package app.aaps.core.objects.autoisf

import app.aaps.core.data.model.AIV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The screen-only columns added 2026-11-20 so the table matches the 3426 app's (FR, MildBst, SMBi5, dAcc, dAccUkf, Req, COBt, carbAbs, HP, HP2). */
class AutoIsfHistoryV3ColumnsTest {

    private val minute = 60_000L

    private fun row(time: Long, glucose: Double = 100.0, ukf: Double = 0.0, iob: Double = 0.0, short: Double = 0.0, cob: Double = 0.0, uam: Double = 0.0) = AIV(
        timestamp = time, acceIsf = 1.0, bgIsf = 1.0, ppIsf = 1.0, duraIsf = 1.0, finalIsf = 1.0,
        glucose = glucose, delta = 0.0, shortAvgDelta = short, longAvgDelta = 0.0, bgAcceleration = 0.0,
        iob = iob, smbDelivered = 0.0, ukfRawBgl = ukf, cob = cob, uamCarbImpact = uam
    )

    @Test fun fastRiseTakesTheFullRatioOrTheFactorAndIgnoresSkippedCaps() {
        assertEquals("750", fastRiseText("fast rise 0.75 ; microBolus = microBolus * 0.75 ;"))
        assertEquals("9", fastRiseText("microBolus = microBolus * 0.9 ;"))
        assertEquals("--", fastRiseText("fast rise 0.75 ; fast-rise caps skipped"))
        assertEquals("--", fastRiseText("nothing here"))
    }

    @Test fun reasonValuesAreRead() {
        assertEquals(0.35, mildBstValue("x; MildBst: 0.35 ; y"))
        assertEquals(-1.2, deltaAcceValue("delta_accl: -1.2 ;"))
        assertNull(mildBstValue("none"))
    }

    @Test fun smbIntervalIsTheMeanGapOfTheLastFiveMinutes() {
        val now = 100 * minute
        assertEquals(60, smbInterval5Sec(now, listOf(now - 2 * minute, now - minute, now)))
        assertNull(smbInterval5Sec(now, listOf(now - 10 * minute, now)))
    }

    @Test fun rawDeltaUsesTheReadingAboutFiveMinutesBack() {
        val now = 100 * minute
        val raw = listOf(now to 70.0, now - 2 * minute to 90.0, now - 5 * minute to 110.0)
        assertEquals(-40.0, rawDelta5Mgdl(now, raw))
        assertNull(rawDelta5Mgdl(now, listOf(now to 70.0)))
        assertNull(rawDelta5Mgdl(now, listOf(now to null, now - 5 * minute to 110.0)))
    }

    @Test fun ukfDeltasAndTheirDifference() {
        // Newest first, one row a minute: UKF falls 2 mg/dL a minute for the last 5 minutes, flat before
        val rows = (0..15).map { i -> row(time = (100 - i) * minute, ukf = if (i <= 5) 120.0 - 2 * (5 - i) else 120.0) }
        assertEquals(-10.0, ukfDeltaMgdl(rows, 0, 5)!!, 1e-9)
        val fifteen = ukfDeltaMgdl(rows, 0, 15)!!
        assertEquals((-10.0 - fifteen) / kotlin.math.abs(fifteen) * 100.0, deltaAcceUkfPercent(rows, 0)!!, 1e-9)
    }

    @Test fun hypoPredictionIsTheGraphFormula() {
        val r = row(time = 0L, glucose = 6.3 * 18.0182, iob = 1.28, short = -0.3 * 18.0182)
        // (6.3 - 1.28) + 0.25 x -0.3 + 0.25 x -0.3 = 4.87 mmol
        assertEquals(4.87, hypoPredictionMgdl(r, -0.3 * 18.0182) / 18.0182, 1e-9)
    }

    @Test fun cobTAddsTheUnannouncedCarbsStillToCome() {
        val rows = listOf(row(time = 10 * minute, uam = 1.0), row(time = 5 * minute, uam = 1.0))
        val cobT = calculatedCobT(rows) { 5.0 to 0.5 }
        // Each row: 5 g COB; excess 0.5 g/5 min; the newer row adds 0.1, the older 0.1 + 0.5
        assertEquals(5.1, cobT[10 * minute]!!, 1e-9)
        assertEquals(5.6, cobT[5 * minute]!!, 1e-9)
    }

    @Test fun theNearestLoopResultMustBeWithin15Minutes() {
        val results = listOf(LoopResultText(0L, "a", 1.0), LoopResultText(20 * minute, "b", null))
        assertEquals("a", nearestLoopResult(results, 5 * minute)?.reason)
        assertNull(nearestLoopResult(listOf(LoopResultText(0L, "a", 1.0)), 16 * minute))
    }
}
