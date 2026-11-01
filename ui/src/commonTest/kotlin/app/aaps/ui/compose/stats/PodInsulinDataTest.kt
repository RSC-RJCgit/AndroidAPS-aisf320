package app.aaps.ui.compose.stats

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodInsulinDataTest {

    private val hour = 60L * 60L * 1000L
    private val day = 24 * hour

    // Midnights in UTC, so the test does not depend on the time zone it runs in
    private val dayStartOf: (Long) -> Long = { it - it % day }
    private val nextDayStart: (Long) -> Long = { it - it % day + day }

    // Three pods of three days, changed at 18:00; the newest still in use at "now"
    private val pod1 = 100 * day + 18 * hour
    private val pod2 = pod1 + 3 * day
    private val pod3 = pod2 + 3 * day
    private val now = pod3 + day + 6 * hour // 00:00, a day and a quarter into the newest pod

    /** One unit an hour, as if the insulin were spread evenly */
    private val oneUnitAnHour: suspend (Long, Long) -> Double? = { from, to -> (to - from) / hour.toDouble() }

    private suspend fun calculate(
        starts: List<Long> = listOf(pod1, pod2, pod3),
        offset: Int = 0,
        sumUnits: suspend (Long, Long) -> Double? = oneUnitAnHour,
        secondaryUnits: suspend (List<Long>) -> Map<Long, Double> = { emptyMap() },
        podCount: Int = POD_COUNT
    ) = calculatePodInsulinData(starts, now, offset, dayStartOf, nextDayStart, sumUnits, secondaryUnits, podCount)

    @Test
    fun podsAreOldestFirstAndEndAtTheNextChange() = runTest {
        val data = calculate()!!

        assertEquals(listOf(pod1, pod2, pod3), data.pods.map { it.startTimestamp })
        assertEquals(listOf(pod2, pod3, now), data.pods.map { it.endTimestamp })
        assertEquals(listOf(false, false, true), data.pods.map { it.inUse })
        assertEquals(3, data.podsAvailable)
    }

    @Test
    fun aPodIsCutAtMidnights() = runTest {
        val pod = calculate()!!.pods.first()

        // 18:00 to midnight, two whole days, midnight to 18:00
        assertEquals(listOf(6.0, 24.0, 24.0, 18.0), pod.days.map { it.units })
        assertEquals(listOf(100 * day, 101 * day, 102 * day, 103 * day), pod.days.map { it.dayStart })
        assertEquals(72.0, pod.totalUnits)
        assertEquals(72.0, pod.hours)
        assertEquals(24.0, pod.unitsPerDay)
    }

    @Test
    fun thePodInUseIsCountedUpToNow() = runTest {
        val pod = calculate()!!.pods.last()

        assertEquals(listOf(6.0, 24.0), pod.days.map { it.units })
        assertEquals(30.0, pod.totalUnits)
    }

    @Test
    fun duplicateChangesAreMerged() = runTest {
        // The same change entered here and synced back from Nightscout two minutes later
        val data = calculate(starts = listOf(pod1, pod2, pod2 + 2 * 60 * 1000L, pod3))!!

        assertEquals(3, data.pods.size)
        assertEquals(3, data.podsAvailable)
        assertEquals(pod3, data.pods[1].endTimestamp)
    }

    @Test
    fun theOffsetSkipsTheNewestPods() = runTest {
        val data = calculate(offset = 1, podCount = 1)!!

        assertEquals(listOf(pod2), data.pods.map { it.startTimestamp })
        assertFalse(data.pods.single().inUse)
        assertNull(calculate(offset = 3))
    }

    @Test
    fun noChangeMeansNoData() = runTest {
        assertNull(calculate(starts = emptyList()))
        // A change in the future is not a pod yet
        assertNull(calculate(starts = listOf(now + hour)))
    }

    @Test
    fun aWholeDayWithNothingHereIsTakenFromTheSecondSite() = runTest {
        val emptyDay = 101 * day
        var asked = emptyList<Long>()
        val data = calculate(
            sumUnits = { from, to -> if (from == emptyDay) null else oneUnitAnHour(from, to) },
            secondaryUnits = { days -> asked = days; mapOf(emptyDay to 30.0) }
        )!!

        assertEquals(listOf(emptyDay), asked)
        val filled = data.pods.first().days[1]
        assertEquals(30.0, filled.units)
        assertTrue(filled.fromSecondary)
        assertEquals(1, data.secondaryDays)
    }

    @Test
    fun aPartDayIsNeverTakenFromTheSecondSite() = runTest {
        var asked = emptyList<Long>()
        val data = calculate(
            sumUnits = { from, to -> if (from == pod1) null else oneUnitAnHour(from, to) },
            secondaryUnits = { days -> asked = days; emptyMap() }
        )!!

        assertTrue(asked.isEmpty())
        assertNull(data.pods.first().days.first().units)
        assertEquals(66.0, data.pods.first().totalUnits)
    }

    @Test
    fun axisTicksCoverTheHighestDay() {
        assertEquals(listOf(0.0, 10.0), podAxisTicks(0.0))
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0), podAxisTicks(41.3))
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0), podAxisTicks(61.0))
    }

    @Test
    fun averagesLeaveThePodInUseOutOfThePodTotal() = runTest {
        val data = calculate()!!

        assertEquals(72.0, data.averageTotalUnits)
        assertEquals(24.0, data.averageUnitsPerDay)
    }
}
