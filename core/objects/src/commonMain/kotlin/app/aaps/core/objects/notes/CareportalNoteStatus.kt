package app.aaps.core.objects.notes

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Optional display text carried in Nightscout's suggested device status. */
object CareportalNoteStatus {

    const val FIELD = "careportalNoteTexts"
    const val WINDOW_MS = 6L * 60 * 60 * 1000
    private const val MAX_NOTES = 32
    private const val MAX_TEXT_LENGTH = 1024
    private const val CLOCK_TOLERANCE_MS = 5L * 60 * 1000

    fun encode(texts: Map<Long, String>, noteTimes: Set<Long>, now: Long): JsonObject? {
        val entries = texts.entries.asSequence()
            .filter { (time, text) -> time > 0 && time in (now - WINDOW_MS)..now && time in noteTimes && isValidText(text) }
            .sortedByDescending { it.key }
            .take(MAX_NOTES)
            .associate { (time, text) -> time.toString() to JsonPrimitive(text) }
        return if (entries.isEmpty()) null else JsonObject(entries)
    }

    fun decode(value: JsonElement?, now: Long): Map<Long, String> {
        val notes = value as? JsonObject ?: return emptyMap()
        if (notes.size > MAX_NOTES) return emptyMap()
        return notes.mapNotNull { (key, value) ->
            val time = key.toLongOrNull() ?: return@mapNotNull null
            if (time <= 0 || time.toString() != key || time !in (now - NoteSmsTexts.KEEP_MS)..(now + CLOCK_TOLERANCE_MS)) return@mapNotNull null
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (isValidText(text)) time to text else null
        }.toMap()
    }

    private fun isValidText(text: String): Boolean = text.length <= MAX_TEXT_LENGTH && text.isNotBlank()
}
