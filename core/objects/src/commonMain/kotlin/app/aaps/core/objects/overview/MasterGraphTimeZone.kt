package app.aaps.core.objects.overview

import app.aaps.core.keys.GRAPH_UTC_OFFSET_PATTERN
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The last master zone reported by this client's Nightscout site, for graph labels only. */
object MasterGraphTimeZone {

    const val FIELD = "masterTimeZone"
    private val writeLock = Mutex()

    private val manualOffsetPattern = Regex(GRAPH_UTC_OFFSET_PATTERN)
    private class CachedZone(val preferences: Preferences, val readAt: TimeSource.Monotonic.ValueTimeMark, val zone: TimeZone?)
    @Volatile private var cachedZone: CachedZone? = null

    /** Avoid reading settings for every minute of historical basal calculations. */
    fun forVirtualMaster(preferences: Preferences): TimeZone? {
        val cached = cachedZone
        if (cached != null && cached.preferences === preferences && cached.readAt.elapsedNow().inWholeMilliseconds < 1000L) return cached.zone
        val imported = if (preferences.get(BooleanKey.ClientGraphFollowMasterTimeZone)) {
            read(preferences.get(StringNonKey.ClientGraphMasterTimeZone), selectedSite(preferences))
        } else null
        val zone = imported ?: preferences.get(StringKey.ClientGraphManualUtcOffset).trim()
            .takeIf(manualOffsetPattern::matches)?.let(::parseZone)
        cachedZone = CachedZone(preferences, TimeSource.Monotonic.markNow(), zone)
        return zone
    }

    fun parseZone(id: String): TimeZone? = runCatching { TimeZone.of(id) }.getOrNull()

    fun selectedSite(primary: String, secondary: String, secondaryEnabled: Boolean): String =
        if (secondaryEnabled && secondary.isNotBlank()) secondary.trim() else primary.trim()

    fun selectedSite(preferences: Preferences): String =
        if (preferences.get(BooleanKey.NsClientSecondaryEnabled)) {
            selectedSite(preferences.get(StringKey.NsClientUrl), preferences.get(StringKey.NsClientSecondaryUrl), secondaryEnabled = true)
        } else preferences.get(StringKey.NsClientUrl).trim()

    fun read(saved: String, site: String): TimeZone? {
        val record = decode(saved) ?: return null
        if (site.isBlank() || record.text("site") != site) return null
        return record.text("zone")?.let(::parseZone)
    }

    suspend fun saveNewest(preferences: Preferences, site: String, at: Long, zone: TimeZone) {
        if (site.isBlank() || at <= 0L) return
        writeLock.withLock {
            if (selectedSite(preferences) != site) return
            val saved = decode(preferences.get(StringNonKey.ClientGraphMasterTimeZone))
            val savedTime = (saved?.get("time") as? JsonPrimitive)?.longOrNull ?: 0L
            if (saved?.text("site") == site && at <= savedTime) return
            val new = buildJsonObject {
                put("site", site)
                put("time", at)
                put("zone", zone.id)
            }
            preferences.put(StringNonKey.ClientGraphMasterTimeZone, new.toString())
        }
    }

    private fun decode(saved: String): JsonObject? =
        if (saved.isBlank()) null else runCatching { Json.parseToJsonElement(saved) as? JsonObject }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
