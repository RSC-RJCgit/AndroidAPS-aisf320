package app.aaps.core.objects.extensions

import app.aaps.core.data.model.ICfg
import app.aaps.core.utils.lenientDouble
import app.aaps.core.utils.lenientLong
import app.aaps.core.utils.lenientString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun ICfg.toJsonObject(): JsonObject = buildJsonObject {
    put("insulinLabel", insulinLabel)
    put("insulinEndTime", insulinEndTime)
    put("insulinPeakTime", insulinPeakTime)
    put("concentration", JsonPrimitive(concentration))
    put("insulinNickname", insulinNickname)
}

/**
 * used to restore configuration within InsulinPlugin and insulin Editor
 *
 * Reads leniently on purpose. `longOrNull` is `content.toLongOrNull()`, which answers null for
 * `1.8E7` and for `18000000.5` - forms that occur in stored documents.
 * Falling back to 0 there would mean `insulinEndTime == 0`, i.e. DIA 0, so
 * IOB would decay at once and the loop would believe there is no insulin on board. See
 * [lenientLong].
 */
fun ICfg.Companion.fromJsonObject(json: JsonObject): ICfg {
    val icfg = ICfg(
        insulinLabel = json.lenientString("insulinLabel"),
        insulinEndTime = json.lenientLong("insulinEndTime"),
        insulinPeakTime = json.lenientLong("insulinPeakTime"),
        concentration = json.lenientDouble("concentration", 1.0)
    )

    icfg.insulinNickname = json.lenientString("insulinNickname")

    return icfg
}

/**
 * Reads one insulin stored as a JSON object in a String preference.
 * Null when the text is blank or not a JSON object, or when the curve has no peak or no DIA, so a bad value can never
 * stamp records with DIA 0.
 */
fun ICfg.Companion.fromStoredJson(json: String): ICfg? {
    if (json.isBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    return fromJsonObject(obj).takeIf { it.insulinEndTime > 0 && it.insulinPeakTime > 0 }
}
