package app.aaps.ui.compose.profileManagement

import app.aaps.core.interfaces.profile.SingleProfile
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.StringKey

/**
 * One coded profile role. [key] is the preference name.
 * [optional] roles may be left empty. [steroidsOff] roles are Standard and Low, not the steroid tiers.
 */
data class CodedProfileSlot(
    val key: String,
    val optional: Boolean,
    val steroidsOff: Boolean,
)

data class CodedProfileSave(
    val writes: Map<String, String>,
    val blocked: Int,
    val steroidsOff: Boolean,
)

fun stringKeyForCodedRole(key: String): StringKey = when (key) {
    StringKey.ApsAutoIsfStandardProfileName.key -> StringKey.ApsAutoIsfStandardProfileName
    StringKey.ApsAutoIsfLowProfileName.key -> StringKey.ApsAutoIsfLowProfileName
    StringKey.ApsAutoIsfStandard100ProfileName.key -> StringKey.ApsAutoIsfStandard100ProfileName
    StringKey.ApsAutoIsfStandard105ProfileName.key -> StringKey.ApsAutoIsfStandard105ProfileName
    StringKey.ApsAutoIsfStandard110ProfileName.key -> StringKey.ApsAutoIsfStandard110ProfileName
    StringKey.ApsAutoIsfLow70ProfileName.key -> StringKey.ApsAutoIsfLow70ProfileName
    StringKey.ApsAutoIsfLow80ProfileName.key -> StringKey.ApsAutoIsfLow80ProfileName
    StringKey.ApsAutoIsfLow90ProfileName.key -> StringKey.ApsAutoIsfLow90ProfileName
    StringKey.ApsAutoIsfSteroid100ProfileName.key -> StringKey.ApsAutoIsfSteroid100ProfileName
    StringKey.ApsAutoIsfSteroid110ProfileName.key -> StringKey.ApsAutoIsfSteroid110ProfileName
    StringKey.ApsAutoIsfSteroid130ProfileName.key -> StringKey.ApsAutoIsfSteroid130ProfileName
    StringKey.ApsAutoIsfSteroid150ProfileName.key -> StringKey.ApsAutoIsfSteroid150ProfileName
    StringKey.ApsAutoIsfSteroid190ProfileName.key -> StringKey.ApsAutoIsfSteroid190ProfileName
    StringKey.ApsAutoIsfSteroid250ProfileName.key -> StringKey.ApsAutoIsfSteroid250ProfileName
    else -> error("Unknown coded profile role: $key")
}

fun codedProfileSlots(): List<CodedProfileSlot> = listOf(
    CodedProfileSlot("autoisf_standard_profile_name", optional = false, steroidsOff = true),
    CodedProfileSlot("autoisf_low_profile_name", optional = false, steroidsOff = true),
    CodedProfileSlot("autoisf_standard100_profile_name", optional = true, steroidsOff = true),
    CodedProfileSlot("autoisf_standard105_profile_name", optional = true, steroidsOff = true),
    CodedProfileSlot("autoisf_standard110_profile_name", optional = true, steroidsOff = true),
    CodedProfileSlot("autoisf_low70_profile_name", optional = true, steroidsOff = true),
    CodedProfileSlot("autoisf_low80_profile_name", optional = true, steroidsOff = true),
    CodedProfileSlot("autoisf_low90_profile_name", optional = true, steroidsOff = true),
    CodedProfileSlot("autoisf_steroid_100_profile_name", optional = false, steroidsOff = false),
    CodedProfileSlot("autoisf_steroid_110_profile_name", optional = false, steroidsOff = false),
    CodedProfileSlot("autoisf_steroid_130_profile_name", optional = false, steroidsOff = false),
    CodedProfileSlot("autoisf_steroid_150_profile_name", optional = false, steroidsOff = false),
    CodedProfileSlot("autoisf_steroid_190_profile_name", optional = false, steroidsOff = false),
    CodedProfileSlot("autoisf_steroid_250_profile_name", optional = false, steroidsOff = false),
)

/** A name that contains "steroid" or "%" is a steroid profile, not a Standard or Low one. */
fun isSteroidMarkedProfileName(name: String): Boolean =
    name.contains("steroid", ignoreCase = true) || name.contains("%")

/**
 * When a profile switch name itself says which steroid tier it is, that tier wins.
 * A name without the marker returns null, and the user's chosen role is used.
 */
fun steroidSlotKeyForName(name: String): String? {
    if (!isSteroidMarkedProfileName(name)) return null
    fun has(number: Int) = Regex("(?<!\\d)$number(?!\\d)").containsMatchIn(name)
    return when {
        has(250) -> "autoisf_steroid_250_profile_name"
        has(190) -> "autoisf_steroid_190_profile_name"
        has(150) -> "autoisf_steroid_150_profile_name"
        has(130) -> "autoisf_steroid_130_profile_name"
        has(110) -> "autoisf_steroid_110_profile_name"
        has(100) -> "autoisf_steroid_100_profile_name"
        else -> null
    }
}

/**
 * Plan the writes for the full role list.
 * A steroid-marked name is refused for a Standard or Low slot and left as it was.
 */
fun planCodedProfileSave(
    slots: List<CodedProfileSlot>,
    selected: List<String>,
    previous: Map<String, String>,
): CodedProfileSave {
    val writes = linkedMapOf<String, String>()
    var blocked = 0
    var steroidsOff = false
    slots.forEachIndexed { index, slot ->
        val value = selected.getOrElse(index) { "" }
        if (slot.steroidsOff && value.isNotEmpty() && isSteroidMarkedProfileName(value)) {
            if (previous[slot.key].orEmpty() != value) blocked++
            return@forEachIndexed
        }
        writes[slot.key] = value
        if (slot.steroidsOff && previous[slot.key].orEmpty() != value) steroidsOff = true
    }
    return CodedProfileSave(writes, blocked, steroidsOff)
}

/**
 * Plan the one role written from the profile switch screen.
 * A steroid-marked name is stored on its steroid tier even if another role was picked.
 * [chosenKey] null means no role change, unless the name itself picks a steroid tier.
 */
fun planSwitchRole(profileName: String, chosenKey: String?): CodedProfileSave {
    val routed = steroidSlotKeyForName(profileName)
    val key = routed ?: chosenKey ?: return CodedProfileSave(emptyMap(), 0, false)
    val slot = codedProfileSlots().firstOrNull { it.key == key } ?: return CodedProfileSave(emptyMap(), 0, false)
    val steroidsOff = routed == null && slot.steroidsOff
    return CodedProfileSave(mapOf(key to profileName), blocked = 0, steroidsOff = steroidsOff)
}

/** Preference that holds the profile every other tier is scaled from. */
const val STANDARD_TIER_A_KEY = "autoisf_standard100_profile_name"

/**
 * One tier copied from Standard tier A.
 * [percent] is applied to that profile as it is stored: basal rises, insulin sensitivity and the carb ratio fall, targets stay.
 * [defaultName] is used only when the role is empty.
 */
data class TierProfileSpec(
    val key: String,
    val percent: Int,
    val defaultName: String,
)

/**
 * One profile to write.
 * [replaceInPlace] overwrites the profile that already has [name]. Otherwise [name] is a new profile.
 */
data class TierWrite(
    val key: String,
    val percent: Int,
    val name: String,
    val replaceInPlace: Boolean,
)

data class TierFillPlan(
    val sourceMissing: Boolean,
    val sourceName: String,
    val creates: List<TierWrite>,
    val replaces: List<TierWrite>,
)

fun tierProfileSpecs(): List<TierProfileSpec> = listOf(
    TierProfileSpec(StringKey.ApsAutoIsfStandard105ProfileName.key, 130, "Standard tier B"),
    TierProfileSpec(StringKey.ApsAutoIsfStandard110ProfileName.key, 150, "Standard tier C"),
    TierProfileSpec(StringKey.ApsAutoIsfLow70ProfileName.key, 80, "Low tier A"),
    TierProfileSpec(StringKey.ApsAutoIsfLow80ProfileName.key, 90, "Low tier B"),
    TierProfileSpec(StringKey.ApsAutoIsfLow90ProfileName.key, 95, "Low tier C"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid100ProfileName.key, 100, "Steroid tier A"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid110ProfileName.key, 110, "Steroid Profile110"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid130ProfileName.key, 130, "Steroid Profile130"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid150ProfileName.key, 150, "Steroid Profile150"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid190ProfileName.key, 190, "Steroid190"),
    TierProfileSpec(StringKey.ApsAutoIsfSteroid250ProfileName.key, 250, "Steroid250"),
)

/**
 * Decide which tiers to add and which already have a profile.
 * A role that points at the Standard tier A profile is not overwritten in place. A new copy is offered instead.
 */
fun planTierFill(roleValues: Map<String, String>, profileNames: Set<String>): TierFillPlan {
    val sourceName = roleValues[STANDARD_TIER_A_KEY].orEmpty().trim()
    if (sourceName.isEmpty() || sourceName !in profileNames) {
        return TierFillPlan(sourceMissing = true, sourceName = sourceName, creates = emptyList(), replaces = emptyList())
    }
    val taken = profileNames.toMutableSet()
    val creates = mutableListOf<TierWrite>()
    val replaces = mutableListOf<TierWrite>()
    tierProfileSpecs().forEach { spec ->
        val current = roleValues[spec.key].orEmpty().trim()
        val hasProfile = current.isNotEmpty() && current in profileNames
        if (hasProfile && current != sourceName) {
            replaces += TierWrite(spec.key, spec.percent, current, replaceInPlace = true)
        } else if (hasProfile) {
            val name = freeProfileName(spec.defaultName, taken)
            taken += name
            replaces += TierWrite(spec.key, spec.percent, name, replaceInPlace = false)
        } else {
            val name = freeProfileName(current.ifEmpty { spec.defaultName }, taken)
            taken += name
            creates += TierWrite(spec.key, spec.percent, name, replaceInPlace = false)
        }
    }
    return TierFillPlan(sourceMissing = false, sourceName = sourceName, creates = creates, replaces = replaces)
}

/** Basal is multiplied by the percent. Sensitivity and the carb ratio are divided by it. Targets are copied. */
fun scaledTierProfile(source: SingleProfile, percent: Int, name: String): SingleProfile {
    val up = percent / 100.0
    val down = 100.0 / percent
    return source.copy(
        name = name,
        basal = source.basal.map { it.copy(amount = Round.roundTo(it.amount * up, 0.001)) },
        isf = source.isf.map { it.copy(amount = Round.roundTo(it.amount * down, 0.001)) },
        ic = source.ic.map { it.copy(amount = Round.roundTo(it.amount * down, 0.001)) },
    )
}

private fun freeProfileName(preferred: String, taken: Set<String>): String {
    if (preferred !in taken) return preferred
    var n = 2
    while ("$preferred $n" in taken) n++
    return "$preferred $n"
}
