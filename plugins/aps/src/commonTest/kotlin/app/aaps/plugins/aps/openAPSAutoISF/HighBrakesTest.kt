package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighBrakesTest {

    @Test
    fun nightFiresOnAHighPlateau() {
        val plan = highBrakePlan(sample(minuteOfDay = 23 * 60, bg = 170.0))
        assertTrue(plan.fireNight)
        assertFalse(plan.fireDayHigh)
        assertFalse(plan.fireTwilight)
    }

    @Test
    fun nightStaysClosedWhenUkfIsFalling() {
        val plan = highBrakePlan(sample(minuteOfDay = 23 * 60, bg = 170.0, ukfDelta5 = -1.0))
        assertFalse(plan.fireNight)
    }

    @Test
    fun nightStaysClosedWhenTheHypoPredictionIsMissing() {
        val plan = highBrakePlan(sample(minuteOfDay = 23 * 60, bg = 170.0, hp1Mmol = null))
        assertFalse(plan.fireNight)
    }

    @Test
    fun nightCutsItsOwnTargetWhenIobRisesFast() {
        val plan = highBrakePlan(
            sample(minuteOfDay = 23 * 60, bg = 170.0, ttLowMgdl = 72.0, nightMarkedWithin6 = true, iobChange5 = 1.0)
        )
        assertTrue(plan.cutNight)
        assertFalse(plan.fireNight)
    }

    @Test
    fun dayHighFiresAtTenInTheMorning() {
        val plan = highBrakePlan(sample(minuteOfDay = 10 * 60, bg = 170.0))
        assertTrue(plan.fireDayHigh)
        assertFalse(plan.fireNight)
    }

    @Test
    fun dayMidNeedsMj3OrNoMjLeft() {
        val closed = highBrakePlan(sample(minuteOfDay = 10 * 60, bg = 140.0, mj3OrNoMj = false))
        val open = highBrakePlan(sample(minuteOfDay = 10 * 60, bg = 140.0, mj3OrNoMj = true))
        assertFalse(closed.fireDayMid)
        assertTrue(open.fireDayMid)
    }

    @Test
    fun twilightFiresAtALowerHypoPredictionThanTheNightBrake() {
        val plan = highBrakePlan(sample(minuteOfDay = 5 * 60, bg = 130.0, hp1Mmol = 6.3))
        assertTrue(plan.fireTwilight)
        assertFalse(plan.fireNight)
        assertFalse(plan.fireDayMid)
    }

    @Test
    fun twilightStaysClosedBelowSixPointTwo() {
        val plan = highBrakePlan(sample(minuteOfDay = 5 * 60, bg = 130.0, hp1Mmol = 6.1))
        assertFalse(plan.fireTwilight)
    }

    private fun sample(
        minuteOfDay: Int,
        bg: Double,
        ukfDelta5: Double? = 2.0,
        hp1Mmol: Double? = 7.0,
        ttLowMgdl: Double? = null,
        nightMarkedWithin6: Boolean = false,
        iobChange5: Double = 0.2,
        mj3OrNoMj: Boolean = true,
    ) = HighBrakeSnapshot(
        minuteOfDay = minuteOfDay,
        bg = bg,
        delta = 0.9,
        shortDelta = 0.9,
        longDelta = 2.0,
        ukfDelta5 = ukfDelta5,
        ukfDelta15 = 2.0,
        factorsReady = true,
        duraIsf = 1.4,
        acceIsf = 1.0,
        bgIsf = 1.0,
        ppIsf = 1.0,
        hp1Mmol = hp1Mmol,
        iobChange5 = iobChange5,
        ttLowMgdl = ttLowMgdl,
        steroidsOff = true,
        nightReady30 = true,
        dayReady30 = true,
        twilightReady30 = true,
        twilightReady15 = true,
        nightMarkedWithin6 = nightMarkedWithin6,
        dayMarkedWithin6 = false,
        twilightOwn = false,
        mj3OrNoMj = mj3OrNoMj,
        lowBg50Recent = false,
        daytimeBypass = false,
    )
}
