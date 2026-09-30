package app.aaps.core.objects.profile

import app.aaps.core.interfaces.profile.ProfileSource
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.StringKey
import org.json.JSONArray

/**
 * Ported 2026-09-30 from the KMP fork's CodedProfileRolesLogic.kt (AaAPS-v4-kmp-aisf321), which
 * already had this working (unit-tested there, not yet installed on that repo's own phone at time
 * of porting). Scope trimmed to the auto-claim + auto-fill-EMPTY-tiers path only -- the KMP
 * version's manual "Fill Tiers" replace-with-confirmation flow (for a tier that already points at
 * a real profile) is deliberately NOT ported yet; this file only ever creates a NEW profile for an
 * empty tier slot, never overwrites one that already has a profile assigned.
 */

/** A name that contains "steroid" or "%" is a steroid profile, not a Standard or Low one. */
fun isSteroidMarkedProfileName(name: String): Boolean =
    name.contains("steroid", ignoreCase = true) || name.contains("%")

/** 105 and 110 are checked before 100, then the Low numbers. A steroid name matches none. */
fun tierNumberInName(name: String): Int? {
    if (isSteroidMarkedProfileName(name)) return null
    fun has(number: Int) = Regex("(?<!\\d)$number(?!\\d)").containsMatchIn(name)
    return listOf(105, 110, 70, 80, 90, 100).firstOrNull { has(it) }
}

fun rolePointsAtStoredProfile(storedName: String, profileNames: Set<String>): Boolean {
    val name = storedName.trim()
    return name.isNotEmpty() && name in profileNames
}

/**
 * What to write when a plain profile is activated (no role chosen) and Standard tier A is empty.
 * [currentStandard] is set only when that role is also empty -- the loop reads that role, so an
 * empty one would otherwise leave nothing running. Null means leave the live role untouched.
 */
data class StandardTierAClaim(
    val tierA: String,
    val currentStandard: String?,
)

/**
 * A plain activated name can become Standard tier A when no role was chosen and tier A is empty.
 * A name that already says 70, 80, 90, 100, 105, 110, or steroid, is left alone -- it's already
 * either a generated tier profile or a steroid profile, not a fresh base to claim.
 */
fun claimStandardTierA(
    profileName: String,
    chosenKey: StringKey?,
    tierAName: String,
    currentStandardName: String,
    profileNames: Set<String>,
): StandardTierAClaim? {
    val name = profileName.trim()
    if (name.isEmpty() || chosenKey != null) return null
    if (isSteroidMarkedProfileName(name) || tierNumberInName(name) != null) return null
    if (rolePointsAtStoredProfile(tierAName, profileNames)) return null
    val current = if (rolePointsAtStoredProfile(currentStandardName, profileNames)) null else name
    return StandardTierAClaim(tierA = name, currentStandard = current)
}

/** One tier copied from Standard tier A. [percent] is the real scaling factor stored, not the slot's own label
 *  (e.g. the "Standard105" slot's real percent is 130, matching real device data: autoisf_standard105_profile_name
 *  has always resolved to a profile named "Profile130", never a 105% one). */
data class TierProfileSpec(
    val key: StringKey,
    val percent: Int,
    val defaultName: String,
)

data class TierWrite(
    val key: StringKey,
    val percent: Int,
    val name: String,
)

data class TierFillPlan(
    val sourceMissing: Boolean,
    val sourceName: String,
    val creates: List<TierWrite>,
)

fun tierProfileSpecs(): List<TierProfileSpec> = listOf(
    TierProfileSpec(StringKey.ApsAutoIsfStandard105ProfileName, 130, "Standard tier B"),
    TierProfileSpec(StringKey.ApsAutoIsfStandard110ProfileName, 150, "Standard tier C"),
    TierProfileSpec(StringKey.ApsAutoIsfLow70ProfileName, 80, "Low tier A"),
    TierProfileSpec(StringKey.ApsAutoIsfLow80ProfileName, 90, "Low tier B"),
    TierProfileSpec(StringKey.ApsAutoIsfLow90ProfileName, 95, "Low tier C"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid100ProfileName, 100, "Steroid tier A"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid110ProfileName, 110, "Steroid Profile110"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid130ProfileName, 130, "Steroid Profile130"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid150ProfileName, 150, "Steroid Profile150"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid190ProfileName, 190, "Steroid190"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid250ProfileName, 250, "Steroid250"),
)

/**
 * Decide which EMPTY tiers to create from the Standard tier A source. A tier that already points
 * at a real, existing profile is left untouched entirely -- no replace path here (see file doc comment).
 */
fun planTierFill(roleValues: Map<StringKey, String>, profileNames: Set<String>): TierFillPlan {
    val sourceName = roleValues[StringKey.ApsAutoIsfStandard100ProfileName].orEmpty().trim()
    if (sourceName.isEmpty() || sourceName !in profileNames) {
        return TierFillPlan(sourceMissing = true, sourceName = sourceName, creates = emptyList())
    }
    val taken = profileNames.toMutableSet()
    val creates = mutableListOf<TierWrite>()
    tierProfileSpecs().forEach { spec ->
        val current = roleValues[spec.key].orEmpty().trim()
        val hasProfile = current.isNotEmpty() && current in profileNames
        if (!hasProfile) {
            val name = freeProfileName(current.ifEmpty { spec.defaultName }, taken)
            taken += name
            creates += TierWrite(spec.key, spec.percent, name)
        }
    }
    return TierFillPlan(sourceMissing = false, sourceName = sourceName, creates = creates)
}

/**
 * Basal is multiplied by the percent, then rounded to 0.05 U/h (0.10 for a Low tier).
 * Sensitivity and the carb ratio are divided by the percent, then rounded to one decimal place,
 * same as both targets (left unscaled -- targets stay the same across tiers, just re-rounded).
 */
fun scaledTierProfile(source: ProfileSource.SingleProfile, percent: Int, name: String, roleKey: StringKey): ProfileSource.SingleProfile {
    val up = percent / 100.0
    val down = 100.0 / percent
    val basalStep = if (roleKey.key.startsWith("autoisf_low")) 0.10 else 0.05
    fun scaled(array: JSONArray, factor: Double, step: Double): JSONArray {
        val out = JSONArray(array.toString())
        for (i in 0 until out.length()) {
            val obj = out.getJSONObject(i)
            obj.put("value", Round.roundTo(obj.getDouble("value") * factor, step))
        }
        return out
    }
    val clone = source.deepClone()
    clone.name = name
    clone.basal = scaled(source.basal, up, basalStep)
    clone.isf = scaled(source.isf, down, 0.1)
    clone.ic = scaled(source.ic, down, 0.1)
    clone.targetLow = scaled(source.targetLow, 1.0, 0.1)
    clone.targetHigh = scaled(source.targetHigh, 1.0, 0.1)
    return clone
}

private fun freeProfileName(preferred: String, taken: Set<String>): String {
    if (preferred !in taken) return preferred
    var n = 2
    while ("$preferred $n" in taken) n++
    return "$preferred $n"
}
