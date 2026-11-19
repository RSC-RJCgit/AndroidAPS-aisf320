package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RealLooperWatchTest {

    private val own = "openaps://samsung SM-F731B"

    private val pump = NSDeviceStatus.Pump(clock = null, reservoir = 100.0, reservoirDisplayOverride = null, battery = null, status = null, extended = null)

    private fun configuration(pumpName: String?) = NSDeviceStatus.Configuration(
        pump = pumpName, version = null, insulin = null, aps = null, sensitivity = null, smoothing = null, insulinConfiguration = null,
        apsConfiguration = null, sensitivityConfiguration = null, overviewConfiguration = null, safetyConfiguration = null
    )

    private fun status(device: String?, withPump: Boolean = true, pumpName: String? = "Medtrum") = NSDeviceStatus(
        date = 1L, uploaderBattery = null, isCharging = null, device = device, uploader = null,
        pump = if (withPump) pump else null, openaps = null, configuration = configuration(pumpName)
    )

    @Test fun anotherAapsWithRealPumpIsRealLooper() {
        assertThat(isRealLooperStatus(status("openaps://samsung SM-A366B"), own)).isTrue()
    }

    @Test fun ownDeviceIsNotRealLooper() {
        assertThat(isRealLooperStatus(status("openaps://Samsung SM-F731B"), own)).isFalse()
    }

    @Test fun virtualPumpIsNotRealLooper() {
        assertThat(isRealLooperStatus(status("openaps://samsung SM-A366B", pumpName = "VirtualPump"), own)).isFalse()
    }

    @Test fun noPumpSectionIsNotRealLooper() {
        assertThat(isRealLooperStatus(status("openaps://samsung SM-A366B", withPump = false), own)).isFalse()
    }

    @Test fun trioAndLoopAreRealLoopers() {
        assertThat(isRealLooperStatus(status("Trio", pumpName = null), own)).isTrue()
        assertThat(isRealLooperStatus(status("loop://iPhone", pumpName = null), own)).isTrue()
    }

    @Test fun otherOrMissingDeviceIsNotRealLooper() {
        assertThat(isRealLooperStatus(status("xdrip://phone"), own)).isFalse()
        assertThat(isRealLooperStatus(status(null), own)).isFalse()
    }
}
