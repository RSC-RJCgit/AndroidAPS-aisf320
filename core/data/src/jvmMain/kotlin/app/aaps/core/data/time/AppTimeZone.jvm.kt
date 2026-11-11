package app.aaps.core.data.time

import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

fun AppTimeZone.javaZone(): ZoneId = ZoneId.of(current().id)

fun AppTimeZone.javaTimeZone(): TimeZone = TimeZone.getTimeZone(current().id)

fun AppTimeZone.calendar(): Calendar = Calendar.getInstance(javaTimeZone())
