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

    private fun localBuckets(sample: SC) = mapOf(
        5 to sample.steps5min, 10 to sample.steps10min, 15 to sample.steps15min,
        30 to sample.steps30min, 60 to sample.steps60min, 180 to sample.steps180min
    )
}
