package app.aaps.core.objects.wizard

import app.aaps.core.data.configuration.Constants
import kotlin.math.min
import kotlinx.serialization.json.Json

/** Above 100% the calculator uses the 100% carb ratio and sensitivity. At or below 100% it does not scale. */
internal fun wizardProfileBaseScale(activePercent: Int): Double =
    if (activePercent > 100) activePercent / 100.0 else 1.0

/**
 * Halve carb insulin only.
 * Closed when the profile is already 50%, because that path already uses the weaker rates.
 * Needs a recent-50 state, or a wizard glucose under 5.0 mmol/L that is not rising,
 * and glucose under 6.5 mmol/L with all three deltas falling by more than 0.1 mmol/L.
 * Glucose values and deltas are mg/dL.
 */
internal fun recent50ShouldHalveCarbs(
    profilePercent: Int,
    lowBgRecent: Boolean,
    wizardBgMgdl: Double,
    glucoseMgdl: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    hasGlucose: Boolean,
): Boolean {
    if (profilePercent == 50 || !hasGlucose) return false
    val lowAndFlat = lowBgRecent || (wizardBgMgdl > 0.0 && wizardBgMgdl < 90.0 && delta <= 0.0)
    if (!lowAndFlat) return false
    val bgMgdl = if (wizardBgMgdl > 0.0) wizardBgMgdl else glucoseMgdl
    val falling = -0.1 * Constants.MMOLL_TO_MGDL
    return bgMgdl < 6.5 * Constants.MMOLL_TO_MGDL &&
        delta < falling &&
        shortDelta < falling &&
        longDelta < falling
}

internal fun lowBgIsRecent50(statesEnabled: Boolean, currentStatesJson: String): Boolean {
    if (!statesEnabled) return false
    val current = runCatching { Json.decodeFromString<Map<String, String>>(currentStatesJson) }.getOrElse { emptyMap() }
    return current["LowBG"] == "50recent"
}

/**
 * Projected glucose in mmol/L after this dose. Null when the wizard has no glucose.
 * [bgMgdl], [deltaMgdl] and [shortDeltaMgdl] are mg/dL. Insulin values are units.
 */
internal fun wizardProjectedHp(
    bgMgdl: Double,
    positiveIob: Double,
    dose: Double,
    deltaMgdl: Double,
    shortDeltaMgdl: Double,
): Double? {
    if (bgMgdl <= 0.0) return null
    val deltaMmol = deltaMgdl * Constants.MGDL_TO_MMOLL
    val shortMmol = shortDeltaMgdl * Constants.MGDL_TO_MMOLL
    return bgMgdl * Constants.MGDL_TO_MMOLL - positiveIob - dose +
        0.25 * min(deltaMmol, 0.0) +
        0.25 * min(shortMmol, 0.0)
}

internal data class WizardHpCut(
    val applied: Boolean,
    val dose: Double,
    val cobRemoved: Double,
)

/**
 * Remove carb-on-board insulin, then take 25% off the rest.
 * Only when the projected value is at or under 6.5 mmol/L, wizard glucose is under 10,
 * both deltas are at least 0.6 mmol/L, positive IOB is at least 2 U, and the dose is at least 0.75 U.
 * Newly entered carbs are not removed.
 */
internal fun wizardHpCut(
    dose: Double,
    insulinFromCob: Double,
    percentage: Double,
    projectedHp: Double?,
    bgMmol: Double,
    deltaMmol: Double,
    shortDeltaMmol: Double,
    positiveIob: Double,
): WizardHpCut {
    val strongRise = deltaMmol >= 0.6 && shortDeltaMmol >= 0.6
    if (projectedHp == null || projectedHp > 6.5 || bgMmol >= 10.0 || !strongRise || positiveIob < 2.0 || dose < 0.75) {
        return WizardHpCut(applied = false, dose = dose, cobRemoved = 0.0)
    }
    val cobRemoved = (insulinFromCob * percentage / 100.0).coerceAtLeast(0.0)
    val adjusted = ((dose - cobRemoved).coerceAtLeast(0.0) * 0.75)
    return WizardHpCut(applied = true, dose = adjusted, cobRemoved = cobRemoved)
}

internal fun walkingSoonImmediatePercent(walkingSoon: Boolean, standingPct: Double): Double {
    if (!walkingSoon) return standingPct
    return minOf(standingPct, 70.0)
}

/**
 * Insulin the walking-soon cut holds back from the first bolus.
 * It is given later by the usual delayed-carb checks. [scaledComponents] is units, before the percent.
 */
internal fun walkingSoonHeldUnits(walkingSoon: Boolean, standingPct: Double, scaledComponents: Double): Double {
    if (!walkingSoon || scaledComponents <= 0.0) return 0.0
    val percentCut = standingPct - walkingSoonImmediatePercent(true, standingPct)
    if (percentCut <= 0.5) return 0.0
    return scaledComponents * percentCut / 100.0
}

/**
 * Default for the Walking soon box. Steps over 5 minutes at 100 or more, or steps over 30 minutes at
 * 200 or more while steps over 15 or 5 minutes are above 0 (S30 alone lingers about 25 minutes after
 * you stop). A missing 15-minute count keeps the older 30-minute-only test. Also glucose under
 * 6 mmol/L and not rising fast. Null when the 5 or 30 minute counts are missing, so the box is left as it is.
 */
fun walkingSoonDefault(steps5: Int?, steps30: Int?, bgMgdl: Double, deltaMgdl: Double, steps15: Int? = null): Boolean? {
    if (steps5 == null || steps30 == null) return null
    val moving = steps5 >= 100 || (steps30 >= 200 && (steps15 == null || steps15 > 0 || steps5 > 0))
    val low = bgMgdl < 108.1
    val notRisingFast = deltaMgdl <= 0.9
    return moving && low && notRisingFast
}

/**
 * Multiply the dose by 1.33 when wizard glucose is above 8 mmol/L and all three deltas are above 0.2 mmol/L.
 * Returns null when the boost does not apply. A walking-soon cut blocks it.
 */
internal fun wizardRiseBoost(
    dose: Double,
    walkingSoonCut: Boolean,
    bgMmol: Double,
    deltaMmol: Double,
    shortDeltaMmol: Double,
    longDeltaMmol: Double,
): Double? {
    if (dose <= 0.0 || walkingSoonCut) return null
    if (bgMmol > 8.0 && deltaMmol > 0.2 && shortDeltaMmol > 0.2 && longDeltaMmol > 0.2) return dose * 1.33
    return null
}
