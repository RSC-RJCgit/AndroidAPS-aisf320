package app.aaps.implementation.notifications

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IOS_HEARTBEAT_FORGET_CHECKED
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
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
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
 * Backup devices (2026-10-24, per explicit request), only for when the RileyLink is not there (a flat battery, left at
 * home). While [BooleanKey.IosHeartbeatCheckOthers] is on, the app looks for [LOOK_MS] and lists what it saw
 * ([StringNonKey.IosHeartbeatSeen]). The user picks one ([StringKey.IosHeartbeatCheckDevice]); for [CHECK_MS] the app
 * subscribes to everything on it that can notify. It passes when values came in at least [CHECK_MINUTES_NEEDED] different
 * minutes ([StringNonKey.IosHeartbeatBackups]), otherwise it is never shown again ([StringNonKey.IosHeartbeatRejected]).
 * When no RileyLink is found, or it is lost, the backups are tried in the order they passed, each for [BACKUP_WAIT_MS].
 * The silent sound keeps running while they are tried and stops at the first backup value, so there is no gap in which
 * iOS can stop the app. When the RileyLink ticks again, the backup is let go.
 *
 * An Android phone running AAPS with "Send heartbeat to an iPhone" on (v3 `IphoneHeartbeatSender`) announces
 * [AAPS_PHONE_SERVICE_UUID] and sends a value every minute (2026-10-24, per explicit request). Android changes its Bluetooth
 * address about every 15 minutes, so such a phone is saved as [PHONE_BACKUP_ID], not by its identifier, and found again by
 * its service when it is tried. The "forget" entry of the device list ([IOS_HEARTBEAT_FORGET_CHECKED]) clears the devices
 * that failed, so they can be checked again.
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
    private var primaryConnected = false
    private var lastPrimaryValueAt = 0L
    private var lastBackupValueAt = 0L
    private var backupConnected = false
    private var operationStatus = ""
    private val syncGate = HeartbeatSyncGate()

    // Found while scanning: identifier -> (device, signal strength)
    private val found = mutableMapOf<String, Pair<CBPeripheral, Int>>()

    // Devices already written to the log in this scan
    private val logged = mutableSetOf<String>()

    // Look for other devices: identifier -> (name, signal strength)
    private var lookJob: Job? = null
    private val seen = mutableMapOf<String, Pair<String, Int>>()

    // Identifiers seen announcing the AAPS phone heartbeat service, and the one found while looking for it as a backup
    private val phoneIds = mutableSetOf<String>()
    private var phoneScanActive = false
    private var phoneFound: CBPeripheral? = null
    private var phoneFoundName = ""

    // The device being checked, and the minutes (since 1970) in which it sent a value
    private var checkDevice: CBPeripheral? = null
    private var checkName = ""
    private var checkJob: Job? = null
    private var checkConnected = false
    private val checkMinutes = mutableSetOf<Long>()
    private var checkStartedAt = 0L
    private var lastCheckValueAt = 0L

    // The backup in use while the RileyLink is not there
    private var backupDevice: CBPeripheral? = null
    private var backupName = ""
    private var backupJob: Job? = null
    private var backupIndex = 0
    private var backupTickSeen = false
    private var lastBackupWake = 0L

    // Strong reference: the central manager and the device hold their delegate weakly
    private val delegate = RileyLinkDelegate(this)

    // Lazy: `currentNotificationCenter()` needs an app bundle (see IosReminderScheduler).
    private val notificationCenter by lazy { UNUserNotificationCenter.currentNotificationCenter() }

    /** Called once at app start. Follows the settings from then on. */
    fun start() {
        if (started) return
        started = true
        refreshStatus()
        scope.launch {
            preferences.observe(BooleanKey.IosRileyLinkHeartbeat).collect { on -> if (on) turnOn() else turnOff() }
        }
        scope.launch {
            preferences.observe(BooleanKey.IosHeartbeatCheckOthers).collect { on -> if (on) lookAround() else stopLooking() }
        }
        scope.launch {
            preferences.observe(StringKey.IosHeartbeatCheckDevice).collect { id -> if (id.isNotEmpty()) check(id) }
        }
        scope.launch {
            while (true) {
                delay(15_000L)
                refreshStatus()
            }
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
        stopLooking()
        endCheck(save = false)
        stopBackup()
        central?.let { manager ->
            manager.stopScan()
            device?.let { manager.cancelPeripheralConnection(it) }
        }
        device = null
        tickSeen = false
        primaryConnected = false
        lastPrimaryValueAt = 0L
        preferences.put(StringNonKey.IosRileyLinkId, "")
        keepAlive.onHeartbeatConnected(false)
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_off))
    }

    // Keep current reception separate from scan/test messages: finding a device must not claim it is sending ticks.
    private fun setStatus(text: String) {
        operationStatus = text
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = preferences.get(BooleanKey.IosRileyLinkHeartbeat)
        val now = dateUtil.now()
        val lines = mutableListOf<String>()
        val receivingNames = mutableSetOf<String>()
        fun reception(name: String, lastAt: Long, connected: Boolean) {
            val state = heartbeatReception(lastAt, now, connected)
            val key = when (state) {
                HeartbeatReception.WAITING -> return
                HeartbeatReception.RECEIVING -> ImplementationStrings.ios_heartbeat_receiving
                HeartbeatReception.STALE -> ImplementationStrings.ios_heartbeat_stale
                HeartbeatReception.DISCONNECTED -> ImplementationStrings.ios_heartbeat_disconnected
            }
            if (state == HeartbeatReception.RECEIVING) receivingNames.add(name)
            lines.add(rh.gs(key, name, dateUtil.dateAndTimeAndSecondsString(lastAt)))
        }
        if (!enabled) lines.add(rh.gs(ImplementationStrings.ios_rileylink_status_off))
        else {
            reception(device?.name ?: "RileyLink", lastPrimaryValueAt, primaryConnected)
            reception(backupName, lastBackupValueAt, backupConnected)
            if (checkStartedAt > 0L) {
                val seconds = ((now - checkStartedAt).coerceAtLeast(0L) / 1000L).coerceAtMost(CHECK_MS / 1000L).toInt()
                lines.add(rh.gs(ImplementationStrings.ios_heartbeat_test_progress, checkName, seconds,
                    (CHECK_MS / 1000L).toInt(), checkMinutes.size, CHECK_MINUTES_NEEDED))
                lines.add(if (lastCheckValueAt > 0L)
                    rh.gs(ImplementationStrings.ios_heartbeat_test_last, dateUtil.dateAndTimeAndSecondsString(lastCheckValueAt))
                else rh.gs(ImplementationStrings.ios_heartbeat_test_waiting))
                if (!checkConnected) lines.add(rh.gs(ImplementationStrings.ios_heartbeat_test_connecting))
            }
            if (operationStatus.isNotBlank()) lines.add(operationStatus)
        }
        val selected = preferences.get(StringKey.IosHeartbeatCheckDevice)
        if (checkStartedAt == 0L && selected.isNotBlank() && selected != IOS_HEARTBEAT_FORGET_CHECKED) {
            val selectedName = seen[selected]?.first ?: preferences.get(StringNonKey.IosHeartbeatSeen).lines()
                .firstOrNull { it.startsWith("$selected\t") }?.substringAfter('\t') ?: selected
            lines.add(rh.gs(ImplementationStrings.ios_heartbeat_test_not_started, selectedName))
        }
        val inactive = backups().map { it.second }.filter { it !in receivingNames }.distinct()
        if (inactive.isNotEmpty()) lines.add(rh.gs(ImplementationStrings.ios_heartbeat_verified_inactive, inactive.joinToString(", ")))
        if (preferences.get(StringNonKey.IosHeartbeatSeen).isNotBlank()) lines.add(rh.gs(ImplementationStrings.ios_heartbeat_detected_only))
        val audioStatus = rh.gs(if (keepAlive.silentAudioPlaying) ImplementationStrings.ios_heartbeat_audio_playing else ImplementationStrings.ios_heartbeat_audio_stopped)
        lines.add(audioStatus)
        // A saved selection or a passed test is not an active source. Prefer a source with
        // recent received values; otherwise show the latest known stale/disconnected source.
        val source = listOf(
            Triple(device?.name ?: "RileyLink", lastPrimaryValueAt, primaryConnected),
            Triple(backupName, lastBackupValueAt, backupConnected)
        ).filter { it.second > 0L }.maxWithOrNull(
            compareBy<Triple<String, Long, Boolean>> {
                heartbeatReception(it.second, now, it.third) == HeartbeatReception.RECEIVING
            }.thenBy { it.second }
        )
        val heartbeatStatus = when {
            !enabled -> rh.gs(ImplementationStrings.ios_heartbeat_overview_off)
            source == null -> rh.gs(ImplementationStrings.ios_heartbeat_overview_waiting)
            else -> {
                val statusKey = when (heartbeatReception(source.second, now, source.third)) {
                    HeartbeatReception.RECEIVING -> ImplementationStrings.ios_heartbeat_overview_receiving
                    HeartbeatReception.STALE -> ImplementationStrings.ios_heartbeat_overview_stale
                    HeartbeatReception.DISCONNECTED -> ImplementationStrings.ios_heartbeat_overview_disconnected
                    HeartbeatReception.WAITING -> null
                }
                if (statusKey == null) rh.gs(ImplementationStrings.ios_heartbeat_overview_waiting)
                else rh.gs(statusKey, source.first, dateUtil.timeStringWithSeconds(source.second))
            }
        }
        val overviewStatus = "$heartbeatStatus\n$audioStatus"
        if (preferences.get(StringNonKey.IosHeartbeatOverviewStatus) != overviewStatus)
            preferences.put(StringNonKey.IosHeartbeatOverviewStatus, overviewStatus)
        if (enabled) {
            lines.add(rh.gs(ImplementationStrings.ios_heartbeat_sync_interval))
            syncGate.lastRequestAt?.let { last ->
                lines.add(rh.gs(ImplementationStrings.ios_heartbeat_sync_last, dateUtil.dateAndTimeAndSecondsString(last)))
            }
        }
        val text = lines.distinct().joinToString("\n")
        if (preferences.get(StringKey.IosRileyLinkStatus) != text) preferences.put(StringKey.IosRileyLinkStatus, text)
    }

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
            // The look for other devices uses the same scan, so it is left running for that
            if (lookJob?.isActive != true) manager.stopScan()
            val best = found.values.maxByOrNull { it.second }?.first
            if (best == null) {
                aapsLogger.info(LTag.CORE, "RileyLink heartbeat: no device found, trying again in ${RESCAN_MS / 1000} s")
                notify(rh.gs(ImplementationStrings.ios_rileylink_not_found))
                setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_not_found, dateUtil.timeString(dateUtil.now() + RESCAN_MS)))
                startBackup()
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
        primaryConnected = false
        lastPrimaryValueAt = 0L
        peripheral.delegate = delegate
        aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: connecting to ${peripheral.name}")
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_connecting, peripheral.name ?: "RileyLink"))
        manager.connectPeripheral(peripheral, null)
    }

    private fun onTick() {
        lastPrimaryValueAt = dateUtil.now()
        primaryConnected = true
        if (!tickSeen) {
            tickSeen = true
            aapsLogger.info(LTag.CORE, "RileyLink heartbeat: first tick from ${device?.name}")
            // The RileyLink is back: a backup is not needed any more
            stopBackup()
        }
        keepAlive.onHeartbeatTick()
        setStatus("")
        wake("RileyLink tick")
    }

    // What each heartbeat does while iOS lets the app run for a few seconds
    private fun wake(reason: String) {
        // Keep per-tick heartbeat handling and alarm checks. Only the extra sync request is throttled.
        if (syncGate.shouldRequestSync(dateUtil.now())) {
            try {
                nsClient.resend(reason)
                aapsLogger.debug(LTag.CORE, "Heartbeat sync requested: $reason (five-minute interval)")
            } catch (e: Exception) {
                // A failed network request must not skip this heartbeat's local alarm checks.
                aapsLogger.error(LTag.CORE, "Heartbeat sync request failed", e)
            }
            refreshStatus()
        }
        scope.launch {
            try {
                periodicMaintenance.runOnce()
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "RileyLink heartbeat: maintenance failed", e)
            }
        }
    }

    // ---------- Look for other devices and check one (2026-10-24, per explicit request) ----------

    private fun lookAround() {
        val manager = central ?: return
        if (manager.state != CBManagerStatePoweredOn) return
        lookJob?.cancel()
        seen.clear()
        preferences.put(StringNonKey.IosHeartbeatSeen, "")
        manager.scanForPeripheralsWithServices(null, null)
        aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: looking for other devices")
        setStatus(rh.gs(ImplementationStrings.ios_heartbeat_status_looking))
        lookJob = scope.launch {
            delay(LOOK_MS)
            if (scanJob?.isActive != true) manager.stopScan()
            val savedPhone = backups().any { it.first == PHONE_BACKUP_ID }
            val skip = rejected() + backups().map { it.first } + listOfNotNull(device?.identifier?.UUIDString) +
                if (savedPhone) phoneIds else emptySet()
            val list = seen.filterKeys { it !in skip }.entries.sortedByDescending { it.value.second }
            aapsLogger.info(LTag.CORE, "RileyLink heartbeat: other devices found ${list.map { "${it.value.first} ${it.value.second}" }}")
            preferences.put(
                StringNonKey.IosHeartbeatSeen,
                list.joinToString("\n") { (id, value) -> "$id\t" + rh.gs(ImplementationStrings.ios_heartbeat_seen_label, value.first, value.second) }
            )
            setStatus(rh.gs(ImplementationStrings.ios_heartbeat_status_found, list.size))
        }
    }

    private fun stopLooking() {
        if (lookJob?.isActive == true && scanJob?.isActive != true) central?.stopScan()
        lookJob?.cancel()
        lookJob = null
    }

    private fun check(id: String) {
        if (id == IOS_HEARTBEAT_FORGET_CHECKED) {
            forgetRejected()
            return
        }
        val manager = central ?: run {
            setStatus(rh.gs(ImplementationStrings.ios_heartbeat_test_bluetooth_not_ready))
            return
        }
        if (manager.state != CBManagerStatePoweredOn) {
            setStatus(rh.gs(ImplementationStrings.ios_heartbeat_test_bluetooth_not_ready))
            return
        }
        endCheck(save = false)
        val peripheral = manager.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uUIDString = id))).firstOrNull() as? CBPeripheral
        checkName = peripheral?.name ?: seen[id]?.first ?: id
        checkMinutes.clear()
        checkConnected = false
        checkStartedAt = dateUtil.now()
        lastCheckValueAt = 0L
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: checking \"$checkName\"")
        setStatus("")
        if (peripheral != null) {
            checkDevice = peripheral
            peripheral.delegate = delegate
            manager.connectPeripheral(peripheral, null)
        }
        // Without a device to connect to, this ends as "cannot wake the app" after the same wait
        checkJob = scope.launch {
            delay(CHECK_MS)
            endCheck(save = true, id = id)
        }
    }

    private fun forgetRejected() {
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: forgetting ${rejected().size} devices that did not work")
        preferences.put(StringNonKey.IosHeartbeatRejected, "")
        preferences.put(StringKey.IosHeartbeatCheckDevice, "")
        setStatus(rh.gs(ImplementationStrings.ios_heartbeat_forgotten))
        lookAround()
    }

    /** Ends the check. With [save], the result is kept and the user is told. */
    private fun endCheck(save: Boolean, id: String = "") {
        checkJob?.cancel()
        checkJob = null
        checkStartedAt = 0L
        checkDevice?.let { central?.cancelPeripheralConnection(it) }
        checkDevice = null
        if (!save) return
        val works = checkMinutes.size >= CHECK_MINUTES_NEEDED
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: check of \"$checkName\" connected=$checkConnected values in ${checkMinutes.size} minutes, works=$works")
        // A phone changes its address, so it is saved by its service (once), not by this identifier
        val backupId = if (id in phoneIds) PHONE_BACKUP_ID else id
        if (works) preferences.put(
            StringNonKey.IosHeartbeatBackups,
            (backups().filter { it.first != backupId } + (backupId to checkName)).joinToString("\n") { "${it.first}\t${it.second}" }
        )
        else preferences.put(StringNonKey.IosHeartbeatRejected, (rejected() + id).joinToString("\n"))
        // Take it out of the list, so it is not picked again
        preferences.put(StringNonKey.IosHeartbeatSeen, preferences.get(StringNonKey.IosHeartbeatSeen).lines().filterNot { it.startsWith("$id\t") }.joinToString("\n"))
        preferences.put(StringKey.IosHeartbeatCheckDevice, "")
        val text = rh.gs(if (works) ImplementationStrings.ios_heartbeat_check_works else ImplementationStrings.ios_heartbeat_check_fails, checkName)
        notify(text)
        setStatus(text)
    }

    private fun backups(): List<Pair<String, String>> =
        preferences.get(StringNonKey.IosHeartbeatBackups).lines().mapNotNull { line ->
            line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }

    private fun rejected(): List<String> = preferences.get(StringNonKey.IosHeartbeatRejected).lines().filter { it.isNotEmpty() }

    // ---------- Backups while the RileyLink is not there ----------

    private fun startBackup() {
        if (backupDevice != null || backupJob?.isActive == true || backups().isEmpty()) return
        backupIndex = 0
        tryBackup()
    }

    private fun tryBackup() {
        val manager = central ?: return
        val list = backups()
        if (backupIndex >= list.size) {
            aapsLogger.info(LTag.CORE, "RileyLink heartbeat: no backup works, trying again in ${BACKUP_RETRY_MS / 60_000} min")
            notify(rh.gs(ImplementationStrings.ios_heartbeat_no_backup_works))
            setStatus(rh.gs(ImplementationStrings.ios_heartbeat_status_no_backup_works, dateUtil.timeString(dateUtil.now() + BACKUP_RETRY_MS)))
            backupJob = scope.launch {
                delay(BACKUP_RETRY_MS)
                backupIndex = 0
                scope.launch { if (!tickSeen && preferences.get(BooleanKey.IosRileyLinkHeartbeat)) tryBackup() }
            }
            return
        }
        val (id, name) = list[backupIndex]
        if (id == PHONE_BACKUP_ID) {
            tryPhoneBackup(manager, name)
            return
        }
        val peripheral = manager.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uUIDString = id))).firstOrNull() as? CBPeripheral
        if (peripheral == null) {
            backupIndex++
            tryBackup()
            return
        }
        connectBackup(manager, peripheral, name)
    }

    /** An AAPS phone: connected already, or found by its service in [PHONE_SCAN_MS]. Its address is not kept, see the class doc. */
    private fun tryPhoneBackup(manager: CBCentralManager, name: String) {
        val serviceIds = listOf(CBUUID.UUIDWithString(AAPS_PHONE_SERVICE_UUID))
        val connected = manager.retrieveConnectedPeripheralsWithServices(serviceIds).firstOrNull() as? CBPeripheral
        if (connected != null) {
            connectBackup(manager, connected, connected.name?.takeIf { it.isNotBlank() } ?: rh.gs(ImplementationStrings.ios_heartbeat_phone_name))
            return
        }
        phoneFound = null
        phoneFoundName = ""
        phoneScanActive = true
        // With a service filter, because iOS gives a background app no scan results without one. The RileyLink services are
        // in it too: this scan replaces any scan that is looking for the RileyLink.
        manager.scanForPeripheralsWithServices(
            serviceIds + listOf(CBUUID.UUIDWithString(SERVICE_UUID), CBUUID.UUIDWithString(ORANGE_SERVICE_UUID)), null
        )
        setStatus(rh.gs(ImplementationStrings.ios_heartbeat_status_trying_backup, name))
        backupJob = scope.launch {
            delay(PHONE_SCAN_MS)
            phoneScanActive = false
            if (scanJob?.isActive != true && lookJob?.isActive != true) manager.stopScan()
            val phone = phoneFound
            if (phone == null) {
                aapsLogger.info(LTag.CORE, "RileyLink heartbeat: backup \"$name\" not found")
                backupIndex++
                scope.launch { tryBackup() }
            } else scope.launch {
                connectBackup(manager, phone, phoneFoundName.ifBlank { rh.gs(ImplementationStrings.ios_heartbeat_phone_name) })
            }
        }
    }

    private fun connectBackup(manager: CBCentralManager, peripheral: CBPeripheral, name: String) {
        backupDevice = peripheral
        backupName = peripheral.name?.takeIf { it.isNotBlank() } ?: name
        backupTickSeen = false
        backupConnected = false
        lastBackupValueAt = 0L
        peripheral.delegate = delegate
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: trying backup \"$name\"")
        setStatus(rh.gs(ImplementationStrings.ios_heartbeat_status_trying_backup, name))
        manager.connectPeripheral(peripheral, null)
        backupJob = scope.launch {
            delay(BACKUP_WAIT_MS)
            if (!backupTickSeen) nextBackup()
        }
    }

    private fun nextBackup() {
        backupJob?.cancel()
        backupDevice?.let { central?.cancelPeripheralConnection(it) }
        backupDevice = null
        backupConnected = false
        lastBackupValueAt = 0L
        backupIndex++
        scope.launch { tryBackup() }
    }

    private fun stopBackup() {
        backupJob?.cancel()
        backupJob = null
        if (phoneScanActive) {
            phoneScanActive = false
            if (scanJob?.isActive != true && lookJob?.isActive != true) central?.stopScan()
        }
        backupDevice?.let {
            aapsLogger.info(LTag.CORE, "RileyLink heartbeat: letting backup \"$backupName\" go")
            central?.cancelPeripheralConnection(it)
        }
        backupDevice = null
        backupTickSeen = false
        backupConnected = false
        lastBackupValueAt = 0L
    }

    private fun onBackupValue() {
        val now = dateUtil.now()
        lastBackupValueAt = now
        backupConnected = true
        setStatus("")
        if (!backupTickSeen) {
            backupTickSeen = true
            backupJob?.cancel()
            notify(rh.gs(ImplementationStrings.ios_heartbeat_using_backup, backupName))
        }
        // A device may send many values a minute; wake at most about once a minute, like a RileyLink tick
        if (now - lastBackupWake < BACKUP_MIN_WAKE_MS) return
        lastBackupWake = now
        keepAlive.onHeartbeatConnected(true)
        keepAlive.onHeartbeatTick()
        refreshStatus()
        wake("Backup heartbeat")
    }

    private fun notify(text: String) {
        val content = UNMutableNotificationContent().apply { setBody(text) }
        val request = UNNotificationRequest.requestWithIdentifier(NOTICE_ID, content, null)
        notificationCenter.addNotificationRequest(request) { error ->
            if (error != null) aapsLogger.error(LTag.CORE, "RileyLink heartbeat: cannot show the notice: $error")
        }
    }

    // ---------- CoreBluetooth callbacks ----------

    private fun CBPeripheral.isSame(other: CBPeripheral?): Boolean = other != null && identifier.UUIDString == other.identifier.UUIDString

    internal fun handleStateUpdate(central: CBCentralManager) {
        aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: Bluetooth state ${central.state}")
        if (!preferences.get(BooleanKey.IosRileyLinkHeartbeat)) return
        // 2026-10-24, per explicit request: no permission or Bluetooth off used to wait without a word
        when (central.state) {
            CBManagerStatePoweredOn    -> {
                connectOrScan(central)
                if (preferences.get(BooleanKey.IosHeartbeatCheckOthers) && lookJob == null) lookAround()
            }

            CBManagerStateUnauthorized -> {
                primaryConnected = false
                backupConnected = false
                checkConnected = false
                notify(rh.gs(ImplementationStrings.ios_rileylink_no_permission))
                setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_no_permission))
            }

            CBManagerStatePoweredOff   -> {
                primaryConnected = false
                backupConnected = false
                checkConnected = false
                notify(rh.gs(ImplementationStrings.ios_rileylink_bluetooth_off))
                setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_bluetooth_off))
            }
        }
    }

    // iOS started the app again for a tick: take the devices back
    internal fun handleRestore(state: Map<Any?, *>) {
        val restored = (state[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>).orEmpty().mapNotNull { it as? CBPeripheral }
        val saved = preferences.get(StringNonKey.IosRileyLinkId)
        val backupIds = backups().map { it.first }
        for (peripheral in restored) {
            val id = peripheral.identifier.UUIDString
            when {
                id == saved || (saved.isEmpty() && device == null) -> {
                    device = peripheral
                    peripheral.delegate = delegate
                    aapsLogger.info(LTag.CORE, "RileyLink heartbeat: restored ${peripheral.name}")
                }

                id in backupIds                                    -> {
                    backupDevice = peripheral
                    backupName = backups().first { it.first == id }.second
                    backupTickSeen = true
                    peripheral.delegate = delegate
                    aapsLogger.info(LTag.CORE, "RileyLink heartbeat: restored backup ${peripheral.name}")
                }
            }
        }
    }

    /**
     * A device seen while scanning. Every device is logged once with its name and announced services, so the log shows
     * what a device that is not found sends. Taken: one that announces the RileyLink or the OrangeLink service, or whose
     * name says OrangeLink, RileyLink or EmaLink (an OrangeLink Pro calls itself "OrangePro"). While looking for other
     * devices, every other device with a name is kept for the list.
     */
    internal fun handleDiscovered(peripheral: CBPeripheral, advertisement: Map<Any?, *>, rssi: Int) {
        val id = peripheral.identifier.UUIDString
        val name = (advertisement[CBAdvertisementDataLocalNameKey] as? String) ?: peripheral.name ?: ""
        val services = (advertisement[CBAdvertisementDataServiceUUIDsKey] as? List<*>).orEmpty().mapNotNull { (it as? CBUUID)?.UUIDString?.uppercase() }
        if (logged.add(id)) aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: seen \"$name\" rssi $rssi services $services")
        val byService = SERVICE_UUID in services || ORANGE_SERVICE_UUID in services
        val byName = NAME_HINTS.any { name.contains(it, ignoreCase = true) }
        val isPhone = AAPS_PHONE_SERVICE_UUID in services
        if (isPhone) {
            phoneIds.add(id)
            if (phoneScanActive && phoneFound == null) {
                phoneFound = peripheral
                phoneFoundName = name
            }
        }
        if (byService || byName) found[id] = peripheral to rssi
        else if (lookJob?.isActive == true && (name.isNotEmpty() || isPhone))
            seen[id] = name.ifEmpty { rh.gs(ImplementationStrings.ios_heartbeat_phone_name) } to rssi
    }

    internal fun handleConnected(peripheral: CBPeripheral) {
        aapsLogger.debug(LTag.CORE, "RileyLink heartbeat: connected to ${peripheral.name}")
        if (peripheral.isSame(checkDevice)) checkConnected = true
        if (peripheral.isSame(device)) primaryConnected = true
        if (peripheral.isSame(backupDevice)) backupConnected = true
        refreshStatus()
        // All services: the RileyLink service may only show after connecting, and the log lists what is there
        peripheral.discoverServices(null)
    }

    internal fun handleDisconnected(central: CBCentralManager, peripheral: CBPeripheral, error: NSError?) {
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: lost ${peripheral.name} (${error?.localizedDescription})")
        when {
            // The check keeps waiting until its time is up; what came in so far still counts
            peripheral.isSame(checkDevice) -> {
                checkConnected = false
                refreshStatus()
                return
            }

            peripheral.isSame(backupDevice) -> {
                keepAlive.onHeartbeatConnected(false)
                nextBackup()
                return
            }

            !peripheral.isSame(device)      -> return
        }
        keepAlive.onHeartbeatConnected(false)
        primaryConnected = false
        tickSeen = false
        if (!preferences.get(BooleanKey.IosRileyLinkHeartbeat)) return
        notify(rh.gs(ImplementationStrings.ios_rileylink_lost, peripheral.name ?: "RileyLink"))
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_lost, peripheral.name ?: "RileyLink", timeNow()))
        // Waits for the device without a time limit: iOS connects as soon as it is in range again
        central.connectPeripheral(peripheral, null)
        startBackup()
    }

    internal fun handleServices(peripheral: CBPeripheral) {
        val all = peripheral.services.orEmpty().mapNotNull { it as? CBService }
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: ${peripheral.name} services ${all.map { it.UUID.UUIDString }}")
        if (!peripheral.isSame(device)) {
            // A device being checked or a backup: everything it has, to find what can notify
            all.forEach { peripheral.discoverCharacteristics(null, it) }
            return
        }
        val service = all.firstOrNull { it.UUID == CBUUID.UUIDWithString(SERVICE_UUID) }
        if (service == null) {
            aapsLogger.error(LTag.CORE, "RileyLink heartbeat: ${peripheral.name} has no RileyLink service")
            notify(rh.gs(ImplementationStrings.ios_rileylink_no_tick, peripheral.name ?: "RileyLink"))
            setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_no_tick, peripheral.name ?: "RileyLink"))
            startBackup()
            return
        }
        // All of its characteristics, so the log lists them when the timer tick is missing
        peripheral.discoverCharacteristics(null, service)
    }

    internal fun handleCharacteristics(peripheral: CBPeripheral, service: CBService) {
        if (!peripheral.isSame(device)) {
            val canNotify = service.characteristics.orEmpty().mapNotNull { it as? CBCharacteristic }
                .filter { (it.properties and (CBCharacteristicPropertyNotify or CBCharacteristicPropertyIndicate)) != 0uL }
            aapsLogger.info(LTag.CORE, "RileyLink heartbeat: ${peripheral.name} service ${service.UUID.UUIDString} can notify ${canNotify.map { it.UUID.UUIDString }}")
            canNotify.forEach { peripheral.setNotifyValue(true, it) }
            return
        }
        val tick = service.characteristics?.firstOrNull { (it as? CBCharacteristic)?.UUID == CBUUID.UUIDWithString(TIMER_TICK_UUID) } as? CBCharacteristic
        if (tick == null) {
            val list = service.characteristics.orEmpty().mapNotNull { (it as? CBCharacteristic)?.UUID?.UUIDString }
            aapsLogger.error(LTag.CORE, "RileyLink heartbeat: ${peripheral.name} has no timer tick (firmware 2 or newer is needed), it has $list")
            notify(rh.gs(ImplementationStrings.ios_rileylink_no_tick, peripheral.name ?: "RileyLink"))
            setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_no_tick, peripheral.name ?: "RileyLink"))
            startBackup()
            return
        }
        peripheral.setNotifyValue(true, tick)
        setStatus(rh.gs(ImplementationStrings.ios_rileylink_status_waiting, peripheral.name ?: "RileyLink"))
        keepAlive.onHeartbeatConnected(true)
        aapsLogger.info(LTag.CORE, "RileyLink heartbeat: listening to ${peripheral.name}")
    }

    internal fun handleValue(peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        if (characteristic.value == null) return
        when {
            peripheral.isSame(checkDevice)  -> {
                checkConnected = true
                lastCheckValueAt = dateUtil.now()
                checkMinutes.add(lastCheckValueAt / 60_000L)
                refreshStatus()
            }
            peripheral.isSame(backupDevice) -> onBackupValue()
            peripheral.isSame(device) && characteristic.UUID == CBUUID.UUIDWithString(TIMER_TICK_UUID) -> onTick()
        }
    }

    companion object {

        // From LoopKit RileyLinkBLEKit (PeripheralManager+RileyLink.swift): the main service and its timer tick characteristic
        const val SERVICE_UUID = "0235733B-99C5-4197-B856-69219C2A3845"
        const val TIMER_TICK_UUID = "6E6C7910-B89E-43A5-78AF-50C5E2B86F7E"
        // The OrangeLink's own service (LoopKit RileyLinkServiceUUID.orange)
        const val ORANGE_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        // An Android phone running AAPS with "Send heartbeat to an iPhone" on. Must match v3 IphoneHeartbeatSender.SERVICE_UUID.
        const val AAPS_PHONE_SERVICE_UUID = "8F5A2C10-3B4E-4C77-9D21-6A0E5B7C1A01"
        // How a phone backup is saved instead of its identifier, which changes
        private const val PHONE_BACKUP_ID = "aaps-phone"
        private const val PHONE_SCAN_MS = 30_000L
        private val NAME_HINTS = listOf("orang", "riley", "emalink") // "orang" also matches a shortened "OrangPro"
        private const val RESTORE_ID = "aaps.rileylink.heartbeat"
        private const val NOTICE_ID = "aaps.rileylink.heartbeat"
        // 60 s (2026-10-24, per explicit request; was 15 s): LoopFollow took a while to see an OrangeLink Pro
        private const val SCAN_MS = 60_000L
        private const val RESCAN_MS = 120_000L
        // Backup devices (2026-10-24, per explicit request)
        private const val LOOK_MS = 30_000L
        private const val CHECK_MS = 5 * 60_000L
        // Values in 3 different minutes out of 5: a device that only sends something once on connect does not pass
        private const val CHECK_MINUTES_NEEDED = 3
        private const val BACKUP_WAIT_MS = 3 * 60_000L
        private const val BACKUP_RETRY_MS = 10 * 60_000L
        private const val BACKUP_MIN_WAKE_MS = 50_000L
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
    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
        if (error == null) owner.handleValue(peripheral, didUpdateValueForCharacteristic)
    }
}
