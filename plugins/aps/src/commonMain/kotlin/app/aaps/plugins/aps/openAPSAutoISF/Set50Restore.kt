package app.aaps.plugins.aps.openAPSAutoISF

/**
 * 2026-11-27, per explicit request: where the end of a Set50 hypo 50% goes (PP50.Off, Usual2). Set50 drops to the Low role at 50%;
 * when it fired while a Standard-role profile ran ([markAt], under 12 hours ago) and a Low-role profile still runs:
 * - 06:00-22:00: back to the current Standard role at 100% ([Standard]);
 * - 22:00-06:00: stay on Low as before, the overnight rule, and keep the mark for the first daytime restore ([KeepLow]).
 * Otherwise [None]: the restore works as before. 27 Nov Live: Low Tier A 07:18-08:42 at BG 7-9.8 until a manual switch.
 * 2026-11-28, per explicit request: a Set50 from a Steroid tier profile is marked too (that profile at 50% counts as still dropped,
 * runningLowRole = true), and [Standard] resumes the exact profile saved at Set50 - the Steroid profile, or the Standard of the
 * tier that ran (B included) - rather than the current Standard role.
 */
internal enum class Set50Restore { None, Standard, KeepLow }

internal fun set50Restore(markAt: Long, now: Long, runningLowRole: Boolean, minuteOfDay: Int): Set50Restore = when {
    markAt <= 0L || markAt > now || now - markAt > SET50_RESTORE_MAX_MS || !runningLowRole -> Set50Restore.None
    minuteOfDay >= 22 * 60 || minuteOfDay < 6 * 60                                        -> Set50Restore.KeepLow
    else                                                                                   -> Set50Restore.Standard
}

internal const val SET50_RESTORE_MAX_MS = 12 * 3_600_000L
