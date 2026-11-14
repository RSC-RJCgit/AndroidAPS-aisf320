package app.aaps.core.interfaces.aps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BgParabolaCurveTest {

    private val now = 1_700_000_000_000L

    private fun status(a0: Double = 100.0, a1: Double = 5.0, a2: Double = 1.0, minutes: Double = 12.4, corr: Double = 0.95) =
        GlucoseStatusAutoIsf(glucose = a0, date = now, a0 = a0, a1 = a1, a2 = a2, parabolaMinutes = minutes, corrSqu = corr)

    @Test
    fun noCurveWithoutAFit() {
        assertNull(status(corr = 0.0).bgParabolaCurve())
        assertNull(status(minutes = 0.0).bgParabolaCurve())
    }

    @Test
    fun timeIsInStepsOfFiveMinutes() {
        val curve = status().bgParabolaCurve()!!
        // t = -1 (5 minutes ago): 100 - 5 + 1
        assertEquals(96.0, curve.fit.single { it.timestamp == now - 5 * 60_000L }.value, 1e-9)
        // t = 2 (10 minutes ahead): 100 + 10 + 4
        assertEquals(114.0, curve.extrapolation.single { it.timestamp == now + 10 * 60_000L }.value, 1e-9)
    }

    @Test
    fun onePointPerMinuteOverTheFitAndTwentyMinutesAhead() {
        val curve = status().bgParabolaCurve()!!
        assertEquals(13, curve.fit.size) // -12 .. 0
        assertEquals(now - 12 * 60_000L, curve.fit.first().timestamp)
        assertEquals(now, curve.fit.last().timestamp)
        assertEquals(21, curve.extrapolation.size) // 0 .. 20
        assertEquals(now + 20 * 60_000L, curve.extrapolation.last().timestamp)
        // Both parts meet at the newest reading, at a0
        assertEquals(100.0, curve.fit.last().value, 1e-9)
        assertEquals(100.0, curve.extrapolation.first().value, 1e-9)
    }
}
