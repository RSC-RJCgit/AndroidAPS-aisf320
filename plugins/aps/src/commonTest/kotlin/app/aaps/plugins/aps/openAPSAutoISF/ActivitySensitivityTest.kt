package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivitySensitivityTest {

    private fun ratio(
        enabled: Boolean = true,
        tempTargetSet: Boolean = false,
        phoneMoved: Boolean = true,
        stepsKnown: Boolean = true,
        steps5: Int = 0,
        steps10: Int = 0,
        steps15: Int = 0,
        steps30: Int = 0,
        steps60: Int = 0,
        hour: Int = 12,
        bg: Double = 140.0,
        targetBg: Double = 100.0,
        shortDelta: Double = 0.0,
        minutesSinceStart: Long = 120L,
        sleeping: Boolean = false,
        sleepStateExists: Boolean = false,
        ignoreInactivityOvernight: Boolean = true,
        idleStartHour: Int = 22,
        idleEndHour: Int = 6,
        activityScale: Double = 1.0,
        inactivityScale: Double = 1.0,
    ) = activitySensitivityRatio(
        enabled, tempTargetSet, phoneMoved, stepsKnown, steps5, steps10, steps15, steps30, steps60,
        hour, bg, targetBg, shortDelta, minutesSinceStart, sleeping, sleepStateExists,
        ignoreInactivityOvernight, idleStartHour, idleEndHour, activityScale, inactivityScale,
    )

    @Test
    fun aSwitchThatIsOffStaysNeutral() {
        assertEquals(1.0, ratio(enabled = false, steps60 = 3000), 0.001)
    }

    @Test
    fun missingStepsDoNotCountAsSittingStill() {
        assertEquals(1.0, ratio(stepsKnown = false, steps60 = 0), 0.001)
    }

    @Test
    fun hardWalkingLowersTheRatioOnlyWhenThePhoneMoved() {
        assertEquals(0.7, ratio(steps5 = 301, steps60 = 3000), 0.001)
        assertEquals(1.0, ratio(phoneMoved = false, steps5 = 301, steps60 = 3000), 0.001)
    }

    @Test
    fun aQuieterWalkLowersTheRatioLess() {
        assertEquals(0.85, ratio(steps60 = 801), 0.001)
    }

    @Test
    fun sittingStillRaisesTheRatio() {
        assertEquals(1.2, ratio(phoneMoved = false, steps60 = 10, bg = 140.0), 0.001)
        assertEquals(1.1, ratio(phoneMoved = false, steps60 = 100, bg = 140.0), 0.001)
    }

    @Test
    fun aFallingGlucoseDoesNotAddInsulinForSittingStill() {
        assertEquals(1.0, ratio(phoneMoved = false, steps60 = 10, shortDelta = -1.0), 0.001)
    }

    @Test
    fun overnightSittingStaysNeutral() {
        assertEquals(1.0, ratio(hour = 23, steps60 = 10), 0.001)
        assertEquals(1.2, ratio(hour = 23, steps60 = 10, ignoreInactivityOvernight = false), 0.001)
    }

    @Test
    fun theFirstHourAfterStartDoesNotTreatQuietStepsAsSittingStill() {
        assertEquals(1.0, ratio(minutesSinceStart = 10, steps60 = 10), 0.001)
    }

    @Test
    fun aTempTargetWinsOverStepsAndAutosensWinsOverTdd() {
        assertEquals(0.8, loopSensitivityRatio(true, 0.8, 0.7, true, 1.1, true, 1.3), 0.001)
        assertEquals(0.7, loopSensitivityRatio(false, 0.8, 0.7, true, 1.1, true, 1.3), 0.001)
        assertEquals(1.1, loopSensitivityRatio(false, 1.0, 1.0, true, 1.1, true, 1.3), 0.001)
        assertEquals(1.3, loopSensitivityRatio(false, 1.0, 1.0, false, 1.1, true, 1.3), 0.001)
        assertEquals(1.0, loopSensitivityRatio(false, 1.0, 1.0, false, 1.1, false, 1.3), 0.001)
        assertEquals(1.0, loopSensitivityRatio(false, 1.0, 1.0, false, 1.1, true, null), 0.001)
    }
}
