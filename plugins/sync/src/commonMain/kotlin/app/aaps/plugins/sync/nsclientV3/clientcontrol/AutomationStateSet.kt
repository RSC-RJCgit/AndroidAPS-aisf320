package app.aaps.plugins.sync.nsclientV3.clientcontrol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The states text with [value] as the current value of [name], in the same form the loop phone stores it. Null when
 * [name] is not a declared state or [value] is not one of its values, so a client cannot add a state or a value.
 * The other states are kept exactly as they are.
 */
internal fun automationStateSetJson(currentJson: String, valuesJson: String, name: String, value: String): String? {
    val trimmedName = name.trim()
    val trimmedValue = value.trim()
    val allowed = runCatching { Json.decodeFromString<Map<String, List<String>>>(valuesJson) }.getOrNull() ?: return null
    if (trimmedValue !in allowed[trimmedName].orEmpty()) return null
    val current = runCatching { Json.decodeFromString<Map<String, String>>(currentJson) }.getOrElse { emptyMap() }
    return Json.encodeToString(current + (trimmedName to trimmedValue))
}
