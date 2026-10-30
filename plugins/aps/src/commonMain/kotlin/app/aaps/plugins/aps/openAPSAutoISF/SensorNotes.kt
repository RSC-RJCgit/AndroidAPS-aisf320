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

// AlarmRecent is cleared when its saved time is missing, older than 24 hours, or inside the ignored overnight window.
internal fun alarmRecentExpired(now: Long, alarmAt: Long, alarmMinuteOfDay: Int, useWindow: Boolean = true): Boolean =
    alarmAt <= 0L || now - alarmAt > 24 * 3_600_000L || (useWindow && inHypoIgnoreWindow(alarmMinuteOfDay))

// The alarm role revert runs once per alarm: only when the saved alarm time is newer than the last one handled.
internal fun alarmRevertDue(alarmAt: Long, handledAt: Long): Boolean = alarmAt > handledAt

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
        // 2026-10-29, per explicit request: NewDay3 runs to the end of day 4 (was < 3.0). 28 Oct was day 4.
        sensorDays != null && sensorDays >= 2.0 && sensorDays < 4.0 -> SensorTier("NewDay3", slopeBase - 0.02, offsetBase + 0.05)
        sensorDays != null && sensorDays >= 9.0 && sensorDays < 13.0 -> SensorTier("1", slopeBase - 0.02, offsetBase + 0.05)
        sensorDays != null && sensorDays >= 13.0 && sensorDays < 14.0 -> SensorTier("2", slopeBase - 0.04, offsetBase + 0.10)
        sensorDays != null && sensorDays >= 14.0 && sensorDays < 15.0 -> SensorTier("3", slopeBase - 0.07, offsetBase + 0.15)
        else -> null
    }
}

/**
 * The raw-spike check (2026-10-29, per explicit request). A raw Libre reading that jumps 0.9 mmol or more in 5 min and falls
 * back 1.5 mmol or more within the next 10 min is a sensor fault: real glucose does not do that. 28 Oct Live (sensor day 4)
 * had three: 07:39 (a shower), 16:45 and 22:51 (raw 11.1 -> 12.0 -> 9.5). In the 7 weeks before there were about 20.
 *
 * A spike opens a window of [RAW_SPIKE_WINDOW_HOURS]. In it, an acce boost is capped at 1.0 while raw rises more than twice
 * as fast as BG ([rawRisingTooFast]; calibration alone explains 1.33x), and the sensor-age Libre slope tier may apply.
 */
internal const val RAW_SPIKE_WINDOW_HOURS = 6L
private const val RAW_SPIKE_JUMP_MGDL = 16.2 /* 0.9 mmol */
private const val RAW_SPIKE_FALL_MGDL = 27.0 /* 1.5 mmol */

/** A raw spike: when it peaked, how much it jumped in 5 min and how far it fell after (negative), in mg/dL */
internal data class RawSpike(val peakAt: Long, val jumpMgdl: Double, val fallMgdl: Double)

/**
 * The newest raw spike in [raw] (time to raw mg/dL, oldest first), or null. The jump is measured from the reading 4-7 min
 * before the peak; the fall to the lowest reading in the 10 min after it.
 */
internal fun findRawSpike(raw: List<Pair<Long, Double>>): RawSpike? {
    for (i in raw.indices.reversed()) {
        val (peakAt, peak) = raw[i]
        val base = raw.subList(0, i).lastOrNull { peakAt - it.first in 4 * 60_000L..7 * 60_000L } ?: continue
        val jump = peak - base.second
        if (jump < RAW_SPIKE_JUMP_MGDL) continue
        val after = raw.subList(i + 1, raw.size).filter { it.first - peakAt <= 10 * 60_000L }
        val fall = (after.minOfOrNull { it.second } ?: continue) - peak
        if (fall <= -RAW_SPIKE_FALL_MGDL) return RawSpike(peakAt, jump, fall)
    }
    return null
}

/**
 * The shower fallback for Shower12 (2026-10-30, per explicit request): the jump half of [findRawSpike], without the fall after it,
 * which comes 10 min too late to decide on. The newest raw reading of the last 5 min that is 0.9 mmol or more above the reading
 * 4-7 min before it; the jump in mg/dL, or null. On 52 mornings of Client uploads (9 Sep - 30 Oct) Shower12 came with a raw jump
 * of 1.0-2.5 almost every time, and 13-74 steps kept it from firing on 9, 18, 21 and 25 Sep while raw jumped 1.0-1.8.
 */
internal fun findRawJump(raw: List<Pair<Long, Double>>, now: Long): Double? {
    for (i in raw.indices.reversed()) {
        val (at, value) = raw[i]
        if (now - at > 5 * 60_000L) break
        val base = raw.subList(0, i).lastOrNull { at - it.first in 4 * 60_000L..7 * 60_000L } ?: continue
        val jump = value - base.second
        if (jump >= RAW_SPIKE_JUMP_MGDL) return jump
    }
    return null
}

/**
 * Raw rising more than twice as fast as BG: the newest raw minus the raw 4-7 min before is 0.9 mmol or more, BG is rising,
 * and the raw rise is more than twice the BG [bgDeltaMgdl]. Calibration alone (slope 0.75) makes raw rise 1.33x as fast.
 */
internal fun rawRisingTooFast(raw: List<Pair<Long, Double>>, bgDeltaMgdl: Double): Boolean {
    val (now, latest) = raw.lastOrNull() ?: return false
    val before = raw.lastOrNull { now - it.first in 4 * 60_000L..7 * 60_000L } ?: return false
    val rawDelta = latest - before.second
    return bgDeltaMgdl > 0.0 && rawDelta >= RAW_SPIKE_JUMP_MGDL && rawDelta > 2.0 * bgDeltaMgdl
}

/**
 * The raw-dip check (2026-10-29, per explicit request), for the sensor-age Libre slope tier only. It changes no automation and
 * no insulin delivery. While BG is already falling, raw drops 1.5 mmol or more in 5 min, ends 0.8 mmol or more below BG, and
 * stays down for the next 10 min (no more than 0.5 mmol back up): it goes too low too quickly and stays there. Calibration alone
 * puts BG only about 0.2 above raw at raw 4.7. In the 7 weeks of Client uploads to 29 Oct this was 11 times on 7 days, among
 * them 28 Oct 19:58 (day 4: BG 7.0, raw 4.9, -2.0 in 5 min). A drop of 0.9 without the other two was 193 times on 45 days.
 * Dips between 22:30 and 07:30 are taken as compression lows (lying on the sensor), which say nothing about its calibration, so
 * they do not count ([inCompressionHours]).
 */
internal data class RawDip(val bottomAt: Long, val dropMgdl: Double, val underBgMgdl: Double)

private const val RAW_DIP_DROP_MGDL = 27.0 /* 1.5 mmol */
private const val RAW_DIP_UNDER_BG_MGDL = 14.4 /* 0.8 mmol */
private const val RAW_DIP_STAYS_MGDL = 9.0 /* 0.5 mmol */

/** The newest raw dip in [readings] (time, BG mg/dL, raw mg/dL; oldest first), or null */
internal fun findRawDip(readings: List<Triple<Long, Double, Double>>): RawDip? {
    for (i in readings.indices.reversed()) {
        val (bottomAt, bottomBg, bottom) = readings[i]
        // The 10 min after must be there in full, and none of it more than 0.5 mmol back up
        val after = readings.subList(i + 1, readings.size).filter { it.first - bottomAt <= 10 * 60_000L }
        if (after.isEmpty() || after.last().first - bottomAt < 9 * 60_000L) continue
        if (after.maxOf { it.third } > bottom + RAW_DIP_STAYS_MGDL) continue
        val startIndex = readings.subList(0, i).indexOfLast { bottomAt - it.first in 4 * 60_000L..7 * 60_000L }
        if (startIndex < 0) continue
        val (startAt, startBg, start) = readings[startIndex]
        val drop = start - bottom
        if (drop < RAW_DIP_DROP_MGDL || bottomBg - bottom < RAW_DIP_UNDER_BG_MGDL) continue
        // BG already falling before the drop
        val earlier = readings.subList(0, startIndex).lastOrNull { startAt - it.first in 4 * 60_000L..12 * 60_000L } ?: continue
        if (earlier.second <= startBg) continue
        return RawDip(bottomAt, drop, bottomBg - bottom)
    }
    return null
}

/** 22:30 to 07:30, when a dip that bounces back is most likely a compression low. [minuteOfDay] is local time. */
internal fun inCompressionHours(minuteOfDay: Int): Boolean = minuteOfDay >= 22 * 60 + 30 || minuteOfDay < 7 * 60 + 30

/** A spike counts unless it came in the 2 h after Shower12 (shower steam on the sensor). [showerMinutesAgo] is null when none. */
internal fun rawSpikeCounts(spikeAt: Long, now: Long, showerMinutesAgo: Int?): Boolean {
    if (showerMinutesAgo == null) return true
    val showerAt = now - showerMinutesAgo * 60_000L
    return spikeAt - showerAt !in 0L..2 * 3_600_000L
}

internal fun rawSpikeWindowOpen(lastSpikeAt: Long, now: Long): Boolean =
    lastSpikeAt != 0L && now - lastSpikeAt < RAW_SPIKE_WINDOW_HOURS * 3_600_000L

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
