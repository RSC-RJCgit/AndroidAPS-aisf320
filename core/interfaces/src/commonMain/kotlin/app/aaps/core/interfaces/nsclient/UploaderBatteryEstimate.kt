package app.aaps.core.interfaces.nsclient

import app.aaps.core.interfaces.utils.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Estimates the uploader's battery life from its current unplugged interval. */
class UploaderBatteryEstimate {

    private data class Reading(val time: Long, val percent: Int, val charging: Boolean?)
    private data class Interval(val latest: Reading, val unplugged: Reading?)

    private val interval = MutableStateFlow<Interval?>(null)

    fun record(time: Long, percent: Int, charging: Boolean?) {
        if (time <= 0 || percent !in 1..100) return
        val reading = Reading(time, percent, charging)
        interval.update { previous ->
            if (previous != null && time <= previous.latest.time) return@update previous
            val unplugged = when {
                charging != false -> null
                previous?.latest?.charging == true -> reading
                previous?.latest?.charging == false -> previous.unplugged
                else -> null
            }
            Interval(reading, unplugged)
        }
    }

    fun label(now: Long, dateUtil: DateUtil): String? {
        val (latest, unplugged) = interval.value ?: return null
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
