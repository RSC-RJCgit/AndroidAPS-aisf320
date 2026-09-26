package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceivedAutoIsfTest {

    @Test
    fun plainLoopResultIsSkipped() {
        val rt = RT(runningDynamicIsf = false, algorithm = APSResult.Algorithm.SMB, reason = StringBuilder("COB: 0"))
        assertNull(receivedAutoIsfRow(1L, rt) { it * 18.0 })
    }

    @Test
    fun ukfAndDeltasAreStoredInMgdl() {
        val rt = RT(
            runningDynamicIsf = false,
            algorithm = APSResult.Algorithm.AUTO_ISF,
            bg = 180.0,
            IOB = 1.2,
            units = 0.1,
            autoIsfAcce = 1.4,
            autoIsfFinal = 1.5,
            autoIsfUkfRawBgl = 176.0,
            reason = StringBuilder("Delta: 0.2 ; SDelta: 0.1 ; LDelta: -0.3 ; bg_acce: 1.5 ;")
        )
        val row = receivedAutoIsfRow(50L, rt) { it * 18.0 }!!
        assertEquals(50L, row.timestamp)
        assertEquals(176.0, row.ukfRawBgl)
        assertEquals(1.4, row.acceIsf)
        assertEquals(1.5, row.finalIsf)
        assertEquals(3.6, row.delta)
        assertEquals(1.8, row.shortAvgDelta)
        assertEquals(-5.4, row.longAvgDelta, 0.001)
        assertEquals(1.5, row.bgAcceleration)
        assertEquals(180.0, row.glucose)
        assertEquals(0.1, row.smbDelivered)
    }
}
