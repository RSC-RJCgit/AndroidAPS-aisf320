package app.aaps.core.data.time

import kotlinx.datetime.TimeZone
import kotlin.concurrent.Volatile

/** The zone used by local schedules and displayed times. The app supplies an override only for a virtual master or an AAPSCLIENT. */
object AppTimeZone {
    @Volatile var virtualMasterZone: (() -> TimeZone?)? = null

    fun current(): TimeZone = virtualMasterZone?.invoke() ?: SystemTimeZone.current()
}
