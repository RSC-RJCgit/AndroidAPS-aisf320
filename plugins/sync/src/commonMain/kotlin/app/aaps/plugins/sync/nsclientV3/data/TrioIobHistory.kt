package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A small display history, separate from the treatment and dosing records. */
internal data class TrioIobHistory(
    val site: String,
    val samples: List<TrioIobSample> = emptyList()
) {
    fun merge(points: List<GraphDataPoint>, now: Long): TrioIobHistory = copy(
        samples = (samples + points.map { TrioIobSample(it.timestamp, it.value) })
            .filter { it.timestamp > 0 && it.timestamp >= now - RETENTION_MS && it.timestamp <= now + 60_000L && it.value.isFinite() }
            .associateBy { it.timestamp }.values.sortedBy { it.timestamp }.takeLast(MAX_SAMPLES)
    )

    fun encode(): String = buildJsonObject {
        put("site", site)
        put("samples", buildJsonArray {
            samples.forEach { sample ->
                add(buildJsonObject {
                    put("timestamp", sample.timestamp)
                    put("value", sample.value)
                })
            }
        })
    }.toString()

    companion object {
        private const val RETENTION_MS = 7 * 24 * 60 * 60_000L
        private const val MAX_SAMPLES = 2500

        fun decode(text: String): TrioIobHistory? = runCatching {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
            val site = (root["site"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
            val values = root["samples"] as? JsonArray ?: return@runCatching null
            val samples = values.mapNotNull { value ->
                val sample = value as? JsonObject ?: return@mapNotNull null
                val timestamp = (sample["timestamp"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val iob = (sample["value"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: return@mapNotNull null
                TrioIobSample(timestamp, iob)
            }
            TrioIobHistory(site, samples)
        }.getOrNull()
    }
}

internal data class TrioIobSample(val timestamp: Long, val value: Double)
