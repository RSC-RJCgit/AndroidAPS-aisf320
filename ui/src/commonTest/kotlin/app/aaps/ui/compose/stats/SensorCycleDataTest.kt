package app.aaps.ui.compose.stats

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SensorCycleDataTest {

    private val hour = 60L * 60L * 1000L
    private val day = 24 * hour

    // Three sensors of 10 days each, the newest still running at "now"
    private val start1 = 100 * day
    private val start2 = start1 + 10 * day
    private val start3 = start2 + 10 * day
    private val now = start3 + 4 * day + 30 * 60 * 1000L

    @Test
    fun readingsAreAlignedToTheirSensorStart() {
        val readings = listOf(
            glucose(start1 + 30 * 60 * 1000L, 100.0), // bin 0 of the oldest sensor
            glucose(start2 + 4 * hour, 200.0),        // bin 1 of the middle sensor (3 h bins)
            glucose(start3 + 2 * day, 150.0)          // bin 16 of the newest sensor
        )
        val data = calculate(readings)!!

        assertEquals(listOf(start3, start2, start1), data.sensors.map { it.startTimestamp })
        assertEquals(150.0, data.sensors[0].binMeansMgdl[16])
        assertEquals(200.0, data.sensors[1].binMeansMgdl[1])
        assertEquals(100.0, data.sensors[2].binMeansMgdl[0])
        assertNull(data.sensors[2].binMeansMgdl[1])
    }

    @Test
    fun binsHoldTheMeanOfTheirReadings() {
        val data = calculate(listOf(glucose(start3 + hour, 100.0), glucose(start3 + 2 * hour, 140.0)))!!

        assertEquals(120.0, data.sensors[0].binMeansMgdl[0])
    }

    @Test
    fun aSensorEndsWhereTheNextOneStarts() {
        // Window of 15 days, but the oldest sensor was replaced after 10: its day 11 belongs to the middle one
        val data = calculate(listOf(glucose(start2 + day, 180.0)), windowHours = 15 * 24)!!

        assertEquals(180.0, data.sensors[1].binMeansMgdl[8])
        // Day 11 of the oldest sensor would be bin 88
        assertNull(data.sensors[2].binMeansMgdl[88])
        assertEquals(0, data.sensors[2].binMeansMgdl.count { it != null })
    }

    @Test
    fun theWindowCapsHowFarASensorIsFollowed() {
        // Only one sensor, still running for 12 days, with a 10 day window: day 11 is not shown
        val data = calculateSensorCycleData(
            readings = listOf(glucose(start1 + 11 * day, 120.0), glucose(start1 + 9 * day, 110.0)),
            starts = listOf(start1),
            now = start1 + 12 * day,
            windowHours = 10 * 24,
            offset = 0,
            lowMgdl = 70.0,
            highMgdl = 180.0
        )!!

        assertEquals(80, data.averageMgdl.size)
        assertEquals(110.0, data.sensors[0].binMeansMgdl[72])
        assertEquals(1, data.sensors[0].binMeansMgdl.count { it != null })
    }

    @Test
    fun theAverageSkipsSensorsWithoutReadingsInABin() {
        val readings = listOf(
            glucose(start1 + hour, 100.0),
            glucose(start2 + hour, 200.0),
            // The newest sensor has no bin 0 reading, and is the only one with bin 2
            glucose(start3 + 7 * hour, 90.0)
        )
        val data = calculate(readings)!!

        assertEquals(150.0, data.averageMgdl[0])
        assertNull(data.averageMgdl[1])
        assertEquals(90.0, data.averageMgdl[2])
    }

    @Test
    fun theOffsetSkipsTheNewestSensors() {
        val data = calculate(listOf(glucose(start1 + hour, 100.0)), offset = 1)!!

        assertEquals(listOf(start2, start1), data.sensors.map { it.startTimestamp })
        assertEquals(3, data.sensorsAvailable)
        assertNull(calculate(emptyList(), offset = 3))
    }

    @Test
    fun duplicateStartsAreMerged() {
        assertEquals(listOf(start1, start2), distinctSensorStarts(listOf(start2, start1, start1 + 60_000L, start2)))
        val data = calculateSensorCycleData(emptyList(), listOf(start1, start1 + 60_000L, start2), now, 240, 0, 70.0, 180.0)!!
        assertEquals(2, data.sensors.size)
    }

    @Test
    fun invalidAndErrorReadingsAreIgnored() {
        val readings = listOf(
            glucose(start3 + hour, 100.0),
            glucose(start3 + hour, 300.0).also { it.isValid = false },
            glucose(start3 + hour, 38.0)
        )
        assertEquals(100.0, calculate(readings)!!.sensors[0].binMeansMgdl[0])
    }

    @Test
    fun changeoverPoolsTheChangesAndComparesBeforeWithAfter() {
        val readings = listOf(
            // Around the newest change
            glucose(start3 - 2 * hour, 200.0),
            glucose(start3 + 30 * 60 * 1000L, 100.0),
            // Around the middle change, in the same hours
            glucose(start2 - 2 * hour, 100.0),
            glucose(start2 + 30 * 60 * 1000L, 120.0)
        )
        val changeover = assertNotNull(calculate(readings)!!.changeover)

        // The oldest change has no sensor before it, so only two changes
        assertEquals(listOf(start3, start2), changeover.changeTimestamps)
        assertEquals(listOf(-2, 0), changeover.percentiles.map { it.hourFromChange })
        assertEquals(150.0, changeover.percentiles[0].medianMgdl)
        assertEquals(110.0, changeover.percentiles[1].medianMgdl)
        assertEquals(150.0, changeover.averageBeforeMgdl)
        assertEquals(110.0, changeover.averageAfterMgdl)
        assertEquals(50.0, changeover.tirBeforePercent)
        assertEquals(100.0, changeover.tirAfterPercent)
    }

    @Test
    fun changeoverPercentilesInterpolateLikeTheAgp() {
        val readings = listOf(100.0, 110.0, 120.0, 130.0, 140.0).mapIndexed { index, value -> glucose(start3 + index * 60_000L, value) }
        val point = calculate(readings)!!.changeover!!.percentiles.single()

        assertEquals(104.0, point.p10Mgdl, 1e-9)
        assertEquals(110.0, point.p25Mgdl)
        assertEquals(120.0, point.medianMgdl)
        assertEquals(130.0, point.p75Mgdl)
        assertEquals(136.0, point.p90Mgdl, 1e-9)
    }

    @Test
    fun changeoverStopsAtTheChangeBefore() {
        // A sensor that lasted only 2 days: 5 days back from the change after it would reach past its own start
        val shortStart = start2 - 2 * day
        val readings = listOf(glucose(start2 - day, 100.0), glucose(shortStart - hour, 300.0))
        val changeover = calculateSensorCycleData(readings, listOf(start1, shortStart, start2), start2 + day, 240, 0, 70.0, 180.0)!!.changeover!!

        assertEquals(listOf(start2, shortStart), changeover.changeTimestamps)
        // The 300 belongs to the sensor before the short one; it counts for the short one's own change only
        assertEquals(100.0, changeover.percentiles.first { it.hourFromChange == -24 }.medianMgdl)
        assertEquals(listOf(300.0), changeover.percentiles.filter { it.hourFromChange == -1 }.map { it.medianMgdl })
    }

    @Test
    fun noChangeoverWithASingleSensor() {
        val data = calculateSensorCycleData(listOf(glucose(start1 + hour, 100.0)), listOf(start1), now, 240, 0, 70.0, 180.0)!!
        assertNull(data.changeover)
    }

    @Test
    fun readingSpanCoversTheShownSensorsAndTheDaysBefore() {
        val span = sensorCycleReadingSpan(listOf(start1, start2, start3), now, windowHours = 240, offset = 1)!!

        assertEquals(start1 - 5 * day, span.first)
        assertEquals(start2 + 10 * day, span.last)
        assertNull(sensorCycleReadingSpan(listOf(start1), now, 240, offset = 1))
    }

    @Test
    fun windowFollowsTheSensorType() {
        assertEquals(240, sensorWindowHours(SourceSensor.DEXCOM_G6))
        assertEquals(252, sensorWindowHours(SourceSensor.DEXCOM_G7))
        assertEquals(360, sensorWindowHours(SourceSensor.LIBRE_3))
        assertEquals(168, sensorWindowHours(SourceSensor.MM_SIMPLERA))
        assertEquals(360, sensorWindowHours(SourceSensor.EVERSENSE))
        assertEquals(360, sensorWindowHours(null))
    }

    private fun calculate(readings: List<GV>, offset: Int = 0, windowHours: Int = 240) =
        calculateSensorCycleData(
            readings = readings,
            starts = listOf(start3, start1, start2),
            now = now,
            windowHours = windowHours,
            offset = offset,
            lowMgdl = 70.0,
            highMgdl = 180.0
        )

    private fun glucose(timestamp: Long, value: Double) = GV(
        timestamp = timestamp,
        raw = null,
        value = value,
        trendArrow = TrendArrow.NONE,
        noise = null,
        sourceSensor = SourceSensor.UNKNOWN
    )
}
