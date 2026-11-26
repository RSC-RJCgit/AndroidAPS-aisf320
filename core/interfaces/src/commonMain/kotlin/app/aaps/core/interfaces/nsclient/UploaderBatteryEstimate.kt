package app.aaps.core.interfaces.nsclient

import app.aaps.core.interfaces.utils.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * Estimates the uploader's battery life from its current unplugged interval.
 *
 * 2026-11-25, per explicit request (same as 3426): the interval no longer needs a charging -> unplugged change seen live.
 * The first unplugged reading after a start, or after an unknown charging state, starts it; on the loop phone the real
 * start of the discharge is saved ([unpluggedSnapshot]) and given back after a restart ([restoreUnplugged]), so the
 * "to HHMM" forecast survives app restarts and new installs. Before this it showed only "Bat%=NN" until the next charge.
 */
class UploaderBatteryEstimate {

    private data class Reading(val time: Long, val percent: Int, val charging: Boolean?)
    private data class Interval(val latest: Reading, val unplugged: Reading?)

    private val interval = MutableStateFlow<Interval?>(null)

    /** A saved discharge start, used once by the first unplugged reading if it still fits (older, same or higher percent). */
    private val restored = MutableStateFlow<Reading?>(null)

    fun record(time: Long, percent: Int, charging: Boolean?) {
        if (time <= 0 || percent !in 1..100) return
        val reading = Reading(time, percent, charging)
        interval.update { previous ->
            if (previous != null && time <= previous.latest.time) return@update previous
            val unplugged = when {
                charging != false -> null
                previous?.latest?.charging == true -> reading
                previous?.latest?.charging == false -> previous.unplugged ?: reading
                // First reading since start, or the state before was unknown: a saved start that still fits, else this one.
                else -> restored.value?.takeIf { it.time < time && it.percent >= percent } ?: reading
            }
            Interval(reading, unplugged)
        }
        restored.value = null
        learnRate()
    }

    /**
     * The fall rate of the last discharge long enough to trust (at least 30 minutes and 3%), in ms per percent. Kept
     * through charging, so a plugged-in phone can show when it would reach 1% if unplugged now (2026-11-26, per explicit
     * request). Learnt again from each discharge; lost on a restart until the next one (clients rebuild it from NS).
     */
    @Volatile
    private var msPerPercent: Long? = null

    private fun learnRate() {
        val current = interval.value ?: return
        val unplugged = current.unplugged ?: return
        val duration = current.latest.time - unplugged.time
        val drop = unplugged.percent - current.latest.percent
        if (duration >= MIN_DURATION_MS && drop >= MIN_DROP) msPerPercent = duration / drop
    }

    /** The start of the current discharge as "time:percent", or "" when none is known. For the loop phone to save. */
    fun unpluggedSnapshot(): String = interval.value?.unplugged?.let { "${it.time}:${it.percent}" } ?: ""

    /** Gives back a start saved by [unpluggedSnapshot]. Only used if no reading has been recorded yet. */
    fun restoreUnplugged(snapshot: String) {
        if (interval.value != null) return
        val parts = snapshot.split(':')
        val time = parts.getOrNull(0)?.toLongOrNull() ?: return
        val percent = parts.getOrNull(1)?.toIntOrNull() ?: return
        if (time <= 0 || percent !in 1..100) return
        restored.value = Reading(time, percent, false)
    }

    /**
     * The last end time worked out, and the discharge start it belongs to (2026-11-26, per explicit request). Shown while the
     * newest reading is too old or stamped ahead of this phone's clock, until a newer reading gives a new one. Cleared when
     * the phone charges, a new discharge starts, or that end time has passed.
     */
    private var lastForecast: Long? = null
    private var lastForecastStart: Long? = null

    fun label(now: Long, dateUtil: DateUtil): String? {
        val (latest, unplugged) = interval.value ?: return null
        val percent = "Bat%=${latest.percent}"
        // A reading up to 2 minutes ahead of this phone's clock counts as current (the loop phone's clock may run ahead)
        val fresh = now - latest.time in -CLOCK_AHEAD_MS..STALE_MS
        if (unplugged == null) {
            lastForecast = null
            lastForecastStart = null
            val rate = msPerPercent
            if (latest.charging != true || !fresh || rate == null) return percent
            // On charge: when 1% would be reached if unplugged now, at the last discharge's rate
            return "$percent chg, off now to ${hhmm(latest.time + (latest.percent - 1L) * rate, dateUtil)}"
        }
        if (lastForecastStart != unplugged.time) lastForecast = null
        val duration = latest.time - unplugged.time
        val drop = unplugged.percent - latest.percent
        if (fresh && duration >= MIN_DURATION_MS && drop >= MIN_DROP) {
            lastForecast = latest.time + ((latest.percent - 1.0) * duration / drop).toLong()
            lastForecastStart = unplugged.time
        }
        val forecast = lastForecast
        if (forecast == null || forecast < now) {
            lastForecast = null
            return percent
        }
        return "$percent to ${hhmm(forecast, dateUtil)}"
    }

    private fun hhmm(time: Long, dateUtil: DateUtil): String =
        dateUtil.toISONoZone(time).substringAfter('T').take(5).replace(":", "")

    companion object {
        private const val CLOCK_AHEAD_MS = 120_000L
        private const val STALE_MS = 900_000L
        private const val MIN_DURATION_MS = 1_800_000L
        private const val MIN_DROP = 3
        val local = UploaderBatteryEstimate()
    }
}
