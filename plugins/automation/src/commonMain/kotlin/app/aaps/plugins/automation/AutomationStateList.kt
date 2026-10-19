package app.aaps.plugins.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One declared automation state: its name, the value it holds now (empty when none) and the values it may hold. */
data class AutomationStateEntry(val name: String, val current: String, val values: List<String>)

/** The two stored texts after a change: the current values and the declared values. */
data class AutomationStateTexts(val currentJson: String, val valuesJson: String)

/**
 * The states the loop has declared, in name order, with the value each one holds now and the values it may hold.
 * A name with no current value is still listed, and its value is empty. Broken saved text becomes an empty list.
 */
fun automationStateEntries(currentJson: String, valuesJson: String): List<AutomationStateEntry> {
    val current = decodeStringMap(currentJson)
    val allowed = decodeStringLists(valuesJson)
    return allowed.keys.sorted().map { name -> AutomationStateEntry(name, current[name].orEmpty(), allowed.getValue(name)) }
}

/**
 * Makes [value] the current value of [name] (2026-10-19, the editable States dialog). Null when [name] is not declared
 * or [value] is not one of its values, so nothing is saved.
 */
fun setAutomationStateCurrent(currentJson: String, valuesJson: String, name: String, value: String): AutomationStateTexts? {
    val allowed = decodeStringLists(valuesJson)
    if (value !in allowed[name].orEmpty()) return null
    val current = decodeStringMap(currentJson) + (name to value)
    return AutomationStateTexts(encodeStringMap(current), valuesJson)
}

/**
 * Declares [name] with [values], or changes its values. A current value that is not in the new list is removed, as the
 * loop's own state store does.
 */
fun setAutomationStateValues(currentJson: String, valuesJson: String, name: String, values: List<String>): AutomationStateTexts {
    val allowed = decodeStringLists(valuesJson) + (name to values)
    val current = decodeStringMap(currentJson).filterNot { (key, value) -> key == name && value !in values }
    return AutomationStateTexts(encodeStringMap(current), encodeStringLists(allowed))
}

/** Removes [name] and its current value. */
fun deleteAutomationState(currentJson: String, valuesJson: String, name: String): AutomationStateTexts =
    AutomationStateTexts(
        encodeStringMap(decodeStringMap(currentJson) - name),
        encodeStringLists(decodeStringLists(valuesJson) - name)
    )

private fun encodeStringMap(map: Map<String, String>): String =
    JsonObject(map.mapValues { (_, value) -> JsonPrimitive(value) }).toString()

private fun encodeStringLists(map: Map<String, List<String>>): String =
    JsonObject(map.mapValues { (_, values) -> JsonArray(values.map { JsonPrimitive(it) }) }).toString()

private fun decodeStringMap(raw: String): Map<String, String> {
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyMap()
    return buildMap {
        for ((key, value) in element) {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            put(key, text)
        }
    }
}

private fun decodeStringLists(raw: String): Map<String, List<String>> {
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyMap()
    return buildMap {
        for ((key, value) in element) {
            val items = value as? JsonArray ?: continue
            val texts = ArrayList<String>(items.size)
            var valid = true
            for (item in items) {
                val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text == null) {
                    valid = false
                    break
                }
                texts.add(text)
            }
            if (valid) put(key, texts)
        }
    }
}
