package app.aaps.core.interfaces.nsclient

import app.aaps.core.interfaces.utils.DateUtil
import java.util.concurrent.atomic.AtomicReference

/** Estimates the uploader's battery life from its current unplugged interval. */
class UploaderBatteryEstimate {

    private data class Reading(val time: Long, val percent: Int, val charging: Boolean?)
    private data class Interval(val latest: Reading, val unplugged: Reading?)

    private val interval = AtomicReference<Interval?>(null)

    fun record(time: Long, percent: Int, charging: Boolean?) {
        if (time <= 0 || percent !in 1..100) return
        val reading = Reading(time, percent, charging)
        while (true) {
            val previous = interval.get()
            if (previous != null && time <= previous.latest.time) return
            val unplugged = when {
                charging != false -> null
                previous?.latest?.charging == true -> reading
                previous?.latest?.charging == false -> previous.unplugged
                else -> null
            }
            if (interval.compareAndSet(previous, Interval(reading, unplugged))) return
        }
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
