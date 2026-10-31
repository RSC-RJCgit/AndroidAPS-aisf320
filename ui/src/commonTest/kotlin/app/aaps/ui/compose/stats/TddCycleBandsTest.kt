package app.aaps.ui.compose.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Cycle tab's ambulatory bands and week averages (2026-11-01). */
class TddCycleBandsTest {

    private fun cycle(index: Int, values: List<Double>) = CycleSeries(dayValues = values, cycleIndex = index, startDate = "")

    @Test
    fun bandsArePercentilesOfTheCyclesOnEachDay() {
        // Day 1 across five cycles: 10, 20, 30, 40, 50
        val cycles = (1..5).map { cycle(it, listOf(it * 10.0, 30.0)) }

        val bands = cycleDayBands(cycles)

        assertEquals(2, bands.size)
        assertEquals(CycleDayBand(day = 0, p10 = 14.0, p25 = 20.0, median = 30.0, p75 = 40.0, p90 = 46.0), bands[0])
        // All the same: a band of no width
        assertEquals(30.0, bands[1].p10)
        assertEquals(30.0, bands[1].p90)
    }

    @Test
    fun noCyclesGiveNoBands() {
        assertEquals(emptyList<CycleDayBand>(), cycleDayBands(emptyList()))
    }

    @Test
    fun weekAveragesPoolEveryCycleDayOfThatWeek() {
        val first = List(28) { day -> if (day < 7) 20.0 else 30.0 }
        val second = List(28) { day -> if (day < 7) 40.0 else 30.0 }

        assertEquals(listOf(30.0, 30.0, 30.0, 30.0), cycleWeekAverages(listOf(cycle(0, first), cycle(1, second))))
    }

    /** A cycle cut short has no value for the weeks it does not reach */
    @Test
    fun aWeekWithNoDaysIsNull() {
        val averages = cycleWeekAverages(listOf(cycle(0, List(10) { 25.0 })))

        assertEquals(25.0, averages[0])
        assertEquals(25.0, averages[1])
        assertNull(averages[2])
        assertNull(averages[3])
    }
}
