package app.aaps.implementation.notifications

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.ImplementationStrings
import app.aaps.implementation.maintenance.PeriodicMaintenance
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject

/**
 * Keeps the app running in the background with a RileyLink (or OrangeLink / EmaLink) instead of silent sound
 * (2026-10-23, per explicit request). Setting: [BooleanKey.IosRileyLinkHeartbeat].
 *
 * The device's firmware sends a "timer tick" about once a minute when the app subscribes to it. With
 * `UIBackgroundModes = bluetooth-central` iOS wakes the app for each tick, for a few seconds. On each tick this loads from
 * Nightscout, runs the regular maintenance (the stale BG alarm is in it) and moves the "stopped" notice of
 * [IosBackgroundKeepAlive] into the future, so a heartbeat that stops is reported. Loop uses the same tick
 * (LoopKit `PeripheralManager.setTimerTickEnabled`, which subscribes to [TIMER_TICK_UUID]).
 *
 * While the device is connected, the silent sound is not needed and stops. When it is lost, the silent sound starts
 * again if its own setting is on, so the app is not left asleep. iOS reconnects by itself when the device comes back in
 * range, because a connect request never times out.
 *
 * The first time it is switched on it connects to the strongest device found in [SCAN_MS]. That device is remembered
 * ([StringNonKey.IosRileyLinkId]); switching the setting off forgets it, so switching it on again picks the nearest one.
 *
 * Everything runs on the main thread (a null queue for the central manager means the main queue).
 */
@OptIn(ExperimentalForeignApi::class)
@SingleIn(AppScope::class)
@Inject
class IosRileyLinkHeartbeat(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rh: TextResolver,
    private val keepAlive: IosBackgroundKeepAlive,
    private val nsClient: NsClient,
    private val periodicMaintenance: PeriodicMaintenance,
    private val dateUtil: DateUtil
) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var started = false
    private var central: CBCentralManager? = null
    private var device: CBPeripheral? = null
    private var scanJob: Job? = null
    private var tickSeen = false

    // Found while scanning: identifier -> (device, signal strength)
    private val found = mutableMapOf<String, Pair<CBPeripheral, Int>>()

    // Devices already written to the log in this scan
    private val logged = mutableSetOf<String>()

    // Strong reference: the central manager and the device hold their delegate weakly
    private val delegate = RileyLinkDelegate(this)

    // Lazy: `currentNotificationCenter()` needs an app bundle (see IosReminderScheduler).
    private val notificationCenter by lazy { UNUserNotificationCenter.currentNotificationCenter() }

    /** Called once at app start. Follows the setting from then on. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            preferences.observe(BooleanKey.IosRileyLinkHeartbeat).collect { on -> if (on) turnOn() else turnOff() }
        }
    }

    private fun turnOn() {
        // The restore identifier lets iOS start the app again for a tick after it ended it in the background
        val manager = central ?: CBCentralManager(delegate, null, mapOf<Any?, Any?>(CBCentralManagerOptionRestoreIdentifierKey to RESTORE_ID))
            .also { central = it }
        // Not powered on yet: centralManagerDidUpdateState carries on when Bluetooth is ready
        if (manager.state == CBManagerStatePoweredOn) connectOrScan(manager)
    }

    private fun turnOff() {
        scanJob?.cancel()
        central?.let { manager ->
            manager.stopScan()
            device?.let { manager.cancelPeripheralConnection(it) }
        }
        device = null
        tickSeen = false
        preferences.put(StringNonKey.IosRileyLinkId, "")
        keepAlive.onHeartbeatConnected(false)
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_off))
    }

    // The read-only line under the switch (2026-10-24, per explicit request)
    private fun setStatus(text: String) = preferences.put(StringKey.IosRileyLinkStatus, text)

    private fun timeNow(): String = dateUtil.timeString(dateUtil.now())

    private fun connectOrScan(manager: CBCentralManager) {
        if (device != null) return
        val saved = preferences.get(StringNonKey.IosRileyLinkId)
        if (saved.isNotEmpty()) {
            val known = manager.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uUIDString = saved))).firstOrNull() as? CBPeripheral
            if (known != null) {
                connect(manager, known)
                return
            }
        }
        // 2026-10-24, per explicit request: one that another app on this phone (LoopFollow, Loop, Trio) is connected to. It does
        // not announce itself while connected, so a scan would miss it. Both apps then share the one connection.
        val serviceIds = listOf(CBUUID.UUIDWithString(SERVICE_UUID), CBUUID.UUIDWithString(ORANGE_SERVICE_UUID))
        val connected = manager.retrieveConnectedPeripheralsWithServices(serviceIds).firstOrNull() as? CBPeripheral
        if (connected != null) {
            aapsLogger.info(LTag.CORE, "RileyLink heartbeat: ${connected.name} is already connected on this phone, using it")
            preferences.put(StringNonKey.IosRileyLinkId, connected.identifier.UUIDString)
            connect(manager, connected)
            return
        }
        scan(manager)
    }

    private fun scan(manager: CBCentralManager) {
        if (scanJob?.isActive == true) return
        found.clear()
        logged.clear()
        // 2026-10-24, per explicit request: no service filter. An OrangeLink Pro ("OrangePro") showed in the Bluetooth list long
        // before LoopFollow saw it as a RileyLink, so it is also taken by name. handleDiscovered picks the devices.
        manager.scanForPeripheralsWithServices(null, null)
        aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: scanning")
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_scanning))
        scanJob = scope.launch {
            delay(SCAN_MS)
            manager.stopScan()
            val best = found.values.maxByOrNull { it.second }?.first
            if (best == null) {
                aapsLogger.info(LTag.CORE, "RileyLink heartbeat: no device found, trying again in ${RESCAN_MS / 1000} s")
                notify(rh.gs(ImplementationStrings.ios_rileylink_not_found))
                setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_not_found, dateUtil.timeString(dateUtil.now() + RESCAN_MS)))
                delay(RESCAN_MS)
                // connectOrScan, not scan: by now another app may have connected to it. In a new coroutine, because scan()
                // does nothing while this job (its own scanJob) is still active, so the retry used to never scan.
                scope.launch { if (preferences.get(BooleanKey.IosRileyLinkHeartbeat) && device == null) connectOrScan(manager) }
                return@launch
            }
            preferences.put(StringNonKey.IosRileyLinkId, best.identifier.UUIDString)
            connect(manager, best)
        }
    }

    private fun connect(manager: CBCentralManager, peripheral: CBPeripheral) {
        device = peripheral
        peripheral.delegate = delegate
        aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: connecting to ${peripheral.name}")
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_connecting, peripheral.name ?: "RileyLink"))
        manager.connectPeripheral(peripheral, null)
    }

    private fun onTick() {
        if (!tickSeen) {
            tickSeen = true
            aapsLogger.info(LTag.CORE, "RileyLink heartbeat: first tick from ${device?.name}")
        }
        keepAlive.onHeartbeatTick()
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_tick, device?.name ?: "RileyLink", timeNow()))
        nsClient.resend("RileyLink tick")
        scope.launch {
            try {
                periodicMaintenance.runOnce()
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "RileyLink heartbeat: maintenance failed", e)
            }
        }
    }

    private fun notify(text: String) {
        val content = UNMutableNotificationContent().apply { setBody(text) }
        val request = UNNotificationRequest.requestWithIdentifier(NOTICE_ID, content, null)
        notificationCenter.addNotificationRequest(request) { error ->
            if (error != null) aapsLogger.error(LTag.CORE, "RileyLink heartbeat: cannot show the notice: $error")
        }
    }

    internal fun handleStateUpdate(central: CBCentralManager) {
        aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: Bluetooth state ${central.state}")
        if (!preferences.get(BooleanKey.IosRileyLinkHeartbeat)) return
        // 2026-10-24, per explicit request: no permission or Bluetooth off used to wait without a word
        when (central.state) {
            CBManagerStatePoweredOn    -> connectOrScan(central)
            CBManagerStateUnauthorized -> {
                notify(rh.gs(ImplementationStrings.ios_rileylink_no_permission))
                setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_no_permission))
            }

            CBManagerStatePoweredOff   -> {
                notify(rh.gs(ImplementationStrings.ios_rileylink_bluetooth_off))
                setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_bluetooth_off))
            }
        }
    }

    // iOS started the app again for a tick: take the device back
    internal fun handleRestore(state: Map<Any?, *>) {
        val restored = (state[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>)?.firstOrNull() as? CBPeripheral ?: return
        device = restored
        restored.delegate = delegate
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: restored ${restored.name}")
    }

    /**
     * A device seen while scanning. Every device is logged once with its name and announced services, so the log shows
     * what a device that is not found sends. Taken: one that announces the RileyLink or the OrangeLink service, or whose
     * name says OrangeLink, RileyLink or EmaLink (an OrangeLink Pro calls itself "OrangePro").
     */
    internal fun handleDiscovered(peripheral: CBPeripheral, advertisement: Map<Any?, *>, rssi: Int) {
        val id = peripheral.identifier.UUIDString
        val name = (advertisement[CBAdvertisementDataLocalNameKey] as? String) ?: peripheral.name ?: ""
        val services = (advertisement[CBAdvertisementDataServiceUUIDsKey] as? List<*>).orEmpty().mapNotNull { (it as? CBUUID)?.UUIDString?.uppercase() }
        if (logged.add(id)) aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: seen \"$name\" rssi $rssi services $services")
        val byService = SERVICE_UUID in services || ORANGE_SERVICE_UUID in services
        val byName = NAME_HINTS.any { name.contains(it, ignoreCase = true) }
        if (byService || byName) found[id] = peripheral to rssi
    }

    internal fun handleConnected(peripheral: CBPeripheral) {
        aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: connected to ${peripheral.name}")
        // All services: the RileyLink service may only show after connecting, and the log lists what is there
        peripheral.discoverServices(null)
    }

    internal fun handleDisconnected(central: CBCentralManager, peripheral: CBPeripheral, error: NSError?) {
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: lost ${peripheral.name} (${error?.localizedDescription})")
        keepAlive.onHeartbeatConnected(false)
        tickSeen = false
        if (!preferences.get(BooleanKey.IosRileyLinkHeartbeat)) return
        notify(rh.gs(ImplementationStrings.ios_rileylink_lost, peripheral.name ?: "RileyLink"))
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_lost, peripheral.name ?: "RileyLink", timeNow()))
        // Waits for the device without a time limit: iOS connects as soon as it is in range again
        central.connectPeripheral(peripheral, null)
    }

    internal fun handleServices(peripheral: CBPeripheral) {
        val all = peripheral.services.orEmpty().mapNotNull { it as? CBService }
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: ${peripheral.name} services ${all.map { it.UUID.UUIDString }}")
        val service = all.firstOrNull { it.UUID == CBUUID.UUIDWithString(SERVICE_UUID) }
        if (service == null) {
            aapsLogger.error(LTag.CORE, "RileyLink heartbeat: ${peripheral.name} has no RileyLink service")
            notify(rh.gs(ImplementationStrings.ios_rileylink_no_tick, peripheral.name ?: "RileyLink"))
            setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_no_tick, peripheral.name ?: "RileyLink"))
            return
        }
        // All of its characteristics, so the log lists them when the timer tick is missing
        peripheral.discoverCharacteristics(null, service)
    }

    internal fun handleCharacteristics(peripheral: CBPeripheral, service: CBService) {
        val tick = service.characteristics?.firstOrNull { (it as? CBCharacteristic)?.UUID == CBUUID.UUIDWithString(TIMER_TICK_UUID) } as? CBCharacteristic
        if (tick == null) {
            val list = service.characteristics.orEmpty().mapNotNull { (it as? CBCharacteristic)?.UUID?.UUIDString }
            aapsLogger.error(LTag.CORE, "RileyLink heartbeat: ${peripheral.name} has no timer tick (firmware 2 or newer is needed), it has $list")
            notify(rh.gs(ImplementationStrings.ios_rileylink_no_tick, peripheral.name ?: "RileyLink"))
            setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_no_tick, peripheral.name ?: "RileyLink"))
            return
        }
        peripheral.setNotifyValue(true, tick)
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_waiting, peripheral.name ?: "RileyLink"))
        keepAlive.onHeartbeatConnected(true)
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: listening to ${peripheral.name}")
    }

    internal fun handleValue(characteristic: CBCharacteristic) {
        if (characteristic.UUID == CBUUID.UUIDWithString(TIMER_TICK_UUID)) onTick()
    }

    companion object {

        // From LoopKit RileyLinkBLEKit (PeripheralManager+RileyLink.swift): the main service and its timer tick characteristic
        const val SERVICE_UUID = "0235733B-99C5-4197-B856-69219C2A3845"
        const val TIMER_TICK_UUID = "6E6C7910-B89E-43A5-78AF-50C5E2B86F7E"
        // The OrangeLink's own service (LoopKit RileyLinkServiceUUID.orange)
        const val ORANGE_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        private val NAME_HINTS = listOf("orang", "riley", "emalink") // "orang" also matches a shortened "OrangPro"
        private const val RESTORE_ID = "aaps.rileylink.heartbeat"
        private const val NOTICE_ID = "aaps.rileylink.heartbeat"
        // 60 s (2026-10-24, per explicit request; was 15 s): LoopFollow took a while to see an OrangeLink Pro
        private const val SCAN_MS = 60_000L
        private const val RESCAN_MS = 120_000L
    }
}

/**
 * The CoreBluetooth delegate of [IosRileyLinkHeartbeat]. A separate top-level class because an Objective-C subclass cannot be
 * an inner class; it only passes each callback on. Everything arrives on the main queue.
 */
@OptIn(ExperimentalForeignApi::class)
private class RileyLinkDelegate(private val owner: IosRileyLinkHeartbeat) : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) = owner.handleStateUpdate(central)

    override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) = owner.handleRestore(willRestoreState)

    override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) =
        owner.handleDiscovered(didDiscoverPeripheral, advertisementData, RSSI.intValue)

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) = owner.handleConnected(didConnectPeripheral)

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) =
        owner.handleDisconnected(central, didDisconnectPeripheral, error)

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) = owner.handleServices(peripheral)

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) =
        owner.handleCharacteristics(peripheral, didDiscoverCharacteristicsForService)

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) =
        owner.handleValue(didUpdateValueForCharacteristic)
}
