package app.aaps.core.data.time

import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/** The zone used by local schedules. The app supplies an override only for a virtual master. */
object AppTimeZone {
    @Volatile var virtualMasterZone: (() -> ZoneId?)? = null

    fun current(): ZoneId = virtualMasterZone?.invoke() ?: ZoneId.systemDefault()

    fun timeZone(): TimeZone = TimeZone.getTimeZone(current())

    fun calendar(): Calendar = Calendar.getInstance(timeZone())
}
