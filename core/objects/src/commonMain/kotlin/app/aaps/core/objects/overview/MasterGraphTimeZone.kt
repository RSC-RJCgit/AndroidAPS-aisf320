package app.aaps.core.objects.overview

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
