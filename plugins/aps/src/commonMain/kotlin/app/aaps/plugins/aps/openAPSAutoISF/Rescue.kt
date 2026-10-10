package app.aaps.plugins.aps.openAPSAutoISF

// Glucose is at least 8.5 mmol and IOB is under 40% of max. HP2 over 6.5 raises the SMB ratio.
// HP2 within 1.0 mmol of target lowers the target instead. A missing prediction stays closed.
// [nightRising] is still passed but no longer lowers the bar (night 7.5 mmol bar withdrawn 2026-10-08).
internal enum class StuckHighBranch { RATIO, TARGET }

internal fun stuckHighBranch(
    ready: Boolean,
    bg: Double,
    iob: Double,
    maxIob: Double,
    hp: Double?,
    targetMmol: Double,
    nightRising: Boolean = false,
): StuckHighBranch? {
    // 2026-10-08: the night 7.5 mmol bar is withdrawn. A large SMB at BG 7.7 at 02:29 led to a hypo.
    val bar = 153.1
    if (!ready || hp == null || bg < bar || iob >= 0.40 * maxIob) return null
    return when {
        hp > 6.5 -> StuckHighBranch.RATIO
        hp <= targetMmol + 1.0 -> StuckHighBranch.TARGET
        else -> null
    }
}

internal fun stuckHighRatio(current: Double, maxRatio: Double): Double = (current + 0.20).coerceAtMost(maxRatio)

internal fun poorResponseRatio(current: Double, maxRatio: Double): Double = (current + 0.05).coerceAtMost(maxRatio)

// 2.0 mmol under the current target, and never under 4.0 mmol.
internal fun rescueTargetMgdl(targetBg: Double): Double = (targetBg - 36.0).coerceAtLeast(72.1)

// Stage 1 is 6 to 8 minutes after the dose and a rise of 0.3 mmol. Stage 2 is 14 to 16 minutes and 0.5 mmol.
// Stage 1 wins when both could match. Returns 1, 2, or null.
internal fun poorResponseStage(
    minutesSinceDose: Double,
    riseMgdl: Double,
    stillRising: Boolean,
    noDecel: Boolean,
    iobRoom: Boolean,
    stage1Ready: Boolean,
    stage2Ready: Boolean,
): Int? {
    if (!iobRoom || !stillRising || !noDecel) return null
    if (minutesSinceDose in 6.0..8.0 && riseMgdl >= 5.4 && stage1Ready) return 1
    if (minutesSinceDose in 14.0..16.0 && riseMgdl >= 9.0 && stage2Ready) return 2
    return null
}

internal fun shouldLatchBigDose(smbDelivered: Double, recentBoost: Boolean): Boolean =
    smbDelivered >= 0.6 && recentBoost

internal data class UnexplainedHighState(val since: Long, val mealSeen: Boolean)

// Over 8.0 mmol starts the clock. Carbs or a recent UAM boost mark the stretch as explained.
// A reading at or under 8.0 mmol clears it.
internal fun nextUnexplainedHigh(
    now: Long,
    highNow: Boolean,
    mealNow: Boolean,
    since: Long,
    mealSeen: Boolean,
): UnexplainedHighState {
    if (highNow) {
        if (since == 0L) return UnexplainedHighState(now, mealSeen = false)
        if (mealNow && !mealSeen) return UnexplainedHighState(since, mealSeen = true)
        return UnexplainedHighState(since, mealSeen)
    }
    if (since != 0L) return UnexplainedHighState(0L, mealSeen = false)
    return UnexplainedHighState(since, mealSeen)
}

internal fun unexplainedHighIsSustained(now: Long, since: Long): Boolean =
    since != 0L && now - since >= 2 * 3_600_000L

// Block 1 is 01:00 until 06:00 and not on the Low name. The HnAM tag uses a tighter falling gate.
// Block 2 is 05:00 until 05:30. Returns the block name, or null.
internal fun offHighBlock(
    ready: Boolean,
    minuteOfDay: Int,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    profilePercent: Int,
    onLowProfile: Boolean,
    ttActive: Boolean,
    steroidsOff: Boolean,
    nightHighTag: Boolean,
): String? {
    if (!ready || onLowProfile || ttActive || !steroidsOff) return null
    val block1 = if (nightHighTag) {
        minuteInWindow(minuteOfDay, 60, 6 * 60) && bg < 117.1 &&
            delta <= 0.0 && shortDelta <= 0.0 && longDelta <= 0.0 && profilePercent >= 100
    } else {
        minuteInWindow(minuteOfDay, 60, 6 * 60) && bg < 135.1 && delta <= -0.9 && profilePercent >= 100
    }
    val block2 = minuteInWindow(minuteOfDay, 5 * 60, 5 * 60 + 30) && bg <= 135.1 && profilePercent == 100
    return when {
        block1 -> "1"
        block2 -> "2"
        else -> null
    }
}

// From 22:00 until 06:00 the hypo prediction is not required.
// Earlier, a missing prediction stays open. A prediction under 5.0 mmol also opens it.
internal fun offHighShouldAct(minuteOfDay: Int, hp: Double?): Boolean =
    minuteInWindow(minuteOfDay, 22 * 60, 6 * 60) || hp == null || hp < 5.0

// 08:00 until 01:00. No temp target, and steroids off.
// The last meal event (carbs, or the last manual bolus while extended carbs are active) must be at least 40 minutes old.
// The caller sets 4.2 mmol for 5 minutes.
internal fun stuckRisingShouldRequest(
    ready: Boolean,
    minuteOfDay: Int,
    ttActive: Boolean,
    steroidsOff: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    cob: Double,
    iob: Double,
    steps60: Int,
    steps180: Int,
    mealAgeMinutes: Int,
): Boolean {
    if (!ready || ttActive || !steroidsOff) return false
    if (!minuteInWindow(minuteOfDay, 8 * 60, 60)) return false
    return slowRiseCriteriaMet(bg, delta, shortDelta, longDelta, cob, iob, steps60, steps180, mealAgeMinutes)
}

// 40 to 59 minutes after a meal event the wider delta bands are allowed as well as the gentle test.
// From 60 minutes on only the gentle test applies. (This replaced a delayed-bolus lookup on 2026-10-06.)
internal fun slowRiseWideBandsApply(mealAgeMinutes: Int): Boolean = mealAgeMinutes < 60

// Glucose 6.5 to 9.0 mmol, small carbs, IOB 1.0 to 5.5 (floor lowered from 1.2 on 2026-10-06, per explicit request).
// The meal event must be at least 40 minutes old.
// Gentle test: all three deltas above 0 and under 0.15 mmol. Wide test: all three in one common band of
// 0.15-0.25, 0.20-0.30 or 0.25-0.35 mmol, only while the meal event is under 60 minutes old.
internal fun slowRiseCriteriaMet(
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    cob: Double,
    iob: Double,
    steps60: Int,
    steps180: Int,
    mealAgeMinutes: Int,
): Boolean {
    if (listOf(bg, delta, shortDelta, longDelta, cob, iob).any { !it.isFinite() }) return false
    if (bg !in (6.5 * 18.0)..(9.0 * 18.0) || cob !in 0.0..8.0 || iob !in 1.0..5.5) return false
    if (steps60 !in 0 until 600 || steps180 !in 0 until 1000) return false
    if (mealAgeMinutes < 40) return false
    val slowCeiling = 0.15 * 18.0
    if (listOf(delta, shortDelta, longDelta).all { it > 0.0 && it < slowCeiling }) return true
    if (!slowRiseWideBandsApply(mealAgeMinutes)) return false
    return listOf(0.15 to 0.25, 0.20 to 0.30, 0.25 to 0.35).any { (low, high) ->
        listOf(delta, shortDelta, longDelta).all { it >= low * 18.0 && it <= high * 18.0 }
    }
}
