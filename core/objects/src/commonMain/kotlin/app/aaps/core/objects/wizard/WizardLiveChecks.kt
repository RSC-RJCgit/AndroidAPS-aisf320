package app.aaps.core.objects.wizard

import app.aaps.core.data.time.SystemTimeZone
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.round
import kotlin.math.roundToLong

// The live checks the Bolus Wizard makes before it calculates, shared with QuickWizard (2026-10-21, per explicit request:
// a QuickWizard gives the same dose as the Bolus Wizard with the same options). They were private to the wizard screen.

private const val BG_RISE_WINDOW_MINUTES = 60L

/**
 * HP1 (hypo prediction) for a phone without AutoISF, which on the fork works it out every loop (2026-11-14, per explicit
 * request): the same formula, (BG - IOB) + 0.25 x short delta + 0.25 x 5-min delta + COB / 12, all mmol/L. The fork takes
 * the 5-min change of the raw Libre value; here the normal 5-min delta. Returns the glucose time and HP1 in milli-mmol/L,
 * as the fork stores it, or null without glucose data. It replaces the lowest-loop-prediction stand-in, which ignores
 * carbs and so held nearly every split part after a meal (A15, 14 Nov 12:50).
 */
suspend fun localHp1(glucoseStatusProvider: GlucoseStatusProvider, iobCobCalculator: IobCobCalculator): Pair<Long, Long>? {
    val glucose = glucoseStatusProvider.getGlucoseStatusData() ?: return null
    val iob = iobCobCalculator.calculateIobFromBolus().round().iob +
        iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
    val cob = iobCobCalculator.getMealDataWithWaitingForCalculationFinish().mealCOB
    val mmol = 18.0182
    val hp1 = (glucose.glucose / mmol - iob) + 0.25 * (glucose.shortAvgDelta / mmol) + 0.25 * (glucose.delta / mmol) + cob / 12.0
    if (!hp1.isFinite()) return null
    return glucose.date to (hp1 * 1000.0).roundToLong()
}

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
    // No AutoISF on this phone: the same HP1 worked out here (2026-11-14, see localHp1)
    val hp1Mmol = if (hp1Cached != 0L) hp1Cached / 1000.0 else localHp1(glucoseStatusProvider, iobCobCalculator)?.second?.let { it / 1000.0 }
    val bolusIob = iobCobCalculator.calculateIobFromBolus().round().iob
    val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
    val from = dateUtil.now() - T.mins(BG_RISE_WINDOW_MINUTES).msecs()
    val recentMin = persistenceLayer.getBgReadingsDataFromTimeToTime(from, dateUtil.now(), ascending = true).minOfOrNull { it.value }
    val tt = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
    return WizardDropTrendCaution.applies(
        delta = glucose.delta,
        shortAvgDelta = glucose.shortAvgDelta,
        bgMgdl = bgMgdl,
        hp1Mmol = hp1Mmol,
        iobUnits = bolusIob + basalIob,
        bgRiseMgdl = if (recentMin == null) 0.0 else bgMgdl - recentMin,
        minuteOfDay = MidnightTime.secondsFromMidnight(dateUtil.now(), SystemTimeZone.current()) / 60,
        activityTempTarget = tt != null && WizardDropTrendCaution.isActivityTempTarget(tt.lowTarget, tt.duration),
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
    // Patch for upstream: the fork's "use the main phone's steps on a Virtual master" branch is left out.
    val local = persistenceLayer.getLastStepsCountFromTimeToTime(now - 30 * 60_000L, now)
    if (local == null) return Triple(0, 0, 0)
    return Triple(local.steps5min, local.steps30min, local.steps15min)
}
