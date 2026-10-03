package app.aaps.plugins.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The states the loop has declared, with the value each one holds now.
 * A name with no current value is still listed, and its value is empty.
 * Broken saved text becomes an empty list.
 */
fun automationStateRows(currentJson: String, valuesJson: String): List<Pair<String, String>> {
    val current = decodeStringMap(currentJson)
    val allowed = decodeStringLists(valuesJson)
    return allowed.keys.sorted().map { name -> name to current[name].orEmpty() }
}

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
