package app.aaps.core.objects.wizard

enum class SplitHpCheck { Wait, Hold, Allow }

fun splitHpCheck(hpMilliMmol: Long, measuredAt: Long, now: Long): SplitHpCheck {
    if (measuredAt <= 0L || measuredAt > now || now - measuredAt > 300_000L) return SplitHpCheck.Wait
    return if (hpMilliMmol < 4_000L) SplitHpCheck.Hold else SplitHpCheck.Allow
}

/**
 * 2026-11-26, per explicit request (same as 3426): the insulinReq cap on carb-split and protein/fat parts is skipped while BG
 * is 7.5 mmol or more and a fresh HP1 is 6.0 mmol or more. 26 Nov Live: a 1.30 U carb split waited at BG 12.6-12.8 and HP1
 * 8.3 because insulinReq read 0 right after a 5.80 U bolus. Below the BG limit, HP1 under 6.0, or HP1 missing or stale, the cap stays.
 * 2026-11-27, per explicit request (same as 3426): BG 6.5 mmol or more (was 7.5), and the delayed bolus checks use it too.
 */
fun insReqCapWaived(glucoseMgdl: Double?, hpMilliMmol: Long, measuredAt: Long, now: Long): Boolean {
    if (glucoseMgdl == null || measuredAt <= 0L || measuredAt > now || now - measuredAt > 300_000L) return false
    return glucoseMgdl >= 117.1 /* 6.5 mmol */ && hpMilliMmol >= 6_000L
}

/**
 * 2026-11-27, per explicit request (same as 3426): the delayed bolus is given in full (no insulinReq cap) at the first check after
 * the hypo wait ends: BG 6.0 mmol or more, both deltas rising, and a fresh HP1 of 6.0 or more. 27 Nov Live: released at Db15
 * (12:44) but 1.55 of 2.04 U came over 50 min, InsReq-capped to 0.06-0.2 U after the SMBs.
 */
fun delayedFullRelease(glucoseMgdl: Double?, deltaMgdl: Double?, shortDeltaMgdl: Double?, hpMilliMmol: Long, measuredAt: Long, now: Long): Boolean {
    if (glucoseMgdl == null || deltaMgdl == null || shortDeltaMgdl == null) return false
    if (measuredAt <= 0L || measuredAt > now || now - measuredAt > 300_000L) return false
    return glucoseMgdl >= 108.1 /* 6.0 mmol */ && deltaMgdl > 0.0 && shortDeltaMgdl > 0.0 && hpMilliMmol >= 6_000L
}

/**
 * 2026-11-27, per explicit request (same as 3426): a high fast rise (BG over 8.5 mmol, Delta >= +0.2 and SDelta >= +0.15 mmol per
 * 5 min, the loop's StepBrakesOff release) takes the steps cut off the delayed bolus, carb-split and protein/fat parts.
 * 27 Nov Live 13:39: fpu2 cut x0.25 for heavy activity at BG 12.4 and rising.
 */
fun followUpHighFastRise(glucoseMgdl: Double?, deltaMgdl: Double?, shortDeltaMgdl: Double?): Boolean =
    glucoseMgdl != null && deltaMgdl != null && shortDeltaMgdl != null &&
        glucoseMgdl > 153.1 /* 8.5 mmol */ && deltaMgdl >= 3.6 /* 0.2 mmol */ && shortDeltaMgdl >= 2.7 /* 0.15 mmol */
