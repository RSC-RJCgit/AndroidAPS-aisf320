package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.interfaces.trio.TrioOverridePreset
import app.aaps.core.interfaces.trio.TrioPushDetails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What Trio's profile says about how to reach it, and the override presets saved in it */
internal data class TrioPushInfo(val details: TrioPushDetails, val presets: List<TrioOverridePreset>)

/**
 * The push details from a Trio profile, kept with the site they came from (2026-10-29, per explicit request). Null when
 * the profile has no device token, which is every profile that is not Trio's.
 *
 * Trio uploads `deviceToken`, `bundleIdentifier`, `isAPNSProduction` and `teamID` at the top of its profile, and its
 * override presets as `overridePresets`. LoopFollow also reads a Loop style `loopSettings` and a `trioOverrides` list, so
 * those are read too.
 */
internal fun trioPushInfoRecord(profile: JsonObject, site: String): String? {
    val loopSettings = profile["loopSettings"] as? JsonObject
    fun text(key: String) = (profile[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: (loopSettings?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val deviceToken = text("deviceToken") ?: return null
    val bundleId = text("bundleIdentifier") ?: return null
    val presets = (profile["overridePresets"] as? JsonArray ?: profile["trioOverrides"] as? JsonArray).orEmpty()
    return buildJsonObject {
        put("site", site.trim().trimEnd('/'))
        put("deviceToken", deviceToken)
        put("bundleId", bundleId)
        put("teamId", text("teamID").orEmpty())
        put("production", (profile["isAPNSProduction"] as? JsonPrimitive)?.booleanOrNull ?: false)
        putJsonArray("presets") {
            presets.filterIsInstance<JsonObject>().forEach { preset ->
                val name = (preset["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
                addJsonObject {
                    put("name", name)
                    (preset["percentage"] as? JsonPrimitive)?.doubleOrNull?.let { put("percentage", it) }
                    (preset["duration"] as? JsonPrimitive)?.doubleOrNull?.let { put("duration", it) }
                    (preset["target"] as? JsonPrimitive)?.doubleOrNull?.let { put("target", it) }
                }
            }
        }
    }.toString()
}

/** The details in [record], when it came from [site]. A record from another site is never used. */
internal fun trioPushInfo(record: String, site: String): TrioPushInfo? {
    if (record.isEmpty()) return null
    val json = runCatching { Json.parseToJsonElement(record) as? JsonObject }.getOrNull() ?: return null
    fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
    if (text("site") != site.trim().trimEnd('/')) return null
    val details = TrioPushDetails(
        deviceToken = text("deviceToken") ?: return null,
        bundleId = text("bundleId") ?: return null,
        teamId = text("teamId").orEmpty(),
        production = (json["production"] as? JsonPrimitive)?.booleanOrNull ?: false
    )
    val presets = (json["presets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { preset ->
        TrioOverridePreset(
            name = (preset["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
            percentage = (preset["percentage"] as? JsonPrimitive)?.doubleOrNull,
            durationMinutes = (preset["duration"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
            targetMgdl = (preset["target"] as? JsonPrimitive)?.doubleOrNull
        )
    }
    return TrioPushInfo(details, presets)
}
