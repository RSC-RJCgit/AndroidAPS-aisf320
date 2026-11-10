package app.aaps.core.objects.notes

import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile

/**
 * The full display text of an AutoISF Careportal note, stored on this phone.
 *
 * The note itself stays the short code (for example `Shwr12`), because rules on this and other phones match those codes.
 * The SMS sent with it has the full text with the values of that moment (`Shower12: g=7.8 iobTH=12`), which cannot be
 * made again later. So it is saved here by the note's time, and Treatments > CarePortal shows it under the code.
 * A master can also send the full text separately in Nightscout device status.
 */
object NoteSmsTexts {

    /** Texts older than this are removed when a new one is saved */
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

    /** At most this many texts are kept, the newest ones */
    const val MAX_ENTRIES = 3000

    private val writeLock = Mutex()
    @Volatile private var cache: Pair<String, Map<Long, String>>? = null

    /**
     * Saves [smsText] for the note at [noteTimestamp]. Nothing is saved when it is blank or the same as [note].
     * Sending a note and receiving a tagged SMS can both write to the same preference.
     */
    suspend fun save(preferences: Preferences, noteTimestamp: Long, note: String, smsText: String?, now: Long) {
        val text = smsText?.trim().orEmpty()
        if (text.isEmpty() || text == note.trim()) return
        writeLock.withLock {
            val all = all(preferences).toMutableMap()
            if (all[noteTimestamp] == text) return
            all[noteTimestamp] = text
            write(preferences, prune(all, now))
        }
    }

    /** Fill gaps in one write, keeping any text already saved on this phone. */
    suspend fun recoverMissing(preferences: Preferences, texts: Map<Long, String>, now: Long) {
        if (texts.isEmpty()) return
        writeLock.withLock {
            val saved = all(preferences)
            if (texts.keys.all { it in saved }) return
            val kept = prune(texts + saved, now)
            if (kept != saved) write(preferences, kept)
        }
    }

    private fun write(preferences: Preferences, texts: Map<Long, String>) {
        preferences.put(StringNonKey.CarePortalNoteSmsTexts, JsonObject(texts.entries.associate { it.key.toString() to JsonPrimitive(it.value) }).toString())
    }

    /** All saved texts by note time. Parsed once for each new stored value. */
    fun all(preferences: Preferences): Map<Long, String> {
        val raw = preferences.get(StringNonKey.CarePortalNoteSmsTexts)
        return cache?.takeIf { it.first == raw }?.second ?: parse(raw).also { cache = raw to it }
    }

    /**
     * The SMS sent with a note ends with this tag, the note's time in base 36 (for example ` #nmhb2k3fk`), so a full AAPS
     * that receives the SMS can save the text for the same note when Nightscout brings it.
     */
    fun tagged(text: String, noteTimestamp: Long): String = "$text $TAG_PREFIX${noteTimestamp.toString(36)}"

    /** The note time and the text without its tag, or null when [text] carries no note tag */
    fun untag(text: String): Pair<Long, String>? {
        val match = TAG_REGEX.find(text.trim()) ?: return null
        val timestamp = match.groupValues[2].toLongOrNull(36) ?: return null
        return timestamp to match.groupValues[1].trim()
    }

    private const val TAG_PREFIX = "#n"
    private val TAG_REGEX = Regex("""^([\s\S]*\S)\s+#n([0-9a-z]{6,12})$""")

    /** Drops texts older than [KEEP_MS], then keeps the newest [MAX_ENTRIES] */
    fun prune(texts: Map<Long, String>, now: Long): Map<Long, String> =
        texts.filterKeys { it >= now - KEEP_MS }.entries.sortedByDescending { it.key }.take(MAX_ENTRIES).associate { it.key to it.value }

    fun parse(raw: String): Map<Long, String> = runCatching {
        Json.parseToJsonElement(raw).jsonObject.entries.mapNotNull { (key, value) ->
            val timestamp = key.toLongOrNull() ?: return@mapNotNull null
            val text = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: return@mapNotNull null
            timestamp to text
        }.toMap()
    }.getOrDefault(emptyMap())
}
