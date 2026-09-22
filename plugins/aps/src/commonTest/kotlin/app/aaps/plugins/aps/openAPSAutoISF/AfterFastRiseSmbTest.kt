package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals

class AfterFastRiseSmbTest {

    @Test
    fun missingFifteenMinuteRawStillCutsTheEarlyMorningRise() {
        val result = earlyMorningThenTwilight(
            morning(hour = 7, iob = 1.0, immediateRawDelta5 = 0.5 * 18.0, rawDelta15 = 9999.0)
        )
        assertEquals(0.8, result.microBolus, 0.001)
        assertEquals("Early morning raw rise x0.8 SMB 0.8. ", result.reason)
    }

    @Test
    fun aSlowFifteenMinuteRawBlocksTheEarlyMorningCut() {
        val result = earlyMorningThenTwilight(
            morning(
                hour = 7,
                iob = 0.2,
                immediateRawDelta5 = 0.5 * 18.0,
                rawDelta15 = 0.5 * 18.0,
                microBolus = 0.1,
            )
        )
        assertEquals(0.1, result.microBolus, 0.001)
        assertEquals("Twilight hours. ", result.reason)
    }

    @Test
    fun hourNineDoesNotUseTheTwilightCap() {
        val result = earlyMorningThenTwilight(morning(hour = 9, steps60 = 0, immediateRawDelta5 = 0.0))
        assertEquals(1.0, result.microBolus, 0.001)
        assertEquals("", result.reason)
    }

    @Test
    fun twilightCapsThenAppliesTheIobCeiling() {
        val result = earlyMorningThenTwilight(morning(hour = 7, iob = 0.8, immediateRawDelta5 = 0.0, steps60 = 0))
        assertEquals(0.1, result.microBolus, 0.001)
        assertEquals("Twilight SMB cap 0.2. Twilight IOB ceiling 0.1. Twilight hours. ", result.reason)
    }

    @Test
    fun aSizeTierBlocksTheEarlyMorningCut() {
        val result = afterFastRiseSmb(
            quiet(
                tier = FastRiseSizeTier.Size0750,
                earlyMorning = morning(hour = 7, iob = 1.0, immediateRawDelta5 = 0.5 * 18.0, rawDelta15 = 9999.0),
            )
        )
        assertEquals(1.0, result.microBolus, 0.001)
        assertEquals("", result.reason)
    }

    @Test
    fun highStepsCutWhenTheSmbIsAboveTheThreshold() {
        val result = highStepsSmbCut(microBolus = 1.0, threshold = 0.3, steps30 = 0, steps60 = 801, steps180 = 0)
        assertEquals(0.7, result.microBolus, 0.001)
        assertEquals("High steps x0.7 SMB 0.7. ", result.reason)
    }

    @Test
    fun lowReboundHalvesAnSmbAboveAQuarterUnit() {
        val result = loRebSmbTrim(microBolus = 0.40, windowActive = true, guardEnabled = true)
        assertEquals(0.2, result.microBolus, 0.001)
        assertEquals("Low rebound SMB 0.4 -> 0.2. ", result.reason)
    }

    @Test
    fun lowReboundLeavesATenthAlone() {
        val result = loRebSmbTrim(microBolus = 0.10, windowActive = true, guardEnabled = true)
        assertEquals(0.10, result.microBolus, 0.001)
        assertEquals("", result.reason)
    }

    @Test
    fun lateTaperUsesHighIobWithNoCarbs() {
        val result = lateFastRiseTaper(
            microBolus = 1.0,
            fastRiseNow = true,
            iob = 1.8,
            maxIob = 10.0,
            cob = 0.0,
            smbSum30 = 0.0,
        )
        assertEquals(0.5, result.microBolus, 0.001)
        assertEquals("Late FastRise high IOB x0.5 SMB 0.5. ", result.reason)
    }

    @Test
    fun lateTaperUsesTheThirtyMinuteSumWhenIobIsLow() {
        val result = lateFastRiseTaper(
            microBolus = 1.0,
            fastRiseNow = true,
            iob = 0.2,
            maxIob = 10.0,
            cob = 0.0,
            smbSum30 = 1.5,
        )
        assertEquals(0.75, result.microBolus, 0.001)
        assertEquals("Late FastRise SMB 30 min 1.5U x0.75 SMB 0.75. ", result.reason)
    }

    @Test
    fun thirtyMinuteCapTrimsEvenWhenTheRiseHasStopped() {
        val result = smbCap30Min(microBolus = 1.0, smbSum30 = 1.8)
        assertEquals(0.3, result.microBolus, 0.001)
        assertEquals("30 min SMB cap 1.0 -> 0.3. ", result.reason)
    }

    @Test
    fun deepNightTenMinuteCapIsPointSixUnderEightMmol() {
        val result = smbCap10Min(microBolus = 0.4, smbSum10 = 0.3, minuteOfDay = 30, bg = 140.0)
        assertEquals(0.3, result.microBolus, 0.001)
        assertEquals("10 min SMB cap 0.4 -> 0.3. ", result.reason)
    }

    @Test
    fun fourAmUsesTheDaytimeTenMinuteCap() {
        val result = smbCap10Min(microBolus = 0.4, smbSum10 = 0.3, minuteOfDay = 240, bg = 140.0)
        assertEquals(0.4, result.microBolus, 0.001)
        assertEquals("", result.reason)
    }

    @Test
    fun eveningShoulderCapIsOneUnitUnderEightMmol() {
        val result = smbCap10Min(microBolus = 0.4, smbSum10 = 0.8, minuteOfDay = 1320, bg = 140.0)
        assertEquals(0.2, result.microBolus, 0.001)
        assertEquals("10 min SMB cap 0.4 -> 0.2. ", result.reason)
    }

    @Test
    fun theChainZerosANegativeTwilightCeiling() {
        val result = afterFastRiseSmb(
            quiet(
                tier = FastRiseSizeTier.None,
                microBolus = 1.0,
                iob = 2.0,
                earlyMorning = morning(hour = 7, iob = 2.0, immediateRawDelta5 = 0.0, steps60 = 0),
            )
        )
        assertEquals(0.0, result.microBolus, 0.001)
    }

    private fun morning(
        hour: Int = 12,
        iob: Double = 0.2,
        maxIob: Double = 10.0,
        cob: Double = 0.0,
        immediateRawDelta5: Double = 0.0,
        rawDelta15: Double = 0.0,
        bg: Double = 8.0 * 18.0,
        delta: Double = 0.0,
        shortDelta: Double = 0.0,
        steps60: Int = 0,
        microBolus: Double = 1.0,
    ) = EarlyMorningTwilightInput(
        hour = hour,
        iob = iob,
        maxIob = maxIob,
        cob = cob,
        immediateRawDelta5 = immediateRawDelta5,
        rawDelta15 = rawDelta15,
        bg = bg,
        delta = delta,
        shortDelta = shortDelta,
        steps60 = steps60,
        microBolus = microBolus,
    )

    private fun quiet(
        tier: FastRiseSizeTier = FastRiseSizeTier.None,
        microBolus: Double = 1.0,
        iob: Double = 0.2,
        earlyMorning: EarlyMorningTwilightInput = morning(),
    ) = AfterFastRiseInput(
        tier = tier,
        libreActive = true,
        tempTargetSet = false,
        microBolus = microBolus,
        roundSmbTo = 20.0,
        earlyMorning = earlyMorning,
        threshold = 0.3,
        steps30 = 0,
        steps60 = 0,
        steps180 = 0,
        loRebActive = false,
        loRebGuardEnabled = true,
        fastRiseNow = false,
        iob = iob,
        maxIob = 10.0,
        cob = 0.0,
        smbSum30 = 0.0,
        smbSum10 = 0.0,
        minuteOfDay = 12 * 60,
        bg = 8.0 * 18.0,
    )
}
