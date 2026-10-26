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
internal fun mcapSmbCap30Min(strengthPercent: Double, walking: Boolean): Double =
    2.1 * 100.0 / strengthPercent.coerceAtLeast(100.0) * (if (walking) 0.5 else 1.0)
