package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs

// Steps over the last hour, IOB between 2 and 4, glucose at or under 8.0 mmol, rising, no carbs.
internal fun stepsSteroidsOffShouldFire(
    ready: Boolean,
    steps60: Int,
    iob: Double,
    bg: Double,
    delta: Double,
    cob: Double,
): Boolean = ready && steps60 >= 1000 && iob in 2.0..4.0 && bg <= 144.1 && delta >= 9.0 && cob == 0.0

// A missing sensor age is 0 hours, so the narrow windows stay closed. A missing pod does not block.
internal fun preSoakBlock(ready: Boolean, livePump: Boolean, sensorHours: Double, podHours: Double?): String? {
    if (!ready || !livePump) return null
    val podOk = podHours == null || podHours <= 80.0
    if (!podOk) return null
    return when {
        sensorHours in 336.0..336.1 -> "14.0"
        sensorHours in 348.0..348.1 -> "14.5"
        else -> null
    }
}

internal fun sensorHourHit(ready: Boolean, livePump: Boolean, sensorHours: Double, podHours: Double?, low: Double, high: Double): Boolean {
    if (!ready || !livePump) return false
    val podOk = podHours == null || podHours <= 80.0
    return podOk && sensorHours >= low && sensorHours <= high
}

// Turns the sensor-age code off. A missing pod or sensor age does not count as too old.
internal fun sensorAgeShouldTurnOff(codeEnabled: Boolean, podHours: Double?, sensorDays: Double?): Boolean =
    codeEnabled && ((podHours != null && podHours > 80.0) || (sensorDays != null && sensorDays > 15.0))

// Turns the sensor-age code back on only after an automatic off, and only when both ages have recovered.
internal fun sensorAgeShouldTurnOn(codeEnabled: Boolean, latched: Boolean, podHours: Double?, sensorDays: Double?): Boolean =
    !codeEnabled && latched && podHours != null && podHours < 80.0 && sensorDays != null && sensorDays <= 15.0

internal fun alarmHypoRoleShouldRevert(statesOn: Boolean, alarmRecent: Boolean, mjHasValues: Boolean, noMjRemains: Boolean): Boolean =
    statesOn && alarmRecent && mjHasValues && !noMjRemains

// A live high is written by the caller. The scan runs only when the saved time is missing or older than 48 hours.
internal fun libreOver12ShouldScan(liveHigh: Boolean, existingTs: Long, now: Long, ready: Boolean): Boolean =
    !liveHigh && (existingTs == 0L || now - existingTs > 48 * 3_600_000L) && ready

internal data class SensorTier(val name: String, val slope: Double, val offset: Double)

// Day 0 matches the steepest old-sensor tier. A pod over 60 hours opens the day-2 tier when the sensor is not in a tighter window.
internal fun oldSensorTier(sensorDays: Double?, podHours: Double?, slopeBase: Double, offsetBase: Double): SensorTier? {
    val podOpensDay2 = podHours != null && podHours > 60.0
    return when {
        sensorDays != null && sensorDays < 1.0 -> SensorTier("NewDay1", slopeBase - 0.07, offsetBase + 0.15)
        (sensorDays != null && sensorDays >= 1.0 && sensorDays < 2.0) || podOpensDay2 ->
            SensorTier("NewDay2", slopeBase - 0.04, offsetBase + 0.10)
        sensorDays != null && sensorDays >= 2.0 && sensorDays < 3.0 -> SensorTier("NewDay3", slopeBase - 0.02, offsetBase + 0.05)
        sensorDays != null && sensorDays >= 9.0 && sensorDays < 13.0 -> SensorTier("1", slopeBase - 0.02, offsetBase + 0.05)
        sensorDays != null && sensorDays >= 13.0 && sensorDays < 14.0 -> SensorTier("2", slopeBase - 0.04, offsetBase + 0.10)
        sensorDays != null && sensorDays >= 14.0 && sensorDays < 15.0 -> SensorTier("3", slopeBase - 0.07, offsetBase + 0.15)
        else -> null
    }
}

internal fun slopeRestoreDeferred(bg: Double, delta: Double, shortDelta: Double): Boolean =
    bg < 108.1 || delta < 0.0 || shortDelta < 0.0

internal fun slopesDiffer(current: Double, target: Double): Boolean = abs(current - target) > 0.001

// Keeps the start time while glucose stays high. A clear resets it.
internal fun oldPodHighSince(now: Long, highNow: Boolean, previous: Long): Long =
    if (highNow) {
        if (previous == 0L) now else previous
    } else 0L

internal fun oldPodBoostShouldStart(
    active: Boolean,
    podHours: Double?,
    highSince: Long,
    now: Long,
    insulinReq: Double?,
    maxIob: Double,
): Boolean {
    if (active) return false
    val podOld = podHours != null && podHours > 60.0
    val sustained = highSince != 0L && now - highSince >= 2 * 3_600_000L
    val lowReq = insulinReq != null && insulinReq < 0.10 * maxIob
    return podOld && sustained && lowReq
}

internal fun oldPodBoostShouldStop(active: Boolean, bg: Double, delta: Double, podHours: Double?): Boolean =
    active && bg < 144.1 && (delta < 0.0 || (podHours != null && podHours < 6.0))
