package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReArmLimitTest {

    private val min = 60_000L

    @Test
    fun theFourthTtInHalfAnHourIsHeldBack() {
        // 28 Nov: EDSR at 04:30, 04:36, 04:42, then 04:49 held back
        val limit = ReArmLimit()
        listOf(0L, 6L, 12L).forEach { m ->
            assertNull(limit.heldBack("EDSR", m * min))
            limit.note("EDSR", m * min)
        }
        assertNotNull(limit.heldBack("EDSR", 19 * min))
        assertNull(limit.heldBack("EDSR", 30 * min))     // the 04:30 one has left the window
        assertNull(limit.heldBack("SRS", 19 * min))      // each block counts on its own
    }

    @Test
    fun theIobLimitHoldsBackAtOrAbove() {
        val limit = ReArmLimit()
        assertNull(limit.heldBack("EDSR", 0L, iob = 0.62, maxIob = 0.9))
        assertNotNull(limit.heldBack("EDSR", 0L, iob = 0.93, maxIob = 0.9))
        assertNotNull(limit.heldBack("EDSR", 0L, iob = 0.9, maxIob = 0.9))
        assertNull(limit.heldBack("SRS", 0L, iob = 2.5))  // no IOB limit given
    }
}
