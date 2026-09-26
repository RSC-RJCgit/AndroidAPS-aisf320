package app.aaps.plugins.aps.openAPSAutoISF

internal data class PersistentRiseClock(
    val startedAt: Long,
    val persistentMinutes: Double,
)

// Minutes use the start time from before this cycle. A new run begins at [now] and reports 0 minutes.
// A broken run reports the old length and stores 0, so the next cycle starts clean.
internal fun persistentRiseClock(holding: Boolean, startedAt: Long, now: Long): PersistentRiseClock {
    val nextStart = when {
        holding && startedAt == 0L -> now
        !holding -> 0L
        else -> startedAt
    }
    val minutes = if (startedAt > 0L) (now - startedAt) / 60_000.0 else 0.0
    return PersistentRiseClock(startedAt = nextStart, persistentMinutes = minutes)
}

// A mild rise with no SMB for 10 minutes, from 08:30 until midnight, not on the Low Current profile.
// A new pod over 9.0 mmol/L, or a recent Usual2, can open that window early.
internal fun persistentRiseShouldFire(
    ready: Boolean,
    boostOn: Boolean,
    minuteOfDay: Int,
    daytimeBypass: Boolean,
    holding: Boolean,
    persistentMinutes: Double,
    onLowCurrent: Boolean,
    mjActive: Boolean,
    steps5: Int,
    steps30: Int,
): Boolean {
    if (!ready || !boostOn || !holding || onLowCurrent || mjActive) return false
    if (persistentMinutes < 10.0) return false
    if (steps5 > 100 || steps30 > 200) return false
    val daytime = minuteInWindow(minuteOfDay, 8 * 60 + 30, 0)
    return daytime || daytimeBypass
}
