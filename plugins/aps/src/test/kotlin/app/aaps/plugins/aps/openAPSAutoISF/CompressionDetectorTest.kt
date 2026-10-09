package app.aaps.plugins.aps.openAPSAutoISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class CompressionDetectorTest {

    private val minute = 60_000L

    /** One reading per minute from minute 0, values in mg/dL. */
    private fun series(values: List<Double>): List<Pair<Long, Double>> = values.mapIndexed { i, v -> i * minute to v }

    /** Flat for 45 minutes, then a drop of [perMinute] mg/dL per minute for [minutes] minutes. */
    private fun drop(flat: Double = 150.0, minutes: Int = 5, perMinute: Double = 4.0): List<Double> =
        List(45) { flat } + List(minutes) { flat - perMinute * (it + 1) }

    @Test fun aSuddenDropOutOfAFlatSpellIsSuspected() {
        val s = CompressionDetector.suspect(series(drop()))
        assertThat(s).isNotNull()
        assertThat(s!!.fromMgdl).isEqualTo(150.0)
        assertThat(s.toMgdl).isEqualTo(130.0)
        assertThat(s.fallMinutes).isEqualTo(5L)
        assertThat(s.steepestMgdlPerMin).isWithin(0.001).of(-4.0)
    }

    @Test fun the2303SlowSlideIsNotSuspected() {
        // 8.2 to 5.9 mmol over 40 minutes, about -0.15 mmol per 5 minutes (a slide, 0.1-0.35 U on board).
        val slide = List(20) { 148.0 } + List(40) { 148.0 - (148.0 - 106.0) * (it + 1) / 40.0 }
        assertThat(CompressionDetector.suspect(series(slide))).isNull()
    }

    @Test fun aLongFallIsNotATriggerHoweverDeep() {
        assertThat(CompressionDetector.suspect(series(drop(minutes = 30, perMinute = 2.0).dropLast(25)))).isNull()
        assertThat(CompressionDetector.suspect(series(drop(minutes = 30, perMinute = 2.0)))).isNull()
    }

    @Test fun aDropUnder1MmolIn5MinutesIsNotSuspected() {
        assertThat(CompressionDetector.suspect(series(drop(perMinute = 3.4)))).isNull()
    }

    @Test fun aDropTheInsulinOnBoardExplainsIsNotSuspected() {
        // 20 mg/dL in 5 minutes against 17 explained: under 1.25 times, so the insulin accounts for it.
        assertThat(CompressionDetector.suspect(series(drop()), explainedFall5Mgdl = 17.0)).isNull()
        // Against 10 explained it is 2 times, above the 1.25 bar.
        assertThat(CompressionDetector.suspect(series(drop()), explainedFall5Mgdl = 10.0)).isNotNull()
    }

    @Test fun aDropAtAnyGlucoseLevelCounts() {
        assertThat(CompressionDetector.suspect(series(drop(flat = 220.0)))).isNotNull()
        assertThat(CompressionDetector.suspect(series(drop(flat = 110.0)))).isNotNull()
    }


    /** 15 flat, then [netFall] mg/dL of slow decline over 30 minutes, then [perMinute] per minute for 5 minutes. */
    private fun slideThenDrop(netFall: Double, perMinute: Double, start: Double = 160.0): List<Double> =
        List(15) { start } + List(30) { start - netFall * (it + 1) / 30.0 } + List(5) { start - netFall - perMinute * (it + 1) }

    @Test fun aSteepDropOnTopOfASlowDeclineIsSuspectedByLaneB() {
        // The 8 Oct 20:40 shape: 9.3 to 7.1 mmol over the half hour before, then 1.5 mmol in 5 minutes.
        val s = CompressionDetector.suspect(series(slideThenDrop(netFall = 34.0, perMinute = 6.0)))
        assertThat(s).isNotNull()
        assertThat(s!!.steepenedLane).isTrue()
    }

    @Test fun laneBNeeds14Mmol() {
        assertThat(CompressionDetector.suspect(series(slideThenDrop(netFall = 34.0, perMinute = 4.0)))).isNull()
    }

    @Test fun laneBNeedsTheHalfHourBeforeToHaveFallenNoMoreThan25Mmol() {
        assertThat(CompressionDetector.suspect(series(slideThenDrop(netFall = 60.0, perMinute = 6.0)))).isNull()
    }

    @Test fun laneBNeedsTwiceTheSteepestFallOfTheHalfHourBefore() {
        // A 20 mg/dL step down in 5 minutes, then flat, then a 30 mg/dL drop: 30 is under twice 20.
        val stepped = List(10) { 160.0 } + List(5) { 160.0 - 4.0 * (it + 1) } + List(25) { 140.0 } + List(5) { 140.0 - 6.0 * (it + 1) }
        assertThat(CompressionDetector.suspect(series(stepped))).isNull()
    }

    @Test fun aFlatSpellIsLaneA() {
        assertThat(CompressionDetector.suspect(series(drop()))!!.steepenedLane).isFalse()
    }

    @Test fun aRepeatingSwingIsNotSuspected() {
        // 8 Oct 12:00-12:25 shape: about 16 minutes between troughs, 1.5 mmol each way.
        val swing = List(90) { 90.0 + 14.0 * sin(2 * PI * it / 16.0) }
        assertThat(CompressionDetector.suspect(series(swing))).isNull()
    }

    @Test fun aNoisy30MinutesBeforeTheDropIsNotSuspected() {
        val noisy = List(15) { 150.0 } + List(10) { 150.0 + 2.5 * (it + 1) } + List(10) { 175.0 - 2.5 * (it + 1) } +
            List(10) { 150.0 } + List(5) { 150.0 - 4.0 * (it + 1) }
        assertThat(CompressionDetector.suspect(series(noisy))).isNull()
    }

    @Test fun aDropThatFinishedEarlierAndHasSettledIsNotSuspected() {
        assertThat(CompressionDetector.suspect(series(drop() + List(10) { 130.0 }))).isNull()
    }

    @Test fun tooLittleDataIsNotSuspected() {
        assertThat(CompressionDetector.suspect(series(listOf(150.0, 150.0, 100.0)))).isNull()
    }


    // The pending check after a trigger: a flat 45 minutes, the trigger at minute 45 ending at 120 mg/dL (6.7 mmol), then [values].
    private val triggerAt = 45 * minute
    private fun pending(values: List<Double>): List<Pair<Long, Double>> =
        series(List(45) { 150.0 }) + values.mapIndexed { i, v -> triggerAt + i * minute to v }
    private fun confirm(values: List<Double>, minutesAfter: Int) =
        CompressionDetector.confirm(pending(values.take(minutesAfter + 1)), triggerAt, 120.0, triggerAt + minutesAfter * minute)

    @Test fun stillDownAfter15MinutesAndUnder6IsConfirmed() {
        // 21:24 shape: 6.7 down to 4.4 within 14 minutes, still low at 15.
        val values = List(31) { maxOf(80.0, 120.0 - 3.0 * it) }
        assertThat(confirm(values, 15)).isEqualTo(CompressionDetector.Pending.CONFIRMED)
    }

    @Test fun before15MinutesItOnlyWaits() {
        val values = List(31) { maxOf(80.0, 120.0 - 3.0 * it) }
        assertThat(confirm(values, 10)).isEqualTo(CompressionDetector.Pending.WAIT)
    }

    @Test fun climbingBackBefore15MinutesDropsIt() {
        val values = List(8) { 115.0 } + List(23) { 135.0 }
        assertThat(confirm(values, 15)).isEqualTo(CompressionDetector.Pending.CANCELLED)
    }

    @Test fun stayingAbove6MmolWaitsTo30MinutesThenLapses() {
        // The 5 Oct 12:01 shape: a step that holds, never under 6.0 (108.1).
        val values = List(40) { 118.0 }
        assertThat(confirm(values, 20)).isEqualTo(CompressionDetector.Pending.WAIT)
        assertThat(confirm(values, 31)).isEqualTo(CompressionDetector.Pending.CANCELLED)
    }

    @Test fun reaching6MmolLateInTheWindowStillConfirms() {
        val values = List(18) { 118.0 } + List(13) { 104.0 }
        assertThat(confirm(values, 25)).isEqualTo(CompressionDetector.Pending.CONFIRMED)
    }

    private val lowThenRebound = series(List(30) { 100.0 })

    @Test fun aSharpRiseFromNearTheLowConfirms() {
        val readings = lowThenRebound + listOf(
            30 * minute to 102.0, 31 * minute to 101.0, 32 * minute to 110.0, 33 * minute to 125.0, 34 * minute to 135.0
        )
        assertThat(CompressionDetector.rebounded(readings, 20 * minute)).isTrue()
    }

    @Test fun aSlowRecoveryDoesNotConfirm() {
        val readings = lowThenRebound + (0 until 12).map { (30 + it) * minute to 100.0 + it * 1.5 }
        assertThat(CompressionDetector.rebounded(readings, 20 * minute)).isFalse()
    }

    @Test fun aRiseThatStartsWellAboveTheLowDoesNotConfirm() {
        // The low (90) is more than 10 minutes before the rise, which starts at 110, 20 above it.
        val readings = listOf(20 * minute to 90.0) + (21..40).map { it * minute to 110.0 } +
            listOf(41 * minute to 125.0, 42 * minute to 136.0)
        assertThat(CompressionDetector.rebounded(readings, 20 * minute)).isFalse()
    }
}
