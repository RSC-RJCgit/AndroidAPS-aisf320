package app.aaps.core.objects.utils

import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.LiveStepsMirror
import javax.inject.Inject
import javax.inject.Provider

/** Source selection for non-loop consumers. Missing remote data never falls back to local SC. */
class StepCountSource @Inject constructor(
    private val preferences: Preferences,
    private val config: Config,
    private val activePlugin: Provider<ActivePlugin>,
    private val mirror: LiveStepsMirror,
    private val persistenceLayer: PersistenceLayer
) {
    fun isMirroring(): Boolean = !config.AAPSCLIENT &&
        preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual) && activePlugin.get().activePump is VirtualPump

    /** SC uses cumulative 10/15-minute counts; NS reason uses the individual five-minute bins. */
    fun latest(timestamp: Long, maxAgeMs: Long): Map<Int, Int>? {
        if (isMirroring()) {
            val sample = mirror.at(timestamp) ?: return null
            if (timestamp - sample.timestamp > maxAgeMs) return null
            return sample.cumulativeBuckets()
        }
        val sample = persistenceLayer.getStepsCountFromTimeToTime(timestamp - maxAgeMs, timestamp)
            .filter { it.isValid && it.timestamp <= timestamp }
            .maxByOrNull { it.timestamp } ?: return null
        return localBuckets(sample)
    }

    /**
     * Step history for the steps graph (2026-10-18), oldest first, with cumulative counts (S15 = the last 15 minutes):
     * - Virtual with mirroring: Live's samples kept by [LiveStepsMirror] (36 h)
     * - otherwise the local SC rows
     * - a client has no SC rows; it reads the step lines from the master's loop results, which use five-minute bins
     */
    fun history(from: Long, to: Long): List<Pair<Long, Map<Int, Int>>> {
        if (isMirroring()) return mirror.between(from, to).map { it.timestamp to it.cumulativeBuckets() }
        val local = persistenceLayer.getStepsCountFromTimeToTime(from, to).filter { it.isValid }.sortedBy { it.timestamp }
        if (local.isNotEmpty() || !config.AAPSCLIENT) return local.map { it.timestamp to localBuckets(it) }
        return persistenceLayer.getApsResults(from, to).mapNotNull { result ->
            val buckets = LiveStepsMirror.parse(result.reason)
            if (buckets.isEmpty()) null
            else result.date to LiveStepsMirror.Sample(result.date, "reason", buckets).cumulativeBuckets()
        }.sortedBy { it.first }
    }

    private fun localBuckets(sample: SC) = mapOf(
        5 to sample.steps5min, 10 to sample.steps10min, 15 to sample.steps15min,
        30 to sample.steps30min, 60 to sample.steps60min, 180 to sample.steps180min
    )
}
