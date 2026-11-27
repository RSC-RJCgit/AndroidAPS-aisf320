package app.aaps.core.objects.wizard

import kotlin.math.abs

/** Smallest and largest resize (2026-11-28, per explicit request, same as 3426): Low Profile90 vs Standard x0.68, Profile190 x1.41. */
const val IC_RESIZE_MIN = 0.65
const val IC_RESIZE_MAX = 1.5

/**
 * Resize of a pending follow-up part when the profile changed after the bolus (2026-11-28, per explicit request, same as 3426).
 * The part was sized at [sizingIc] (the IC its units came from, at 100%) on [sizingProfile]; [runningIc] is the running
 * profile's IC at 100% at the same time of day. Returns sizingIc / runningIc, kept within [IC_RESIZE_MIN]..[IC_RESIZE_MAX], or
 * 1.0 when the profile is the same, a value is missing (a part saved before this change, or the Tier 3 automation's series), or
 * [upOnly] and the running profile is weaker. [upOnly] is for the delayed checks sized at the Standard IC (2026-11-14 rule:
 * once safe, the meal is dosed as on Standard), so a Low profile does not shrink them, while a stronger or Steroid profile still
 * makes them bigger.
 */
fun profileIcResizeFactor(sizingProfile: String, sizingIc: Double, runningProfile: String, runningIc: Double, upOnly: Boolean = false): Double {
    if (sizingProfile.isBlank() || runningProfile.isBlank() || sizingProfile.trim() == runningProfile.trim()) return 1.0
    if (!(sizingIc > 0.0) || !(runningIc > 0.0) || !sizingIc.isFinite() || !runningIc.isFinite()) return 1.0
    val factor = (sizingIc / runningIc).coerceIn(IC_RESIZE_MIN, IC_RESIZE_MAX)
    if (upOnly && factor < 1.0) return 1.0
    return if (abs(factor - 1.0) < 0.005) 1.0 else factor
}

/** The note text for a resize, or "" when there is none. */
fun profileIcResizeNote(factor: Double, sizingProfile: String, runningProfile: String): String {
    if (factor == 1.0) return ""
    val cents = kotlin.math.round(factor * 100.0).toLong()
    return " Resized x${cents / 100}.${(cents % 100).toString().padStart(2, '0')} for the profile change ($sizingProfile -> $runningProfile)."
}
