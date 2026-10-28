package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlinx.serialization.Serializable

/** A small display history, separate from the treatment and dosing records. */
@Serializable
internal data class TrioIobHistory(
    val site: String,
    val samples: List<TrioIobSample> = emptyList()
) {
    fun merge(points: List<GraphDataPoint>, now: Long): TrioIobHistory = copy(
        samples = (samples + points.map { TrioIobSample(it.timestamp, it.value) })
            .filter { it.timestamp > 0 && it.timestamp >= now - RETENTION_MS && it.timestamp <= now + 60_000L && it.value.isFinite() }
            .associateBy { it.timestamp }.values.sortedBy { it.timestamp }.takeLast(MAX_SAMPLES)
    )

    companion object {
        private const val RETENTION_MS = 7 * 24 * 60 * 60_000L
        private const val MAX_SAMPLES = 2500
    }
}

@Serializable
internal data class TrioIobSample(val timestamp: Long, val value: Double)
