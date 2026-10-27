package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.data.datetime.parseIsoToEpochMillisOrNull
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.RT
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Remember the sender's units, not the viewer's units. A changed site or an older result cannot reuse them. */
internal fun trioProfileUnitsRecord(profile: JsonObject, site: String): String? {
    if ((profile["enteredBy"] as? JsonPrimitive)?.contentOrNull != "Trio") return null
    val default = (profile["defaultProfile"] as? JsonPrimitive)?.contentOrNull ?: return null
    val selected = (profile["store"] as? JsonObject)?.get(default) as? JsonObject ?: return null
    val units = (selected["units"] as? JsonPrimitive)?.contentOrNull
        ?: (profile["units"] as? JsonPrimitive)?.contentOrNull ?: return null
    val normalized = when (units.lowercase()) {
        "mg/dl" -> GlucoseUnit.MGDL
        "mmol", "mmol/l" -> GlucoseUnit.MMOL
        else -> return null
    }
    val start = (profile["startDate"] as? JsonPrimitive)?.contentOrNull
        ?.let(::parseIsoToEpochMillisOrNull) ?: return null
    return buildJsonObject {
        put("site", site.trim().trimEnd('/'))
        put("units", normalized.name)
        put("start", start)
    }.toString()
}

internal fun trioSourceUnits(record: String, site: String, timestamp: Long): GlucoseUnit? {
    val json = runCatching { Json.parseToJsonElement(record) as? JsonObject }.getOrNull() ?: return null
    if ((json["site"] as? JsonPrimitive)?.contentOrNull != site.trim().trimEnd('/')) return null
    val start = (json["start"] as? JsonPrimitive)?.longOrNull ?: return null
    if (timestamp < start) return null
    return when ((json["units"] as? JsonPrimitive)?.contentOrNull) {
        "MGDL" -> GlucoseUnit.MGDL
        "MMOL" -> GlucoseUnit.MMOL
        else -> null
    }
}

/** Read-only compatibility mapping. Native fields win; no algorithm or dosing decision is inferred. */
internal fun receivedLoopResult(device: String?, data: JsonObject, sourceUnits: GlucoseUnit?): RT {
    if (device != "Trio") return RT.deserialize(data.toString())
    val fields = data.toMutableMap()
    fun alias(native: String, trio: String, convert: (Double) -> Double = { it }) {
        if (fields[native] != null && fields[native] != JsonNull) return
        val value = (data[trio] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: return
        fields[native] = JsonPrimitive(convert(value))
    }
    alias("autoIsfAcce", "acce_ISFratio")
    alias("autoIsfBg", "bg_ISFratio")
    alias("autoIsfPp", "pp_ISFratio")
    alias("autoIsfDura", "dura_ISFratio")
    alias("autoIsfFinal", "auto_ISFratio")
    alias("autoIsfBgAcceleration", "bg_acce")
    alias("autoIsfSmbDeliveryRatio", "SMBratio")
    alias("autoIsfIobThEffective", "iob_THeffective")
    // Trio uploads ISF, bg and prediction arrays in mg/dL even when its displayed target is in mmol/L.
    alias("variable_sens", "ISF")
    if (sourceUnits != null) alias("targetBG", "current_target") {
        if (sourceUnits == GlucoseUnit.MMOL) it * Constants.MMOLL_TO_MGDL else it
    }
    return RT.deserialize(JsonObject(fields).toString())
}
