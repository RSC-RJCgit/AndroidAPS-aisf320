package app.aaps.implementation.heartbeat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets an iPhone running the AAPS client use this phone as its background heartbeat (2026-10-24, per explicit request), as a
 * backup for when its RileyLink / OrangeLink is not there. Setting: [BooleanKey.IphoneHeartbeatSender].
 *
 * While the setting is on, this phone announces [SERVICE_UUID] over Bluetooth Low Energy and sends a small value on
 * [TICK_UUID] every [TICK_MS] to each device that subscribed. With `bluetooth-central` in its background modes, the iPhone
 * is woken for each value, the same way a RileyLink timer tick wakes it. The iPhone finds this phone by [SERVICE_UUID],
 * not by its address: Android changes its Bluetooth address about every 15 minutes, which would make a saved address
 * useless. The same UUIDs are in `IosRileyLinkHeartbeat` (AAPS_PHONE_SERVICE_UUID) and must stay the same.
 *
 * Works while AAPS runs and the two phones are within Bluetooth range. Needs the Nearby devices permission (Android 12+),
 * which MainActivity asks for while the setting is on.
 */
@Singleton
class IphoneHeartbeatSender @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {

    private val handler = Handler(HandlerThread("IphoneHeartbeat").also { it.start() }.looper)
    private var started = false
    private var server: BluetoothGattServer? = null
    private var tick: BluetoothGattCharacteristic? = null
    private var advertising = false
    private var counter = 0

    // Devices that turned notifications on. Only touched on the handler thread.
    private val subscribed = mutableSetOf<BluetoothDevice>()

    /** Called once at app start. Follows the setting from then on, checked every minute. */
    fun start() {
        if (started) return
        started = true
        handler.post(loop)
    }

    private val loop = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "iPhone heartbeat: failed", e)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        if (!preferences.get(BooleanKey.IphoneHeartbeatSender)) {
            stop()
            return
        }
        if (!permitted()) {
            aapsLogger.debug(LTag.CORE, "iPhone heartbeat: no Nearby devices permission yet")
            return
        }
        if (server == null) open()
        sendTick()
    }

    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (granted(Manifest.permission.BLUETOOTH_ADVERTISE) && granted(Manifest.permission.BLUETOOTH_CONNECT))

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in permitted()
    private fun open() {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        val characteristic = BluetoothGattCharacteristic(
            TICK_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(characteristic) }
        val gattServer = manager.openGattServer(context, callback) ?: return
        gattServer.addService(service)
        server = gattServer
        tick = characteristic

        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        // The name goes in the scan response: next to the 128-bit UUID it does not fit in the 31 bytes of the advertisement
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        aapsLogger.info(LTag.CORE, "iPhone heartbeat: started")
    }

    @SuppressLint("MissingPermission")
    private fun stop() {
        if (server == null && !advertising) return
        try {
            if (permitted()) {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                server?.close()
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "iPhone heartbeat: stop failed", e)
        }
        server = null
        tick = null
        advertising = false
        subscribed.clear()
        aapsLogger.info(LTag.CORE, "iPhone heartbeat: stopped")
    }

    @SuppressLint("MissingPermission")
    private fun sendTick() {
        val gattServer = server ?: return
        val characteristic = tick ?: return
        if (subscribed.isEmpty()) return
        counter = (counter + 1) and 0xFF
        val value = byteArrayOf(counter.toByte())
        for (device in subscribed.toList()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer.notifyCharacteristicChanged(device, characteristic, false, value)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                gattServer.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            aapsLogger.error(LTag.CORE, "iPhone heartbeat: advertising failed, code $errorCode")
        }
    }

    // Bluetooth calls these on its own thread; the work moves to the handler thread
    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) subscribed.remove(device)
                aapsLogger.info(LTag.CORE, "iPhone heartbeat: ${if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected"}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int,
            value: ByteArray?
        ) {
            handler.post {
                if (descriptor.uuid == CCCD_UUID) {
                    val on = value != null && (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
                    if (on) subscribed.add(device) else subscribed.remove(device)
                }
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                // The first value at once, so the iPhone does not wait a minute for it
                sendTick()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (subscribed.contains(device)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(counter.toByte()))
        }
    }

    companion object {

        /** Must match AAPS_PHONE_SERVICE_UUID and AAPS_PHONE_TICK_UUID in `IosRileyLinkHeartbeat` (v4, iOS). */
        val SERVICE_UUID: UUID = UUID.fromString("8F5A2C10-3B4E-4C77-9D21-6A0E5B7C1A01")
        val TICK_UUID: UUID = UUID.fromString("8F5A2C11-3B4E-4C77-9D21-6A0E5B7C1A01")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TICK_MS = 60_000L
    }
}
