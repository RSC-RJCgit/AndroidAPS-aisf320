package app.aaps.ui.compose.profileManagement

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
