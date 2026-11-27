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
     * request). Learnt again from each discharge. Since 2026-11-26 (per explicit request) the loop phone saves it with the
     * discharge start ([unpluggedSnapshot]), so it survives a restart; clients rebuild it from NS.
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

    /**
     * The start of the current discharge and the learnt rate as "time:percent:msPerPercent", or "" when neither is known.
     * For the loop phone to save. With no current discharge (charging) the start is "0:0", so the rate is still kept.
     */
    fun unpluggedSnapshot(): String {
        val start = interval.value?.unplugged
        val rate = msPerPercent
        if (start == null && rate == null) return ""
        return "${start?.time ?: 0}:${start?.percent ?: 0}" + (rate?.let { ":$it" } ?: "")
    }

    /** Gives back a start and rate saved by [unpluggedSnapshot] (an older "time:percent" still works). The start is only used if no reading has been recorded yet. */
    fun restoreUnplugged(snapshot: String) {
        val parts = snapshot.split(':')
        if (msPerPercent == null) parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }?.let { msPerPercent = it }
        if (interval.value != null) return
        val time = parts.getOrNull(0)?.toLongOrNull() ?: return
        val percent = parts.getOrNull(1)?.toIntOrNull() ?: return
        if (time <= 0 || percent !in 1..100) return
        restored.value = Reading(time, percent, false)
    }

    /**
     * The last end time worked out, and the discharge start it belongs to (2026-11-26, per explicit request). Shown while the
     * newest reading is too old or stamped ahead of this phone's clock, until a newer reading gives a new one. Cleared when
     * the phone charges or a new discharge starts. Since 2026-11-26 (per explicit request) an end time that has passed
     * stays, marked "!", so the line always has a time once a rate is known.
     */
    private var lastForecast: Long? = null
    private var lastForecastStart: Long? = null

    /** Set while the current forecast comes from the discharge itself, not from the last learnt rate. */
    private var lastForecastMeasured = false

    fun label(now: Long, dateUtil: DateUtil): String? {
        val (latest, unplugged) = interval.value ?: return null
        val percent = "Bat%=${latest.percent}"
        // A reading up to 2 minutes ahead of this phone's clock counts as current (the loop phone's clock may run ahead)
        val fresh = now - latest.time in -CLOCK_AHEAD_MS..STALE_MS
        val rate = msPerPercent
        if (unplugged == null) {
            lastForecast = null
            lastForecastStart = null
            lastForecastMeasured = false
            if (latest.charging != true || rate == null) return percent
            // On charge: when 1% would be reached if unplugged now, at the last discharge's rate. With an old reading
            // (2026-11-26, per explicit request) it is counted from now with the last known level instead of left out.
            val from = if (fresh) latest.time else now
            return "$percent till ${hhmm(from + (latest.percent - 1L) * rate, now, dateUtil)}"
        }
        if (lastForecastStart != unplugged.time) {
            lastForecast = null
            lastForecastMeasured = false
        }
        val duration = latest.time - unplugged.time
        val drop = unplugged.percent - latest.percent
        if (fresh && duration >= MIN_DURATION_MS && drop >= MIN_DROP) {
            lastForecast = latest.time + ((latest.percent - 1.0) * duration / drop).toLong()
            lastForecastStart = unplugged.time
            lastForecastMeasured = true
        } else if (rate != null && !lastForecastMeasured && (fresh || lastForecast == null)) {
            // 2026-11-26, per explicit request: early in a discharge (under 30 minutes or 3%), or readings old before the
            // first forecast, the last learnt rate gives the end time from the newest reading instead of no time at all
            lastForecast = latest.time + (latest.percent - 1L) * rate
            lastForecastStart = unplugged.time
        }
        val forecast = lastForecast ?: return percent
        return "$percent till ${hhmm(forecast, now, dateUtil)}" + if (forecast < now) "!" else ""
    }

    /**
     * 2026-11-27, per explicit request: the time starts with S for the same day (S1316), N for the next day (N1315), and N2, N3 ...
     * with a space for later days (N2 1315). Days are counted in the same time zone as the time itself.
     */
    private fun hhmm(time: Long, now: Long, dateUtil: DateUtil): String {
        val iso = dateUtil.toISONoZone(time)
        val clock = iso.substringAfter('T').take(5).replace(":", "")
        val day = iso.substringBefore('T')
        if (day == dateUtil.toISONoZone(now).substringBefore('T')) return "S$clock"
        for (days in 1..30) {
            if (day == dateUtil.toISONoZone(now + days * DAY_MS).substringBefore('T'))
                return if (days == 1) "N$clock" else "N$days $clock"
        }
        return clock
    }

    companion object {
        private const val CLOCK_AHEAD_MS = 120_000L
        private const val STALE_MS = 900_000L
        private const val MIN_DURATION_MS = 1_800_000L
        private const val MIN_DROP = 3
        private const val DAY_MS = 86_400_000L
        val local = UploaderBatteryEstimate()
    }
}
