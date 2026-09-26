package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostCriteriaTest {

    @Test
    fun mildFiresOnADaytimeIobRiseInsideTheRawBand() {
        assertTrue(mild())
    }

    @Test
    fun mildStaysOffWhenRawIsAlreadyInTheStrongBand() {
        assertFalse(mild(rawDelta5 = 15.0, rawDelta1 = 15.0))
    }

    @Test
    fun mildFiresOnAMealLeftoverEvenInTheStrongRawBand() {
        assertTrue(
            mild(
                iobChange5 = 0.0,
                cob = 5.0,
                rawDelta5 = 20.0,
                rawDelta1 = 20.0,
                shortDelta = 3.0,
            )
        )
    }

    @Test
    fun mildStaysOffUnder75WhenDeliveryIsAlreadyHot() {
        assertFalse(mild(bg = 130.0, iobChange5 = 0.9))
    }

    @Test
    fun mildWindowWrapsPastMidnightAndClosesAt2() {
        assertTrue(mild(minuteOfDay = 60))
        assertFalse(mild(minuteOfDay = 180))
        assertFalse(mild(minuteOfDay = 8 * 60 + 29))
    }

    @Test
    fun mildStaysOffWhenTheRawValueIsMissing() {
        assertFalse(mild(rawDelta5 = -9999.0))
    }

    @Test
    fun aFastSmbStackRaisesTheMildBar() {
        assertTrue(mild(delta = 5.5, rawDelta5 = 5.5, smbIntervalSec = 9999.0))
        assertFalse(mild(delta = 5.5, rawDelta5 = 5.5, smbIntervalSec = 60.0))
    }

    @Test
    fun strongBoostFiresOnASteepIobRise() {
        assertTrue(strong())
    }

    @Test
    fun strongBoostFiresWhenDeliveryIsSuppressedEvenIfTheLongDeltaLags() {
        assertTrue(
            strong(
                iobChange5 = 0.0,
                delta = 1.0,
                longDelta = 0.0,
                rawDelta5 = 15.0,
                rawDelta1 = 15.0,
                smbCount5 = 0,
                bg = 120.0,
            )
        )
    }

    @Test
    fun strongBoostDoesNotUseTheOvernightHours() {
        assertFalse(strong(minuteOfDay = 60))
        assertTrue(strong(minuteOfDay = 23 * 60))
    }

    @Test
    fun strongBoostIsBlockedByARecentMarkOrHighIob() {
        assertTrue(bg3BoostBlocked(recentBolusGiven = true, recentMild = false, recentMildFailsafe = false, iob = 0.4))
        assertTrue(bg3BoostBlocked(recentBolusGiven = false, recentMild = false, recentMildFailsafe = false, iob = 2.0))
        assertTrue(bg3BoostBlocked(recentBolusGiven = false, recentMild = true, recentMildFailsafe = false, iob = 1.5))
        assertFalse(bg3BoostBlocked(recentBolusGiven = false, recentMild = true, recentMildFailsafe = false, iob = 1.0))
    }

    @Test
    fun aNewPodHighOpensTheWindowBefore830() {
        assertTrue(mild(minuteOfDay = 6 * 60, daytimeBypass = true))
        assertTrue(strong(minuteOfDay = 6 * 60, daytimeBypass = true))
    }

    private fun mild(
        minuteOfDay: Int = 10 * 60,
        daytimeBypass: Boolean = false,
        bg: Double = 140.0,
        delta: Double = 6.0,
        shortDelta: Double = 3.0,
        rawDelta5: Double = 6.0,
        rawDelta1: Double = 5.0,
        iobChange5: Double = 0.5,
        cob: Double = 0.0,
        smbIntervalSec: Double = 9999.0,
    ) = mildBoostShouldFire(
        readyMild = true,
        readyBg3 = true,
        profilePercent = 100,
        tempTargetSet = false,
        boostAutomationsOn = true,
        minuteOfDay = minuteOfDay,
        daytimeBypass = daytimeBypass,
        bg = bg,
        delta = delta,
        shortDelta = shortDelta,
        rawDelta5 = rawDelta5,
        rawDelta1 = rawDelta1,
        iobChange5 = iobChange5,
        cob = cob,
        minutesSinceNormalBolus = Int.MAX_VALUE,
        recentAlarmHypo = false,
        onLowProfile = false,
        mjActive = false,
        steps5 = 0,
        steps30 = 0,
        smbIntervalSec = smbIntervalSec,
        deliveryBaseline = 0.14,
    )

    private fun strong(
        minuteOfDay: Int = 10 * 60,
        daytimeBypass: Boolean = false,
        bg: Double = 150.0,
        delta: Double = 12.0,
        longDelta: Double = 8.0,
        rawDelta5: Double = 16.0,
        rawDelta1: Double = 6.0,
        iobChange5: Double = 0.9,
        smbCount5: Int = 3,
    ) = bg3BoostShouldFire(
        readyBolusGiven = true,
        readyBg3 = true,
        readyMild = true,
        profilePercent = 100,
        boostAutomationsOn = true,
        minuteOfDay = minuteOfDay,
        daytimeBypass = daytimeBypass,
        bg = bg,
        delta = delta,
        longDelta = longDelta,
        rawDelta5 = rawDelta5,
        rawDelta1 = rawDelta1,
        iobChange5 = iobChange5,
        smbCount5 = smbCount5,
        onLowProfile = false,
        mjActive = false,
        steps5 = 0,
        steps30 = 0,
        steps60 = 0,
        smbIntervalSec = 9999.0,
        deliveryBaseline = 0.14,
    )

    @Test
    fun mildFailsafeFiresOnAQuietConfirmedRise() {
        assertTrue(failsafe())
    }

    @Test
    fun mildFailsafeClosesAt9AndAt21() {
        assertFalse(failsafe(minuteOfDay = 8 * 60 + 59))
        assertFalse(failsafe(minuteOfDay = 21 * 60))
        assertTrue(failsafe(minuteOfDay = 20 * 60 + 59))
    }

    @Test
    fun mildFailsafeOpensAtNightAfterANewPodHigh() {
        assertTrue(failsafe(minuteOfDay = 3 * 60, daytimeBypass = true))
    }

    @Test
    fun mildFailsafeStaysOffWhenAnSmbOrTooMuchIobIsPresent() {
        assertFalse(failsafe(smbCount20 = 1))
        assertFalse(failsafe(iob = 0.21))
    }

    @Test
    fun mildFailsafeStaysOffWhenTheLongDeltaLagsOrASiblingJustFired() {
        assertFalse(failsafe(longDelta = 3.5))
        assertFalse(failsafe(readyBg3 = false))
        assertFalse(failsafe(readyMild = false))
        assertFalse(failsafe(tempTargetSet = true))
    }

    private fun failsafe(
        minuteOfDay: Int = 10 * 60,
        daytimeBypass: Boolean = false,
        bg: Double = 120.0,
        delta: Double = 5.4,
        shortDelta: Double = 5.4,
        longDelta: Double = 3.6,
        iob: Double = 0.1,
        smbCount20: Int = 0,
        steps5: Int = 0,
        steps30: Int = 0,
        readyFailsafe: Boolean = true,
        readyMild: Boolean = true,
        readyBg3: Boolean = true,
        tempTargetSet: Boolean = false,
    ) = mildFailsafeShouldFire(
        readyFailsafe = readyFailsafe,
        readyMild = readyMild,
        readyBg3 = readyBg3,
        profilePercent = 100,
        tempTargetSet = tempTargetSet,
        boostAutomationsOn = true,
        minuteOfDay = minuteOfDay,
        daytimeBypass = daytimeBypass,
        bg = bg,
        delta = delta,
        shortDelta = shortDelta,
        longDelta = longDelta,
        iob = iob,
        smbCount20 = smbCount20,
        steps5 = steps5,
        steps30 = steps30,
    )
}
