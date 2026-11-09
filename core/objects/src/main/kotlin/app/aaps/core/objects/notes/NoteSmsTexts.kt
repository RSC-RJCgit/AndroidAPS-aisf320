package app.aaps.core.objects.notes

import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject

/**
 * The full SMS text of an AutoISF CarePortal note, kept on this phone only (2026-11-09, per explicit request).
 *
 * The note itself stays the short code (for example `Shwr12`), because rules on this and other phones match those codes.
 * The SMS sent with it has the full text with the values of that moment (`Shower12: g=7.8 iobTH=12`), which cannot be
 * made again later. So it is saved here by the note's time, and Treatments > CarePortal shows it under the code.
 * Nightscout and other phones get the code only.
 */
object NoteSmsTexts {

    /** Texts older than this are removed when a new one is saved */
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

    /** At most this many texts are kept, the newest ones */
    const val MAX_ENTRIES = 3000

    private val lock = Any()
    private var cachedRaw: String? = null
    private var cached: Map<Long, String> = emptyMap()
    private val senderPattern = Regex("\\+?[0-9]+")

    fun normaliseSender(number: String): String? =
        number.filterNot { it.isWhitespace() || it in "-()" }.takeIf(senderPattern::matches)

    /** Saves [smsText] for the note at [noteTimestamp]. Nothing is saved when it is blank or the same as [note]. */
    fun save(preferences: Preferences, noteTimestamp: Long, note: String, smsText: String?, now: Long) {
        val text = smsText?.trim().orEmpty()
        if (text.isEmpty() || text == note.trim()) return
        synchronized(lock) {
            val existing = all(preferences)
            if (existing[noteTimestamp] != text) write(preferences, prune(existing + (noteTimestamp to text), now))
        }
    }

    /** Fills gaps from the inbox without replacing live SMS texts. Returns true if anything was saved. */
    fun saveMissing(preferences: Preferences, texts: Map<Long, String>, now: Long): Boolean = synchronized(lock) {
        if (texts.isEmpty()) return@synchronized false
        val existing = all(preferences)
        val kept = prune(texts + existing, now)
        if (kept == existing) return@synchronized false
        write(preferences, kept)
        true
    }

    private fun write(preferences: Preferences, texts: Map<Long, String>) {
        val json = JSONObject()
        texts.forEach { (timestamp, value) -> json.put(timestamp.toString(), value) }
        preferences.put(StringNonKey.CarePortalNoteSmsTexts, json.toString())
    }

    /** All saved texts by note time. Parsed once for each new stored value, so a list can call it for every row. */
    fun all(preferences: Preferences): Map<Long, String> = synchronized(lock) {
        val raw = preferences.get(StringNonKey.CarePortalNoteSmsTexts)
        if (raw != cachedRaw) {
            cached = parse(raw)
            cachedRaw = raw
        }
        cached
    }

    /**
     * The SMS sent with a note ends with this tag, the note's time in base 36 (for example ` #nmhb2k3fk`), so a full AAPS
     * or AAPSClient that receives the SMS can save the text for the same note when Nightscout brings it.
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
        val json = JSONObject(raw)
        json.keys().asSequence().mapNotNull { key -> key.toLongOrNull()?.let { it to json.getString(key) } }.toMap()
    }.getOrDefault(emptyMap())
}
