package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Profile strength in % for the Mcap SMB cap (2026-10-26, per explicit request): 100 = the Standard Tier A profile (the
 * Standard 100 setting, else the Standard role). From the real values: its ISF divided by the current ISF (both now, the
 * current one with its percentage). Only when one of them is missing, from the name: the number in it times the
 * percentage ("Profile150" at 100% = 150).
 */
internal fun profileStrengthPercent(referenceIsfMgdl: Double?, currentIsfMgdl: Double?, profileName: String, percentage: Int): Double {
    if (referenceIsfMgdl != null && currentIsfMgdl != null && referenceIsfMgdl > 0.0 && currentIsfMgdl > 0.0)
        return referenceIsfMgdl / currentIsfMgdl * 100.0
    val fromName = Regex("""\d+""").find(profileName)?.value?.toDoubleOrNull() ?: 100.0
    return fromName * percentage / 100.0
}

/**
 * The 30 min SMB cap for Mcap (2026-10-26, per explicit request): 2.1 U at 100% or weaker, smaller for a stronger profile
 * (2.1 x 100 / strength: 1.6 U at 130%, 1.4 U at 150%), and half that while walking. Replayed over 5-26 Oct: 4 U more held
 * back before lows by the profile scaling and 2 U more by the walking half, none before a BG above 12 within 3 hours.
 */
/** Older hourly steps alone must not halve the cap after recent activity has stopped. */
internal fun mcapWalking(steps5: Int, steps30: Int): Boolean = steps5 > 20 || steps30 > 100

internal fun mcapSmbCap30Min(strengthPercent: Double, walking: Boolean, walkingScale: Double = 0.5): Double =
    2.1 * 100.0 / strengthPercent.coerceAtLeast(100.0) * (if (walking) walkingScale else 1.0)

/**
 * Walking cut on a confirmed rise (2026-11-24, per explicit request): what walking multiplies the 30 min SMB cap and the
 * activity factor by. 0.5 as before; 0.75 at BG >= 8.0 mmol and 1.0 (no cut) at BG >= 10.0 mmol, but only while rising
 * (Delta and short average delta both >= 0.15 mmol) and HP2 > 6.0 mmol. 24 Nov Live: BG 6.2 -> 11.4 over 1.5 h of walking
 * (S5 117-541) with the walking-halved cap at 0.65 U / 30 min on Profile150 (162%), Req 2-6 U and almost no SMB.
 * The halving stays below 8.0, when not rising, and when HP2 is 6.0 or less (17 Oct walking low: BG 9.9 -> 3.6).
 */
internal fun walkingRiseScale(bgMgdl: Double, deltaMgdl: Double, shortDeltaMgdl: Double, hp2Mmol: Double?): Double {
    val rising = deltaMgdl >= 2.7 && shortDeltaMgdl >= 2.7
    if (!rising || hp2Mmol == null || hp2Mmol <= 6.0) return 0.5
    return when {
        bgMgdl >= 180.2 -> 1.0   // 10.0 mmol
        bgMgdl >= 144.1 -> 0.75  // 8.0 mmol
        else            -> 0.5
    }
}
