package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class FastRiseSizeTierTest {

    @Test
    fun strongRiseCutsToOneFifth() {
        val decision = fastRiseSizeDecision(rise(delta = 18.0, shortDelta = 18.0, longDelta = 18.0))
        assertEquals(FastRiseSizeTier.Size0201, decision.tier)
        assertEquals(0.2, decision.factor)
    }

    @Test
    fun moderateRiseFollowsBgAndSize() {
        val high = fastRiseSizeDecision(rise(delta = 12.6, shortDelta = 7.2, longDelta = 7.2, bg = 162.0))
        val mid = fastRiseSizeDecision(rise(delta = 12.6, shortDelta = 7.2, longDelta = 7.2, bg = 8.8 * 18.0))
        val largeSmb = fastRiseSizeDecision(rise(delta = 12.6, shortDelta = 7.2, longDelta = 7.2, bg = 140.0, microBolus = 1.0))
        val smallSmb = fastRiseSizeDecision(rise(delta = 12.6, shortDelta = 7.2, longDelta = 7.2, bg = 140.0, microBolus = 0.2))
        assertEquals(FastRiseSizeTier.Size0602, high.tier)
        assertEquals(0.6, high.factor)
        assertEquals(FastRiseSizeTier.Size0653, mid.tier)
        assertEquals(0.65, mid.factor)
        assertEquals(FastRiseSizeTier.Size0504, largeSmb.tier)
        assertEquals(0.5, largeSmb.factor)
        assertEquals(FastRiseSizeTier.Uncapped0564, smallSmb.tier)
        assertEquals(1.0, smallSmb.factor)
    }

    @Test
    fun mildRiseFollowsBgHourAndSize() {
        val high = fastRiseSizeDecision(rise(delta = 7.2, shortDelta = 4.0, longDelta = 4.0, bg = 170.0))
        val mid = fastRiseSizeDecision(rise(delta = 7.2, shortDelta = 4.0, longDelta = 4.0, bg = 150.0))
        val earlyHour = fastRiseSizeDecision(rise(delta = 7.2, shortDelta = 4.0, longDelta = 4.0, bg = 140.0, microBolus = 0.1, hour = 4))
        val daytimeSmall = fastRiseSizeDecision(rise(delta = 7.2, shortDelta = 4.0, longDelta = 4.0, bg = 140.0, microBolus = 0.1, hour = 12))
        assertEquals(FastRiseSizeTier.Size0900, high.tier)
        assertEquals(0.9, high.factor)
        assertEquals(FastRiseSizeTier.Size0850, mid.tier)
        assertEquals(0.85, mid.factor)
        assertEquals(FastRiseSizeTier.Size0750, earlyHour.tier)
        assertEquals(0.75, earlyHour.factor)
        assertEquals(FastRiseSizeTier.Uncapped0707, daytimeSmall.tier)
        assertEquals(1.0, daytimeSmall.factor)
    }

    @Test
    fun mainGateBlocksTheEarlyTier() {
        val decision = fastRiseSizeDecision(rise(delta = 0.30 * 18.0, shortDelta = 0.12 * 18.0, longDelta = 1.0))
        assertEquals(FastRiseSizeTier.None, decision.tier)
        assertEquals(1.0, decision.factor)
    }

    @Test
    fun earlyTierRunsWhenTheMainGateIsClosed() {
        val cut = fastRiseSizeDecision(
            rise(bg = 99.0, delta = 0.30 * 18.0, shortDelta = 0.12 * 18.0, longDelta = 1.0, hour = 2, microBolus = 0.1)
        )
        val keep = fastRiseSizeDecision(
            rise(bg = 99.0, delta = 0.30 * 18.0, shortDelta = 0.12 * 18.0, longDelta = 1.0, hour = 10, microBolus = 0.1)
        )
        assertEquals(FastRiseSizeTier.Size0700, cut.tier)
        assertEquals(0.7, cut.factor)
        assertEquals(FastRiseSizeTier.Uncapped0608, keep.tier)
        assertEquals(1.0, keep.factor)
    }

    @Test
    fun higherBgTierUsesItsOwnBand() {
        val decision = fastRiseSizeDecision(
            rise(
                bg = 12.2 * 18.0,
                delta = 0.95 * 18.0,
                shortDelta = 0.8 * 18.0,
                longDelta = 0.8 * 18.0,
                rawDelta5 = 0.9 * 18.0,
                aapsDelta1 = 0.9 * 18.0,
            )
        )
        assertEquals(FastRiseSizeTier.Size0759, decision.tier)
        assertEquals(0.75, decision.factor)
    }

    @Test
    fun mainGateWinsOverTheHigherBgTier() {
        val decision = fastRiseSizeDecision(
            rise(bg = 11.8 * 18.0, delta = 18.0, shortDelta = 18.0, longDelta = 18.0, rawDelta5 = 18.0, aapsDelta1 = 18.0)
        )
        assertEquals(FastRiseSizeTier.Size0201, decision.tier)
    }

    @Test
    fun nightHourOpensTheMainGateWithLowIob() {
        val night = fastRiseSizeDecision(rise(delta = 18.0, shortDelta = 18.0, longDelta = 18.0, iob = 0.0, hour = 23))
        val day = fastRiseSizeDecision(rise(delta = 18.0, shortDelta = 18.0, longDelta = 18.0, iob = 0.0, hour = 12))
        assertEquals(FastRiseSizeTier.Size0201, night.tier)
        assertEquals(FastRiseSizeTier.None, day.tier)
    }

    @Test
    fun slopeRatioDividesTheSmoothedDeltas() {
        val decision = fastRiseSizeDecision(
            rise(bg = 150.0, delta = 18.0, shortDelta = 18.0, longDelta = 18.0, slopeRatio = 2.0)
        )
        assertEquals(FastRiseSizeTier.Size0850, decision.tier)
    }

    @Test
    fun activeLoRebWindowTurnsTheTierOff() {
        val window = loRebWindow(
            bg = 144.0,
            delta = 0.42 * 18.0,
            shortDelta = 0.31 * 18.0,
            systemTimeMs = 1_700_000_000_000L,
            lastAlarmHypoAtMs = 1_700_000_000_000L - 28 * 60_000L,
            cob = 0.0,
            uci = 0.0,
            csf = 1.0,
            recentLowReboundGuardEnabled = true,
        )
        val decision = fastRiseSizeDecision(
            rise(
                bg = 144.0,
                delta = 0.42 * 18.0,
                shortDelta = 0.31 * 18.0,
                longDelta = 1.0,
                rawDelta5 = 0.42 * 18.0,
                aapsDelta1 = 0.42 * 18.0,
                cob = 0.0,
                loRebWindowActive = window.active,
            )
        )
        assertEquals(true, window.active)
        assertEquals(FastRiseSizeTier.OffLoReb, decision.tier)
        assertEquals(1.0, decision.factor)
    }

    @Test
    fun inactiveLoRebWindowLeavesTheMildCut() {
        val window = loRebWindow(
            bg = 144.0,
            delta = 0.42 * 18.0,
            shortDelta = 0.31 * 18.0,
            systemTimeMs = 1_700_000_000_000L,
            lastAlarmHypoAtMs = 0L,
            cob = 0.0,
            uci = 0.0,
            csf = 1.0,
            recentLowReboundGuardEnabled = true,
        )
        val decision = fastRiseSizeDecision(
            rise(
                bg = 144.0,
                delta = 0.42 * 18.0,
                shortDelta = 0.31 * 18.0,
                longDelta = 1.0,
                rawDelta5 = 0.42 * 18.0,
                aapsDelta1 = 0.42 * 18.0,
                cob = 0.0,
                loRebWindowActive = window.active,
            )
        )
        assertEquals(false, window.active)
        assertEquals(FastRiseSizeTier.Size0750, decision.tier)
        assertEquals(0.75, decision.factor)
    }

    @Test
    fun adjustedSmbUsesTheStrongCut() {
        val result = fastRiseAdjustedMicroBolus(
            microBolus = 1.0,
            roundSmbTo = 20.0,
            loReb = inactiveWindow(),
            input = rise(delta = 18.0, shortDelta = 18.0, longDelta = 18.0),
        )
        assertEquals(0.2, result.microBolus)
        assertEquals("FastRise x0.2 SMB 1.0 -> 0.2. ", result.reason)
    }

    @Test
    fun adjustedSmbStaysWholeWhenTheLoRebWindowIsActive() {
        val window = loRebWindow(
            bg = 144.0,
            delta = 0.42 * 18.0,
            shortDelta = 0.31 * 18.0,
            systemTimeMs = 1_700_000_000_000L,
            lastAlarmHypoAtMs = 1_700_000_000_000L - 28 * 60_000L,
            cob = 0.0,
            uci = 0.0,
            csf = 1.0,
            recentLowReboundGuardEnabled = true,
        )
        val result = fastRiseAdjustedMicroBolus(
            microBolus = 1.0,
            roundSmbTo = 20.0,
            loReb = window,
            input = rise(
                bg = 144.0,
                delta = 0.42 * 18.0,
                shortDelta = 0.31 * 18.0,
                longDelta = 1.0,
                rawDelta5 = 0.42 * 18.0,
                aapsDelta1 = 0.42 * 18.0,
            ),
        )
        assertEquals(1.0, result.microBolus)
        assertEquals("FastRise tiers OFF (LoReb 30min). ", result.reason)
    }

    @Test
    fun adjustedSmbExplainsANonLibreSensor() {
        val result = fastRiseAdjustedMicroBolus(
            microBolus = 1.0,
            roundSmbTo = 20.0,
            loReb = inactiveWindow(),
            input = rise(delta = 18.0, shortDelta = 18.0, longDelta = 18.0).copy(libreActive = false),
        )
        assertEquals(1.0, result.microBolus)
        assertEquals("FastRise tiers OFF (not Libre). ", result.reason)
    }

    @Test
    fun settingLibreAndTempTargetEachLeaveTheSmbUncut() {
        val strong = rise(delta = 18.0, shortDelta = 18.0, longDelta = 18.0)
        assertEquals(FastRiseSizeTier.OffTest, fastRiseSizeDecision(strong.copy(fastRiseSettingOn = false)).tier)
        assertEquals(FastRiseSizeTier.OffNotLibre, fastRiseSizeDecision(strong.copy(libreActive = false)).tier)
        assertEquals(FastRiseSizeTier.OffTempTarget, fastRiseSizeDecision(strong.copy(tempTargetSet = true)).tier)
        assertEquals(1.0, fastRiseSizeDecision(strong.copy(fastRiseSettingOn = false)).factor)
    }

    private fun inactiveWindow() = loRebWindow(
        bg = 126.0,
        delta = 18.0,
        shortDelta = 18.0,
        systemTimeMs = 1_700_000_000_000L,
        lastAlarmHypoAtMs = 0L,
        cob = 0.0,
        uci = 0.0,
        csf = 1.0,
        recentLowReboundGuardEnabled = true,
    )

    private fun rise(
        bg: Double = 126.0,
        delta: Double,
        shortDelta: Double,
        longDelta: Double,
        rawDelta5: Double = 18.0,
        aapsDelta1: Double = 18.0,
        cob: Double = 0.0,
        iob: Double = 2.0,
        maxIob: Double = 10.0,
        microBolus: Double = 1.0,
        threshold: Double = 0.3,
        hour: Int = 12,
        slopeRatio: Double = 1.0,
        loRebWindowActive: Boolean = false,
    ) = FastRiseSizeInput(
        bg = bg,
        delta = delta,
        shortDelta = shortDelta,
        longDelta = longDelta,
        rawDelta5 = rawDelta5,
        aapsDelta1 = aapsDelta1,
        cob = cob,
        iob = iob,
        maxIob = maxIob,
        microBolus = microBolus,
        threshold = threshold,
        hour = hour,
        slopeRatio = slopeRatio,
        loRebWindowActive = loRebWindowActive,
    )
}
