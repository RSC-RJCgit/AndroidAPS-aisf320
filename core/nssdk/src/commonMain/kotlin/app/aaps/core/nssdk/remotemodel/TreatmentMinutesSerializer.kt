package app.aaps.core.nssdk.remotemodel

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.doubleOrNull

/** Accept fractional minutes on download, while keeping whole-minute uploads as integer JSON. */
internal object TreatmentMinutesSerializer : JsonTransformingSerializer<Double>(Double.serializer()) {
    override fun transformSerialize(element: JsonElement): JsonElement {
        val value = (element as? JsonPrimitive)?.doubleOrNull ?: return element
        return if (value.isFinite() && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble() && value == value.toLong().toDouble())
            JsonPrimitive(value.toLong())
        else element
    }
}
