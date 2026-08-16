package app.aaps.plugins.aps.openAPS

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dagger.Reusable
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

@Reusable
class DeltaCalculator @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    /**
     * Holds the results from the delta calculations.
     */
    data class DeltaResult(
        val delta: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double
    )

    /**
     * Calculates delta, short- and long-average delta from InMemoryGlucoseValue.
     *
     * @param data A list of historical glucose data, sorted from newest to oldest.
     * @return A [DeltaResult] containing the calculated deltas.
     */
    fun calculateDeltas(data: MutableList<InMemoryGlucoseValue>): DeltaResult =
        calculateDeltasGeneric(data.map { it.timestamp to it.recalculated })

    /**
     * Same exact window definitions/averaging logic as [calculateDeltas] above, but decoupled from
     * [InMemoryGlucoseValue] -- takes any (timestamp, value) series instead, so a smoothing type's
     * own reconstructed history (not just the live DB readings [calculateDeltas] reads) can be run
     * through the identical math. [calculateDeltas] is now a thin wrapper over this; behavior for its
     * existing callers is unchanged. Added 2026-08-15 for the per-type delta/acceleration comparison
     * work on the UKF3426 branch -- see that branch's scope notes.
     *
     * @param data A list of (timestamp, value) pairs, sorted from newest to oldest.
     */
    fun calculateDeltasGeneric(data: List<Pair<Long, Double>>): DeltaResult {
        if (data.size < 2) {
            return DeltaResult(0.0, 0.0, 0.0)
        }

        var change: Double
        val lastDeltas = mutableListOf<Double>()
        val shortDeltas = mutableListOf<Double>()
        val longDeltas = mutableListOf<Double>()

        val now = data[0]
        val nowDate = now.first
        // start at data[1] as data[0] is the value used in the now calculations
        for (i in 1 until data.size) {
            if (data[i].second > minBgValue) {
                val then = data[i]
                val thenDate = then.first
                val minutesAgo = (nowDate - thenDate).milliseconds.toDouble(DurationUnit.MINUTES)
                change = now.second - then.second
                val avgDel = change / minutesAgo * 5 // multiply by 5 to get the same units as delta, i.e. mg/dL/5m

                // values that are too recent are not considered (this check had been commented out before; now it's just being logged.)
                if (minutesAgo in 0.0 .. minLastDeltaMinutes) {
                    aapsLogger.debug(LTag.GLUCOSE, "$avgDel from $minutesAgo minutes ago is too recent to be considered.")
                }

                // last_deltas are calculated from minLastDeltaMinutes to maxLastDeltaMinutes
                if (minutesAgo in minLastDeltaMinutes .. maxLastDeltaMinutes) { //currently min: 2.5 max 7.5
                    lastDeltas.add(avgDel)
                }
                // short_deltas are calculated from minShortDeltaMinutes to maxShortDeltaMinutes
                if (minutesAgo in minShortDeltaMinutes .. maxShortDeltaMinutes) { //currently min: 2.5 max 17.5
                    shortDeltas.add(avgDel)
                }
                // long_deltas are calculated from minLongDeltaMinutes to maxLongDeltaMinutes
                if (minutesAgo in minLongDeltaMinutes .. maxLongDeltaMinutes) { //currently min: 17.5 max 42.5
                    longDeltas.add(avgDel)
                } else if ( minutesAgo > maxLongDeltaMinutes){ //currently 42.5
                    break // Do not process any more records after maxLongDeltaMinutes
                }
            }
        }
        val shortAverageDelta = average(shortDeltas)
        val delta =
            if (lastDeltas.isEmpty()) {
                shortAverageDelta
            } else {
                average(lastDeltas)
            }

        return DeltaResult(
            delta = delta,
            shortAvgDelta = shortAverageDelta,
            longAvgDelta = average(longDeltas)
        )
    }

    companion object {

        fun average(array: List<Double>): Double {
            if (array.isEmpty()) return 0.0
            return array.sum() / array.size
        }

        private const val minBgValue = 39.0
        private const val minShortDeltaMinutes = 2.5
        private const val maxShortDeltaMinutes = 17.5
        private const val minLastDeltaMinutes = 2.5
        private const val maxLastDeltaMinutes = 7.5
        private const val minLongDeltaMinutes = 17.5
        private const val maxLongDeltaMinutes = 42.5
    }
}