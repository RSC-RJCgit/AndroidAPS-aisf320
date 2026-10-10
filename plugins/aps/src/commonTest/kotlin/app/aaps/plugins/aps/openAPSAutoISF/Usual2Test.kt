package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Usual2Test {

    @Test
    fun block1FiresOnADaytimeWalkWhenTheThresholdIsLow() {
        assertEquals("1", usual())
    }

    @Test
    fun block2FiresForANightLevelThresholdWithoutTheStepCount() {
        assertEquals("2", usual(steps60 = 0, steps180 = 0, iobTh = 19, minuteOfDay = 10 * 60))
    }

    @Test
    fun block3FiresOnAWakingRiseBeforeTheDaytimeWalkWindow() {
        assertEquals(
            "3",
            usual(minuteOfDay = 6 * 60, steps60 = 0, steps180 = 0, iobTh = 40, bg = 160.0)
        )
    }

    @Test
    fun aThresholdAlreadyAt70DoesNotFire() {
        assertNull(usual(iobTh = 70))
    }

    @Test
    fun steroidsOnOrATempTargetKeepsItClosed() {
        assertNull(usual(steroidsOff = false))
        assertNull(usual(tempTargetSet = true))
    }

    @Test
    fun anIobOf50NeedsTheFallToHaveStopped() {
        assertNull(usual(iobTh = 50, steps60 = 0, steps180 = 0, delta = -1.0, shortDelta = -1.0, minuteOfDay = 10 * 60))
        assertEquals(
            "2",
            usual(iobTh = 50, steps60 = 0, steps180 = 0, delta = 0.0, shortDelta = 0.0, minuteOfDay = 10 * 60)
        )
    }

    private fun usual(
        minuteOfDay: Int = 12 * 60,
        iobTh: Int = 40,
        bg: Double = 110.0,
        delta: Double = 1.0,
        shortDelta: Double = 1.0,
        steps60: Int = 50,
        steps180: Int = 10,
        cob: Double = 0.0,
        steroidsOff: Boolean = true,
        tempTargetSet: Boolean = false,
        earlyAfterShower: Boolean = false,
    ) = usual2Block(
        ready = true,
        profilePercent = 100,
        tempTargetSet = tempTargetSet,
        iobTh = iobTh,
        bg = bg,
        delta = delta,
        shortDelta = shortDelta,
        steroidsOff = steroidsOff,
        minuteOfDay = minuteOfDay,
        steps60 = steps60,
        steps180 = steps180,
        cob = cob,
        earlyAfterShower = earlyAfterShower,
    )
}
