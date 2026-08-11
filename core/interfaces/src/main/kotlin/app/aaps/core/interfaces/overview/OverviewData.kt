package app.aaps.core.interfaces.overview

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.graph.Scale
import app.aaps.core.interfaces.graph.SeriesData

interface OverviewData {

    var rangeToDisplay: Int // for graph
    var toTime: Long  // current time rounded up to 1 hour
    var fromTime: Long // toTime - range
    var endTime: Long // toTime + predictions

    fun reset()
    fun initRange()
    /*
     * PUMP STATUS
     */

    var pumpStatus: String

    /*
     * CALC PROGRESS
     */

    var calcProgressPct: Int

    /*
     * TEMPORARY BASAL
     */

    fun temporaryBasalText(): String
    fun temporaryBasalDialogText(): String
    @DrawableRes fun temporaryBasalIcon(): Int
    @AttrRes fun temporaryBasalColor(context: Context?): Int

    /*
     * EXTENDED BOLUS
    */
    fun extendedBolusText(): String
    fun extendedBolusDialogText(): String

    /*
     * Graphs
     */

    var bgReadingsArray: List<GV>
    var maxBgValue: Double
    var bucketedGraphSeries: SeriesData
    var bgReadingGraphSeries: SeriesData
    var predictionsGraphSeries: SeriesData

    val basalScale: Scale
    var baseBasalGraphSeries: SeriesData
    var tempBasalGraphSeries: SeriesData
    var tempBasalAcceIsfSeries: SeriesData
    var tempBasalBgIsfSeries: SeriesData
    var tempBasalPpIsfSeries: SeriesData
    var tempBasalDuraIsfSeries: SeriesData
    var basalLineGraphSeries: SeriesData
    var absoluteBasalGraphSeries: SeriesData

    var temporaryTargetSeries: SeriesData
    var runningModesSeries: SeriesData

    var maxIAValue: Double
    val actScale: Scale
    var activitySeries: SeriesData
    var activityPredictionSeries: SeriesData
    var activityPeakSeries: SeriesData

    var maxCarbAbsorptionValue: Double
    val carbAbsorptionScale: Scale
    var carbAbsorptionSeries: SeriesData

    var maxCarbModelValue: Double
    val carbModelScale: Scale
    var carbModelSeries: SeriesData

    // UAM Carb Impact (uci) -- deviation-derived carbs-equivalent, grams/5min (converted from uci's
    // native mg/dL/5min BG-impact via csf), from the AIV table, EMA-smoothed to match
    // carbAbsorptionScale's own smoothing. Same physical unit as carbAbsorptionScale now, but kept on
    // its own independent scale by choice, not necessity — still its own line/auto-fit range. See
    // PrepareIobAutosensGraphDataWorker.kt.
    var maxUamCarbImpactValue: Double
    val uamCarbImpactScale: Scale
    var uamCarbImpactSeries: SeriesData

    // Combined Carbs -- carbAbsorptionSeries + uamCarbImpactSeries summed at matching bucket
    // timestamps. Deliberately excludes carbModelSeries (a forward prediction from entered carbs, not
    // a live activity measurement). See PrepareIobAutosensGraphDataWorker.kt.
    var maxCombinedCarbsValue: Double
    val combinedCarbsScale: Scale
    var combinedCarbsSeries: SeriesData

    var maxBgParabolaValue: Double
    val bgParabolaScale: Scale
    var bgParabolaSeries: SeriesData
    var bgParabolaPredictionSeries: SeriesData

    var maxEpsValue: Double
    val epsScale: Scale
    var epsSeries: SeriesData
    var maxTreatmentsValue: Double
    var treatmentsSeries: SeriesData
    var smbLabelSeries: SeriesData
    var smbStackTotalSeries: SeriesData
    var maxTherapyEventValue: Double
    var therapyEventSeries: SeriesData
    // Plain TE.Type.NOTE events only (split out from therapyEventSeries) — renders on graph4 (swapped
    // with the SMB stacked labels, which moved to graph2), while therapyEventSeries
    // (Announcements/MBG/finger-stick/settings-export/exercise) stays on the main graph.
    var noteEventSeries: SeriesData
    // Same notes as noteEventSeries, but rendered as plain unscaled arrowheads (Shape.NOTE_ARROWHEAD_GRAPH3)
    // fixed at graph4's top half — an additional, simpler view alongside noteEventSeries also on graph4.
    var noteArrowheadSeries: SeriesData

    var maxIobValueFound: Double
    val iobScale: Scale
    var iobSeries: SeriesData
    var absIobSeries: SeriesData
    var iobPredictions1Series: SeriesData
    // Same "only the genuinely dominant peaks" labeling as activityPeakSeries -- see
    // PrepareIobAutosensGraphDataWorker.kt's iobPeakIndices computation for the exact rule.
    var iobPeakSeries: SeriesData

    var maxBGIValue: Double
    val bgiScale: Scale
    var minusBgiSeries: SeriesData
    var minusBgiHistSeries: SeriesData

    var maxCobValueFound: Double
    val cobScale: Scale
    var cobSeries: SeriesData
    var cobMinFailOverSeries: SeriesData

    var maxDevValueFound: Double
    val devScale: Scale
    var deviationsSeries: SeriesData

    var maxRatioValueFound: Double                    //even if sens data equals 0 for all the period, minimum scale is between 95% and 105%
    var minRatioValueFound: Double
    val ratioScale: Scale
    var ratioSeries: SeriesData

    var maxVarSensValueFound: Double
    var minVarSensValueFound: Double
    val varSensScale: Scale
    var varSensSeries: SeriesData

    var maxFromMaxValueFound: Double
    var maxFromMinValueFound: Double
    val dsMaxScale: Scale
    val dsMinScale: Scale
    var dsMaxSeries: SeriesData
    var dsMinSeries: SeriesData
    // A list, not a single series: the raw/Libre line is split into color-banded segments (red / yellow
    // low / yellow high) since GraphView's line series can't vary color within itself — see
    // PrepareBgDataWorker.kt.
    var rawBgSeries: List<SeriesData>
    // UKF-smoothed trace of the same raw/noise values as rawBgSeries -- a single continuous line
    // (unlike rawBgSeries, doesn't need color-banding) drawn alongside it. See PrepareBgDataWorker.kt
    // and UnscentedKalmanFilterPlugin.smoothForDisplay().
    var rawBgSmoothedSeries: SeriesData
    var noisyBgDeltaSeries: SeriesData
    var ukfDeltaSeries: SeriesData
    // AAPS (smoothed) 1-min delta label attached to the current smoothed BG graph point — see A1DeltaDataPoint/Shape.A1_DELTA_POINT.
    var a1DeltaSeries: SeriesData
    // "hypoprediction= <value>" row, fixed near the bottom of the main graph — see HPDataPoint/Shape.HP_ROW_BOTTOM.
    var hpSeries: SeriesData
    var targetOffsetDuTSeries: SeriesData
    // "pp= acc= du=" row, fixed near the bottom of graph3 — see IsfWeightsRowDataPoint.
    var isfWeightsRowSeries: SeriesData
    var stepsStackedSeries: SeriesData
    // "DR=/AW=/LS=" row, split out of stepsStackedSeries — see StepsExtraDataPoint/Shape.STEPS_EXTRA_ROW.
    var stepsExtraSeries: SeriesData
    // "f= ac= bg= pp= du= smb=" multi-color row on graph3 — see IsfIndicesDataPoint/Shape.ISF_INDICES.
    var isfIndicesSeries: SeriesData
    var heartRateScale: Scale
    var heartRateGraphSeries: SeriesData
    var stepsForScale: Scale
    var stepsCountGraphSeries: SeriesData

    // AutoISF interim results
    var maxIobThValueFound: Double
    var minIobThValueFound: Double
    val iobThScale: Scale
    var iobThSeries: SeriesData
    var maxAcceIsfValueFound: Double
    var minAcceIsfValueFound: Double
    val acceIsfScale: Scale
    var acceIsfSeries: SeriesData
    var maxBgIsfValueFound: Double
    var minBgIsfValueFound: Double
    val bgIsfScale: Scale
    var bgIsfSeries: SeriesData
    var maxPpIsfValueFound: Double
    var minPpIsfValueFound: Double
    val ppIsfScale: Scale
    var ppIsfSeries: SeriesData
    var maxDuraIsfValueFound: Double
    var minDuraIsfValueFound: Double
    val duraIsfScale: Scale
    var duraIsfSeries: SeriesData
    var maxFinalIsfValueFound: Double
    var minFinalIsfValueFound: Double
    val finalIsfScale: Scale
    var finalIsfSeries: SeriesData
}
