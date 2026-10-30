package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-10-30: a Virtual-pump master uploads nothing to a site where a real looper uploads */
class RealLooperWatchTest {

    private val own = "openaps://samsung SM-F731B"
    private val pump = NSDeviceStatus.Pump(reservoir = 100.0)

    private fun status(device: String, withPump: Boolean = true, configuredPump: String? = null) = NSDeviceStatus(
        device = device,
        pump = if (withPump) pump else null,
        configuration = configuredPump?.let { name -> buildJsonObject { put("pump", name) } }
    )

    @Test
    fun anotherAapsTrioOrLoopWithAPumpIsARealLooper() {
        assertTrue(isRealLooperStatus(status("openaps://samsung SM-A366B", configuredPump = "OmnipodDashPumpPlugin"), own))
        assertTrue(isRealLooperStatus(status("Trio"), own))
        assertTrue(isRealLooperStatus(status("loop://iPhone"), own))
    }

    @Test
    fun thisPhoneAnotherVirtualMasterOrAStatusWithoutAPumpIsNot() {
        assertFalse(isRealLooperStatus(status(own), own))
        assertFalse(isRealLooperStatus(status("openaps://samsung SM-A156E", configuredPump = "VirtualPumpPlugin"), own))
        assertFalse(isRealLooperStatus(status("openaps://samsung SM-A156E", withPump = false), own))
        assertFalse(isRealLooperStatus(status("xDrip-DexcomG6"), own))
    }
}
