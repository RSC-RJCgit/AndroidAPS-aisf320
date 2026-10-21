package app.aaps.core.objects.wizard

import app.aaps.core.data.model.LiveSteps
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.round

// The live checks the Bolus Wizard makes before it calculates, shared with QuickWizard (2026-10-21, per explicit request:
// a QuickWizard gives the same dose as the Bolus Wizard with the same options). They were private to the wizard screen.

private const val BG_RISE_WINDOW_MINUTES = 60L

/** True when the falling-BG caution applies now ([WizardDropTrendCaution]): the wizard percent is then halved. */
suspend fun dropTrendCautionNow(
    bgMgdl: Double,
    glucoseStatusProvider: GlucoseStatusProvider,
    preferences: Preferences,
    iobCobCalculator: IobCobCalculator,
    persistenceLayer: PersistenceLayer,
    dateUtil: DateUtil,
): Boolean {
    val glucose = glucoseStatusProvider.getGlucoseStatusData() ?: return false
    val hp1Cached = preferences.get(LongNonKey.ApsAutoIsfLastCycleHp1MilliMmol)
    val hp1Mmol = if (hp1Cached != 0L) hp1Cached / 1000.0 else null
    val bolusIob = iobCobCalculator.calculateIobFromBolus().round().iob
    val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
    val from = dateUtil.now() - T.mins(BG_RISE_WINDOW_MINUTES).msecs()
    val recentMin = persistenceLayer.getBgReadingsDataFromTimeToTime(from, dateUtil.now(), ascending = true).minOfOrNull { it.value }
    return WizardDropTrendCaution.applies(
        delta = glucose.delta,
        shortAvgDelta = glucose.shortAvgDelta,
        longAvgDelta = glucose.longAvgDelta,
        bgMgdl = bgMgdl,
        hp1Mmol = hp1Mmol,
        iobUnits = bolusIob + basalIob,
        bgRiseMgdl = if (recentMin == null) 0.0 else bgMgdl - recentMin,
    )
}

/**
 * Steps in the last 5, 30 and 15 minutes, for the walking-soon default. A Virtual master set to use the main master
 * looping phone's steps reads those; no fresh sample from it gives nulls (the box is left as it is). Otherwise this
 * phone's own latest steps, and none means not moving.
 */
suspend fun walkingSoonStepCounts(
    config: Config,
    preferences: Preferences,
    activePlugin: ActivePlugin,
    persistenceLayer: PersistenceLayer,
    dateUtil: DateUtil,
): Triple<Int?, Int?, Int?> {
    val now = dateUtil.now()
    val useLive = config.APS && !config.AAPSCLIENT &&
        preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual) &&
        activePlugin.activePump.pumpDescription.pumpType == PumpType.GENERIC_AAPS
    if (useLive) {
        val own = "openaps://${config.deviceModelForUpload}"
        val sample = LiveSteps.sampleFor(
            now,
            persistenceLayer.getStepsCountFromTimeToTime(now - LiveSteps.MAX_AGE_MS, now),
            fromLivePhone = true,
            ownDevice = own,
        )?.takeIf { row ->
            LiveSteps.hasDosingBuckets(
                mapOf(
                    5 to row.steps5min,
                    10 to row.steps10min,
                    15 to row.steps15min,
                    30 to row.steps30min,
                    60 to row.steps60min,
                    180 to row.steps180min,
                )
            )
        }
        if (sample == null) return Triple(null, null, null)
        return Triple(sample.steps5min, sample.steps30min, sample.steps15min)
    }
    val local = persistenceLayer.getLastStepsCountFromTimeToTime(now - 30 * 60_000L, now)
    if (local == null) return Triple(0, 0, 0)
    return Triple(local.steps5min, local.steps30min, local.steps15min)
}
