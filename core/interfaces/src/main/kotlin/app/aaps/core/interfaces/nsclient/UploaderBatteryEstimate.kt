package app.aaps.core.interfaces.nsclient

import app.aaps.core.interfaces.utils.DateUtil
import java.util.concurrent.atomic.AtomicReference

/**
 * Estimates the uploader's battery life from its current unplugged interval.
 *
 * 2026-11-25, per explicit request: the interval no longer needs a charging -> unplugged change seen live. The first
 * unplugged reading after a start, or after an unknown charging state, starts it; on the loop phone the real start of the
 * discharge is saved ([unpluggedSnapshot]) and given back after a restart ([restoreUnplugged]), so the "to HHMM" forecast
 * survives app restarts and new installs. Before this it showed only "Bat%=NN" until the next charge.
 */
class UploaderBatteryEstimate {

    private data class Reading(val time: Long, val percent: Int, val charging: Boolean?)
    private data class Interval(val latest: Reading, val unplugged: Reading?)

    private val interval = AtomicReference<Interval?>(null)

    /** A saved discharge start, used once by the first unplugged reading if it still fits (older, same or higher percent). */
    private val restored = AtomicReference<Reading?>(null)

    fun record(time: Long, percent: Int, charging: Boolean?) {
        if (time <= 0 || percent !in 1..100) return
        val reading = Reading(time, percent, charging)
        while (true) {
            val previous = interval.get()
            if (previous != null && time <= previous.latest.time) return
            val unplugged = when {
                charging != false -> null
                previous?.latest?.charging == true -> reading
                previous?.latest?.charging == false -> previous.unplugged ?: reading
                // First reading since start, or the state before was unknown: a saved start that still fits, else this one.
                else -> restored.get()?.takeIf { it.time < time && it.percent >= percent } ?: reading
            }
            if (interval.compareAndSet(previous, Interval(reading, unplugged))) {
                restored.set(null)
                return
            }
        }
    }

    /** The start of the current discharge as "time:percent", or "" when none is known. For the loop phone to save. */
    fun unpluggedSnapshot(): String = interval.get()?.unplugged?.let { "${it.time}:${it.percent}" } ?: ""

    /** Gives back a start saved by [unpluggedSnapshot]. Only used if no reading has been recorded yet. */
    fun restoreUnplugged(snapshot: String) {
        if (interval.get() != null) return
        val parts = snapshot.split(':')
        val time = parts.getOrNull(0)?.toLongOrNull() ?: return
        val percent = parts.getOrNull(1)?.toIntOrNull() ?: return
        if (time <= 0 || percent !in 1..100) return
        restored.set(Reading(time, percent, false))
    }

    fun label(now: Long, dateUtil: DateUtil): String? {
        val (latest, unplugged) = interval.get() ?: return null
        val percent = "Bat%=${latest.percent}"
        if (unplugged == null || now - latest.time !in 0L..900_000L) return percent
        val duration = latest.time - unplugged.time
        val drop = unplugged.percent - latest.percent
        if (duration < 1_800_000L || drop < 3) return percent
        val forecast = latest.time + ((latest.percent - 1.0) * duration / drop).toLong()
        if (forecast < now) return percent
        val localTime = dateUtil.toISONoZone(forecast).substringAfter('T').take(5).replace(":", "")
        return "$percent to $localTime"
    }

    companion object {
        val local = UploaderBatteryEstimate()
    }
}
