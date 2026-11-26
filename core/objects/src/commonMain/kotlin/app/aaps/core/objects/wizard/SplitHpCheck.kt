package app.aaps.core.objects.wizard

enum class SplitHpCheck { Wait, Hold, Allow }

fun splitHpCheck(hpMilliMmol: Long, measuredAt: Long, now: Long): SplitHpCheck {
    if (measuredAt <= 0L || measuredAt > now || now - measuredAt > 300_000L) return SplitHpCheck.Wait
    return if (hpMilliMmol < 4_000L) SplitHpCheck.Hold else SplitHpCheck.Allow
}

/**
 * 2026-11-26, per explicit request (same as 3426): the insulinReq cap on carb-split and protein/fat parts is skipped while BG
 * is 7.5 mmol or more and a fresh HP1 is 6.0 mmol or more. 26 Nov Live: a 1.30 U carb split waited at BG 12.6-12.8 and HP1
 * 8.3 because insulinReq read 0 right after a 5.80 U bolus. Below 7.5, HP1 under 6.0, or HP1 missing or stale, the cap stays.
 */
fun insReqCapWaived(glucoseMgdl: Double?, hpMilliMmol: Long, measuredAt: Long, now: Long): Boolean {
    if (glucoseMgdl == null || measuredAt <= 0L || measuredAt > now || now - measuredAt > 300_000L) return false
    return glucoseMgdl >= 135.1 /* 7.5 mmol */ && hpMilliMmol >= 6_000L
}
