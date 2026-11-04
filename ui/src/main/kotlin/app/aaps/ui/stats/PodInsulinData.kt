// Ported from v4 (AaAPS-kmp-beta1merge, ui/compose/stats) on 2026-11-01, per explicit request: the same numbers as
// v4's Statistics tabs, drawn with Android views in v3. Keep the two in step when one changes.
package app.aaps.ui.stats

import kotlin.math.ceil

/**
 * Pod tab (2026-11-01, per explicit request): the insulin used by each pod, day by day and in total, the pods in
 * sequence from oldest to newest, as the Sensor tab lays glucose over each sensor.
 *
 * A pod runs from one site change to the next. Omnipod Dash and O5 write a site change and an insulin change together
 * when a pod is activated; the Virtual pump has only what is entered with Fill. The site change is what the overview's
 * patch pump light and AutoISF's "last pod" read, so it is the boundary here too.
 */
data class PodDay(
    /** Local midnight of the day this part of the pod falls in */
    val dayStart: Long,
    val from: Long,
    val to: Long,
    /** Null where nothing was found for this part */
    val units: Double?,
    /** A whole day this phone has no records for, taken from the other phone on the second Nightscout. Shown only. */
    val fromSecondary: Boolean = false
)

data class PodInsulin(
    val startTimestamp: Long,
    /** The next site change, or now for the pod in use */
    val endTimestamp: Long,
    val inUse: Boolean,
    /** Oldest first: the part day it started on, whole days, the part day it ended on */
    val days: List<PodDay>
) {

    val totalUnits: Double get() = days.sumOf { it.units ?: 0.0 }
    val hours: Double get() = (endTimestamp - startTimestamp) / HOUR_MS.toDouble()
    val unitsPerDay: Double? get() = if (hours > 0) totalUnits * 24 / hours else null
}

data class PodInsulinData(
    /** Oldest first */
    val pods: List<PodInsulin>,
    /** Pod changes found in the history read, so the offset can be capped */
    val podsAvailable: Int
) {

    val secondaryDays: Int get() = pods.sumOf { pod -> pod.days.count { it.fromSecondary } }
    val averageTotalUnits: Double? get() = pods.filter { !it.inUse }.takeIf { it.isNotEmpty() }?.map { it.totalUnits }?.average()

    /** Over all shown pods, the one in use included: the units of all of them over their hours */
    val averageUnitsPerDay: Double?
        get() {
            val hours = pods.sumOf { it.hours }
            return if (hours > 0) pods.sumOf { it.totalUnits } * 24 / hours else null
        }
}

/** One pod's span, before its insulin is summed */
internal data class PodSpan(val start: Long, val end: Long, val inUse: Boolean)

/**
 * The pods shown at [offset], oldest first. The changes are merged as the Sensor tab merges its starts: the same
 * change can arrive twice, entered here and synced back from Nightscout, a few seconds or minutes apart.
 */
internal fun podSpans(starts: List<Long>, now: Long, offset: Int, podCount: Int = POD_COUNT): List<PodSpan> {
    val newestFirst = distinctSensorStarts(starts.filter { it <= now }).asReversed()
    return newestFirst.withIndex().drop(offset).take(podCount).map { (index, start) ->
        val next = newestFirst.getOrNull(index - 1)
        PodSpan(start = start, end = next ?: now, inUse = next == null)
    }.asReversed()
}

/**
 * [from] to [to] cut at local midnights: (day start, from, to) for each part, oldest first.
 *
 * @param dayStartOf the local midnight a time falls in
 * @param nextDayStart the local midnight after the day a time falls in
 */
internal fun podDaySegments(from: Long, to: Long, dayStartOf: (Long) -> Long, nextDayStart: (Long) -> Long): List<Triple<Long, Long, Long>> {
    val out = mutableListOf<Triple<Long, Long, Long>>()
    var segmentStart = from
    while (segmentStart < to) {
        val segmentEnd = minOf(nextDayStart(segmentStart), to)
        out.add(Triple(dayStartOf(segmentStart), segmentStart, segmentEnd))
        segmentStart = segmentEnd
    }
    return out
}

/**
 * The insulin of the pods shown at [offset].
 *
 * @param starts site change times, in any order and with duplicates
 * @param sumUnits the insulin from one time to another, null when nothing is found
 * @param secondaryUnits the other phone's totals of the whole days given, keyed by their midnights; only asked for the
 *   whole days [sumUnits] finds nothing for
 * @return null when there is no pod change at that offset
 */
internal fun calculatePodInsulinData(
    starts: List<Long>,
    now: Long,
    offset: Int,
    dayStartOf: (Long) -> Long,
    nextDayStart: (Long) -> Long,
    sumUnits: (from: Long, to: Long) -> Double?,
    secondaryUnits: (dayStarts: List<Long>) -> Map<Long, Double> = { emptyMap() },
    podCount: Int = POD_COUNT
): PodInsulinData? {
    val spans = podSpans(starts, now, offset, podCount)
    if (spans.isEmpty()) return null

    val pods = spans.map { span ->
        span to podDaySegments(span.start, span.end, dayStartOf, nextDayStart).map { (dayStart, from, to) ->
            PodDay(dayStart = dayStart, from = from, to = to, units = sumUnits(from, to))
        }
    }
    // A whole day, finished, with nothing here: from the other phone, if it has it
    val missing = pods.flatMap { (_, days) -> days }
        .filter { it.units == null && it.from == it.dayStart && it.to == nextDayStart(it.dayStart) && it.to <= now }
        .map { it.dayStart }
    val remote = if (missing.isEmpty()) emptyMap() else secondaryUnits(missing)

    return PodInsulinData(
        pods = pods.map { (span, days) ->
            PodInsulin(
                startTimestamp = span.start,
                endTimestamp = span.end,
                inUse = span.inUse,
                days = days.map { day ->
                    val fill = if (day.units == null && day.from == day.dayStart) remote[day.dayStart] else null
                    if (fill != null) day.copy(units = fill, fromSecondary = true) else day
                }
            )
        },
        podsAvailable = distinctSensorStarts(starts.filter { it <= now }).size
    )
}

internal const val POD_COUNT = 4

internal fun podAxisTicks(highestUnits: Double): List<Double> {
    val step = if (highestUnits > 60.0) 20.0 else 10.0
    val top = (ceil(highestUnits / step) * step).coerceAtLeast(step)
    return generateSequence(0.0) { it + step }.takeWhile { it <= top }.toList()
}

private const val HOUR_MS = 60L * 60L * 1000L
