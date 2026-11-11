package app.aaps.core.interfaces.utils

import app.aaps.core.keys.time.AppTimeZone as ScheduleTimeZone
import java.util.Calendar

/** Calendar access for UI code that already depends on core interfaces. */
object AppCalendar {
    fun current(): Calendar = ScheduleTimeZone.calendar()
}
