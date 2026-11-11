package app.aaps.core.utils

import app.aaps.core.data.time.AppTimeZone

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Midnight time conversion
 */
object MidnightUtils {

    /**
     * Actual passed seconds from midnight ignoring DST change
     * (thus always having 24 hours in a day, not 23 or 25 in days where DST changes)
     *
     * @return seconds
     */
    fun secondsFromMidnight(): Int {
        val nowZoned = ZonedDateTime.now(AppTimeZone.current())
        val localTime = nowZoned.toLocalTime()
        val midnight = nowZoned.toLocalDate().atStartOfDay(nowZoned.zone).toLocalTime()
        val duration = Duration.between(midnight, localTime)
        return duration.seconds.toInt()
    }

    /**
     * Passed seconds from midnight for specified time ignoring DST change
     * (thus always having 24 hours in a day, not 23 or 25 in days where DST changes)
     *
     * @param timestamp time
     * @return seconds
     */
   fun secondsFromMidnight(timestamp: Long): Int {
        val timeZoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), AppTimeZone.current())
        val localTime = timeZoned.toLocalTime()
        val midnight = timeZoned.toLocalDate().atStartOfDay(timeZoned.zone).toLocalTime()
        val duration: Duration = Duration.between(midnight, localTime)
        return duration.seconds.toInt()
    }

    /**
     * Passed milliseconds from midnight for specified time ignoring DST change
     * (thus always having 24 hours in a day, not 23 or 25 in days where DST changes)
     *
     * @param timestamp time
     * @return milliseconds
     */
    fun milliSecFromMidnight(timestamp: Long): Long {
        val timeZoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), AppTimeZone.current())
        val localTime = timeZoned.toLocalTime()
        val midnight = timeZoned.toLocalDate().atStartOfDay(timeZoned.zone).toLocalTime()
        val duration = Duration.between(midnight, localTime)
        return duration.toMillis()
    }
}