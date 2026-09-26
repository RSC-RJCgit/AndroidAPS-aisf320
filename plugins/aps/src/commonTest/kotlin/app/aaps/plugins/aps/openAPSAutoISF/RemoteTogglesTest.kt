package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteTogglesTest {

    private fun mgdl(mmol: Double): Double = mmol * Constants.MMOLL_TO_MGDL

    @Test
    fun eachCodeMatchesOnlyItsOwnTarget() {
        assertEquals(RemoteToggleCode.SMB_DOWN, remoteToggleCode(mgdl(5.002)))
        assertEquals(RemoteToggleCode.SMB_UP, remoteToggleCode(mgdl(5.004)))
        assertEquals(RemoteToggleCode.SENSOR_AGE, remoteToggleCode(mgdl(5.006)))
        assertEquals(RemoteToggleCode.BOOST, remoteToggleCode(mgdl(5.008)))
        assertEquals(RemoteToggleCode.PP_DOWN, remoteToggleCode(mgdl(5.012)))
        assertEquals(RemoteToggleCode.PP_UP, remoteToggleCode(mgdl(5.014)))
        assertEquals(RemoteToggleCode.PP_HIGH_DOWN, remoteToggleCode(mgdl(5.056)))
        assertEquals(RemoteToggleCode.PP_HIGH_UP, remoteToggleCode(mgdl(5.058)))
        assertEquals(RemoteToggleCode.ACCE_DOWN, remoteToggleCode(mgdl(5.016)))
        assertEquals(RemoteToggleCode.ACCE_UP, remoteToggleCode(mgdl(5.018)))
        assertEquals(RemoteToggleCode.ACCE_HIGH_DOWN, remoteToggleCode(mgdl(5.062)))
        assertEquals(RemoteToggleCode.ACCE_HIGH_UP, remoteToggleCode(mgdl(5.064)))
        assertEquals(RemoteToggleCode.HIGH_ISF_DOWN, remoteToggleCode(mgdl(5.068)))
        assertEquals(RemoteToggleCode.HIGH_ISF_UP, remoteToggleCode(mgdl(5.070)))
        assertEquals(RemoteToggleCode.MAX_LOW_DOWN, remoteToggleCode(mgdl(5.080)))
        assertEquals(RemoteToggleCode.MAX_LOW_UP, remoteToggleCode(mgdl(5.082)))
        assertEquals(RemoteToggleCode.MAX_DOWN, remoteToggleCode(mgdl(5.086)))
        assertEquals(RemoteToggleCode.MAX_UP, remoteToggleCode(mgdl(5.088)))
        assertEquals(RemoteToggleCode.TOD_0002_DOWN, remoteToggleCode(mgdl(5.092)))
        assertEquals(RemoteToggleCode.TOD_0002_UP, remoteToggleCode(mgdl(5.094)))
        assertEquals(RemoteToggleCode.TOD_0204_DOWN, remoteToggleCode(mgdl(5.098)))
        assertEquals(RemoteToggleCode.TOD_0204_UP, remoteToggleCode(mgdl(5.100)))
        assertEquals(RemoteToggleCode.TOD_0406_DOWN, remoteToggleCode(mgdl(5.104)))
        assertEquals(RemoteToggleCode.TOD_0406_UP, remoteToggleCode(mgdl(5.106)))
        assertEquals(RemoteToggleCode.TOD_0609_DOWN, remoteToggleCode(mgdl(5.110)))
        assertEquals(RemoteToggleCode.TOD_0609_UP, remoteToggleCode(mgdl(5.112)))
        assertEquals(RemoteToggleCode.TOD_0912_DOWN, remoteToggleCode(mgdl(5.116)))
        assertEquals(RemoteToggleCode.TOD_0912_UP, remoteToggleCode(mgdl(5.118)))
        assertEquals(RemoteToggleCode.TOD_1218_DOWN, remoteToggleCode(mgdl(5.122)))
        assertEquals(RemoteToggleCode.TOD_1218_UP, remoteToggleCode(mgdl(5.124)))
        assertEquals(RemoteToggleCode.TOD_1822_DOWN, remoteToggleCode(mgdl(5.128)))
        assertEquals(RemoteToggleCode.TOD_1822_UP, remoteToggleCode(mgdl(5.130)))
        assertEquals(RemoteToggleCode.TOD_2200_DOWN, remoteToggleCode(mgdl(5.134)))
        assertEquals(RemoteToggleCode.TOD_2200_UP, remoteToggleCode(mgdl(5.136)))
        assertEquals(RemoteToggleCode.GRAPH2, remoteToggleCode(mgdl(5.138)))
        assertEquals(RemoteToggleCode.CLOUD_LOGS, remoteToggleCode(mgdl(5.140)))
        assertEquals(RemoteToggleCode.MJ_NO, remoteToggleCode(mgdl(5.144)))
        assertEquals(RemoteToggleCode.MJ3, remoteToggleCode(mgdl(5.146)))
        assertEquals(RemoteToggleCode.MJ_ACTIVE, remoteToggleCode(mgdl(5.222)))
        assertEquals(RemoteToggleCode.MJ2, remoteToggleCode(mgdl(5.224)))
        assertNull(remoteToggleCode(mgdl(5.142)))
        assertNull(remoteToggleCode(mgdl(5.0)))
        assertNull(remoteToggleCode(mgdl(5.010)))
    }

    @Test
    fun nudgesStopAtTheFloorAndTheCap() {
        assertEquals(0.13, nudgeDown(0.14, 0.1), 0.0001)
        assertEquals(0.1, nudgeDown(0.1, 0.1), 0.0001)
        assertEquals(0.15, nudgeUp(0.14, 0.5), 0.0001)
        assertEquals(0.5, nudgeUp(0.5, 0.5), 0.0001)
        assertEquals(1.0, nudgeUp(1.0, 1.0), 0.0001)
        assertEquals(0.0, nudgeDown(0.0, 0.0), 0.0001)
        assertEquals(0.15, nudgeUp(0.15, 0.15), 0.0001)
        assertEquals(0.65, nudgeDown(0.70, 0.55, 0.05), 0.0001)
        assertEquals(0.55, nudgeDown(0.55, 0.55, 0.05), 0.0001)
        assertEquals(1.0, nudgeUp(0.97, 1.0, 0.05), 0.0001)
        assertEquals(0.0, nudgeDown(0.05, 0.0, 0.1), 0.0001)
        assertEquals(2.0, nudgeUp(1.95, 2.0, 0.1), 0.0001)
        assertEquals("SB.14", compactSettingNote("SB", 0.14, 2, omitLeadingZero = true))
        assertEquals("HI1.0", compactSettingNote("HI", 1.0, 1))
        assertEquals(-0.1, nudgeDown(0.0, -2.0, 0.1), 0.0001)
        assertEquals(-2.0, nudgeDown(-2.0, -2.0, 0.1), 0.0001)
        assertEquals(2.0, nudgeUp(1.95, 2.0, 0.1), 0.0001)
        assertEquals("T+0.0", todOffsetNote(0.0))
        assertEquals("T-0.1", todOffsetNote(-0.1))
        assertEquals("T+0.5", todOffsetNote(0.5))
    }

    @Test
    fun liveWeightMovesOnlyWhenItStillMatchesTheBaseline() {
        assertTrue(liveMatchesBaseline(0.08, 0.0805))
        assertFalse(liveMatchesBaseline(0.08, 0.082))
    }
}
