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

    /** Saves [smsText] for the note at [noteTimestamp]. Nothing is saved when it is blank or the same as [note]. */
    fun save(preferences: Preferences, noteTimestamp: Long, note: String, smsText: String?, now: Long) {
        val text = smsText?.trim().orEmpty()
        if (text.isEmpty() || text == note.trim()) return
        synchronized(lock) {
            val all = parse(preferences.get(StringNonKey.CarePortalNoteSmsTexts)).toMutableMap()
            all[noteTimestamp] = text
            val kept = prune(all, now)
            val json = JSONObject()
            kept.forEach { (timestamp, value) -> json.put(timestamp.toString(), value) }
            preferences.put(StringNonKey.CarePortalNoteSmsTexts, json.toString())
        }
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

    /** Drops texts older than [KEEP_MS], then keeps the newest [MAX_ENTRIES] */
    fun prune(texts: Map<Long, String>, now: Long): Map<Long, String> =
        texts.filterKeys { it >= now - KEEP_MS }.entries.sortedByDescending { it.key }.take(MAX_ENTRIES).associate { it.key to it.value }

    fun parse(raw: String): Map<Long, String> = runCatching {
        val json = JSONObject(raw)
        json.keys().asSequence().mapNotNull { key -> key.toLongOrNull()?.let { it to json.getString(key) } }.toMap()
    }.getOrDefault(emptyMap())
}
