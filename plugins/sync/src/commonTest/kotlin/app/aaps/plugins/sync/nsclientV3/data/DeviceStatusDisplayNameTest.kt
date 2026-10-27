package app.aaps.plugins.sync.nsclientV3.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceStatusDisplayNameTest {
    @Test
    fun recognisedLoopSourcesKeepTheirDisplayNames() {
        assertEquals("Trio", deviceStatusDisplayName("Trio"))
        assertEquals("samsung SM-F731B", deviceStatusDisplayName("openaps://samsung SM-F731B"))
        assertNull(deviceStatusDisplayName(null))
        assertNull(deviceStatusDisplayName("Unknown uploader"))
    }
}
