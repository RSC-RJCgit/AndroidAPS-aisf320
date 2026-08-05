package app.aaps.workflow

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.time.T
import app.aaps.core.graph.data.BarGraphSeries
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.DeviationDataPoint
import app.aaps.core.graph.data.FixedLineGraphSeries
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.ScaledDataPoint
import app.aaps.core.graph.data.Shape
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.graph.Scale
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventIobCalculationProgress
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class PrepareIobAutosensGraphDataWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    private var ctx: Context

    init {
        ctx = rh.getThemedCtx(context)
    }

    class PrepareIobAutosensData(
        val iobCobCalculator: IobCobCalculator, // cannot be injected : HistoryBrowser uses different instance
        val overviewData: OverviewData
    )

    class IobTotalDataPoint(val i: IobTotal) : DataPointWithLabelInterface {

        private var color = 0
        override fun getX(): Double = i.time.toDouble()
        override fun getY(): Double = i.iob
        override fun setY(y: Double) {}
        override val label = ""
        override val duration = 0L
        override val shape = Shape.IOB_PREDICTION
        override val size = 0.5f
        override val paintStyle: Paint.Style = Paint.Style.FILL

        override fun color(context: Context?): Int = color
        fun setColor(color: Int): IobTotalDataPoint {
            this.color = color
            return this
        }
    }

    class AutosensDataPoint(
        private val ad: AutosensData,
        private val scale: Scale,
        private val chartTime: Long,
        private val rh: ResourceHelper
    ) : DataPointWithLabelInterface {

        override fun getX(): Double = chartTime.toDouble()
        override fun getY(): Double = scale.transform(ad.cob)
        override fun setY(y: Double) {}
        override val label: String = ""
        override val duration = 0L
        override val shape = Shape.COB_FAIL_OVER
        override val size = 0.5f
        override val paintStyle: Paint.Style = Paint.Style.FILL
        override fun color(context: Context?): Int {
            return rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
        }
    }

    class ActivityPeakDataPoint(
        private val x: Double,
        private val y: Double,
        private val labelText: String,
        private val rh: ResourceHelper
    ) : DataPointWithLabelInterface {
        override fun getX(): Double = x
        override fun getY(): Double = y
        override fun setY(y: Double) {}
        override val label: String = labelText
        override val duration = 0L
        override val shape = Shape.ACTIVITY_PEAK
        override val size = 0f
        override val paintStyle: Paint.Style = Paint.Style.FILL
        override fun color(context: Context?): Int = rh.gac(context, app.aaps.core.ui.R.attr.activityColor)
    }

    override suspend fun doWorkAndLog(): Result {
        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as PrepareIobAutosensData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        val endTime = data.overviewData.endTime
        val fromTime = data.overviewData.fromTime
        rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_IOB_AUTOSENS_DATA, 0, null))
        val iobArray: MutableList<ScaledDataPoint> = ArrayList()
        val absIobArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxIobValueFound = Double.MIN_VALUE
        var lastIob = 0.0
        var absLastIob = 0.0
        var time = fromTime

        val minFailOverActiveList: MutableList<DataPointWithLabelInterface> = ArrayList()
        val cobArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxCobValueFound = Double.MIN_VALUE
        var lastCob = 0

        val actArrayHist: MutableList<ScaledDataPoint> = ArrayList()
        val actArrayPrediction: MutableList<ScaledDataPoint> = ArrayList()
        // Raw (unscaled) activity values, one per actArrayHist/actArrayPrediction entry in the same order —
        // ScaledDataPoint.getY() returns the value already multiplied by actScale for graph rendering, so
        // peak detection/labeling needs this separate raw list instead of reading it back through .y.
        val rawActPoints: MutableList<Double> = ArrayList()
        val now = dateUtil.now().toDouble()
        data.overviewData.maxIAValue = 0.0

        // CARBS ABSORPTION -- like activity is to IOB, this is the rate (grams/5min), not the level
        // (that's COB above). this5MinAbsorption is the algorithm's own live per-bucket estimate, same
        // AutosensData already fetched once per loop iteration for COB/BGI/deviations below. History
        // only (no prediction segment, unlike activity) -- a thin, solid orange line on graph0.
        val carbAbsArrayHist: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxCarbAbsorptionValue = 0.0
        // Simple single-pole exponential smoother (same style as the LibreSpecial fsl_exp1 smoother
        // elsewhere in this codebase) -- this5MinAbsorption is a raw per-bucket rate and jumps around
        // a lot bucket-to-bucket, unlike a level (COB). null until the first real sample seeds it, so
        // the smoother doesn't start biased toward 0. History: 0.1 -> 0.3 -> briefly 1.0 (no smoothing,
        // as a raw baseline) -> back to 0.1 for heavier smoothing, now that the line itself is dashed
        // (see GraphData/PrepareIobAutosensGraphDataWorker styling).
        val carbAbsAlpha = 0.1
        var carbAbsEma: Double? = null

        // UAM Carb Impact (uci -> grams-equivalent, see DetermineBasalAutoISF.kt/RT.autoIsfUamCarbImpact)
        // -- same EMA smoothing/alpha as carbAbsArrayHist above. Computed HERE (moved from
        // PrepareBgDataWorker.kt, which used to own this line) so it shares this loop's 5-min buckets
        // with the empirical carb absorption line, letting both be summed into a Combined Carbs line at
        // matching timestamps -- PrepareBgDataWorker.kt's own discrete per-AIV-row timestamps couldn't
        // give it that alignment. autoIsfResults (fetched here, reused below for IOB_TH -- one query, not
        // two) is matched to each bucket by nearest timestamp within 4 min: AIV rows land roughly on the
        // same ~5-min cycle cadence as these buckets but aren't guaranteed exactly aligned to them.
        val autoIsfResults = persistenceLayer.getAutoIsfValuesFromTimeToTime(fromTime, endTime).sortedBy { it.timestamp }
        val uamCarbAlpha = 0.1
        var uamCarbEma: Double? = null
        val uamCarbImpactArrayHist: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxUamCarbImpactValue = 0.0

        // Combined Carbs -- smoothedAbs (empirical) + smoothed UAM impact, both already grams/5min at
        // this same bucket timestamp, simply added together. Not a third independent measurement, just
        // an "everything carbs-shaped happening right now" view without eyeballing two separate lines.
        // Deliberately excludes the carb model curve below -- that's a theoretical forward prediction
        // from entered carbs, not a live activity measurement, so summing it in wouldn't mean the same
        // thing as combining these two.
        val combinedCarbsArrayHist: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxCombinedCarbsValue = 0.0

        // CARB MODEL CURVE (optional overlay, off by default) -- two-compartment (Dalla Man-style) Ra(t)
        // absorption model. Peak-time and tail-decay-speed aren't independently tunable in this model
        // (a fixed peak forces a fixed tail shape), so this uses the kgri=kabs simplification: an exact
        // Gamma(2) closed form, Ra(tau) = carbs * f * k^2 * tau * e^(-k*tau), tau = minutes since that
        // meal, k = 1/90 giving t_peak = 1/k = EXACTLY 90min. Hard cutoff at carbModelCutoffMin (6h) --
        // beyond that a meal contributes nothing at all, regardless of how many hours of lookback were
        // queried. This matters for maxCarbModelValue's scaling: that's computed per-refresh from only
        // whatever's inside the current [fromTime, endTime] window, and without a cutoff, an older/larger
        // meal well outside a short (3h/6h) window could still contribute a long, slowly-decaying tail
        // into that window's points -- inconsistent with how the same meal reads once the window widens
        // back out to 12h and the tail becomes a small fraction of the visible range instead. Overlapping
        // meals still superpose naturally since every qualifying (non-cutoff) carb entry's contribution is
        // summed at each point. f=0.9 standard bioavailability. Entirely independent of the empirical
        // carbAbsArrayHist above (this5MinAbsorption-based) -- a calculated overlay, not a measurement.
        // Dashed styling (built below) visually marks it as a model, not data. Computed for the WHOLE
        // display window including future timestamps (unlike carbAbsArrayHist, which can't know unobserved
        // future deviations) -- this curve only needs already-known carb entries and elapsed time, both
        // computable for any timestamp, so it isn't restricted to time <= now.
        val showCarbModel = preferences.get(BooleanKey.ApsAutoIsfShowCarbModelCurve)
        val carbModelArrayHist: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxCarbModelValue = 0.0
        val carbModelK = 1.0 / 90.0 // t_peak = 1/k = 90min, matching the comment above (was 70min)
        val carbModelF = 0.9
        val carbModelCutoffMin = 360.0 // 6h hard cutoff -- see comment above
        val carbModelQueryLookbackMs = T.hours(6).msecs() // matches carbModelCutoffMin -- no point querying further back
        val recentCarbs: List<CA> = if (showCarbModel)
            persistenceLayer.getCarbsFromTimeToTimeExpanded(fromTime - carbModelQueryLookbackMs, endTime, ascending = true)
        else emptyList()

        val bgiArrayHist: MutableList<ScaledDataPoint> = ArrayList()
        val bgiArrayPrediction: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxBGIValue = Double.MIN_VALUE

        val devArray: MutableList<DeviationDataPoint> = ArrayList()
        data.overviewData.maxDevValueFound = Double.MIN_VALUE

        val ratioArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxRatioValueFound = 5.0                    //even if sens data equals 0 for all the period, minimum scale is between 95% and 105%
        data.overviewData.minRatioValueFound = -5.0

        val dsMaxArray: MutableList<ScaledDataPoint> = ArrayList()
        val dsMinArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxFromMaxValueFound = Double.MIN_VALUE
        data.overviewData.maxFromMinValueFound = Double.MIN_VALUE

        val adsData = data.iobCobCalculator.ads.clone()

        val toUnits = if (profileUtil.units == GlucoseUnit.MGDL) 1.0 else Constants.MGDL_TO_MMOLL

        while (time <= endTime) {
            if (isStopped) return Result.failure(workDataOf("Error" to "stopped"))
            val progress = (time - fromTime).toDouble() / (endTime - fromTime) * 100.0
            rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_IOB_AUTOSENS_DATA, progress.toInt(), null))
            val profile = profileFunction.getProfile(time)
            if (profile == null) {
                time += 5 * 60 * 1000L
                continue
            }
            // IOB
            val iob = data.iobCobCalculator.calculateFromTreatmentsAndTemps(time, profile)
            val baseBasalIob = data.iobCobCalculator.calculateAbsoluteIobFromBaseBasals(time)
            val absIob = IobTotal.combine(iob, baseBasalIob)
            val autosensData = adsData.getAutosensDataAtTime(time)
            if (abs(lastIob - iob.iob) > 0.02) {
                if (abs(lastIob - iob.iob) > 0.2) iobArray.add(ScaledDataPoint(time, lastIob, data.overviewData.iobScale))
                iobArray.add(ScaledDataPoint(time, iob.iob, data.overviewData.iobScale))
                data.overviewData.maxIobValueFound = maxOf(data.overviewData.maxIobValueFound, abs(iob.iob))
                lastIob = iob.iob
            }
            if (abs(absLastIob - absIob.iob) > 0.02) {
                if (abs(absLastIob - absIob.iob) > 0.2) absIobArray.add(ScaledDataPoint(time, absLastIob, data.overviewData.iobScale))
                absIobArray.add(ScaledDataPoint(time, absIob.iob, data.overviewData.iobScale))
                data.overviewData.maxIobValueFound = maxOf(data.overviewData.maxIobValueFound, abs(absIob.iob))
                absLastIob = absIob.iob
            }

            // COB
            if (autosensData != null) {
                val cob = autosensData.cob.toInt()
                if (cob != lastCob) {
                    if (autosensData.carbsFromBolus != 0.0) cobArray.add(ScaledDataPoint(time, lastCob.toDouble(), data.overviewData.cobScale))
                    cobArray.add(ScaledDataPoint(time, cob.toDouble(), data.overviewData.cobScale))
                    data.overviewData.maxCobValueFound = max(data.overviewData.maxCobValueFound, cob.toDouble())
                    lastCob = cob
                }
                if (autosensData.failOverToMinAbsorptionRate) {
                    minFailOverActiveList.add(AutosensDataPoint(autosensData, data.overviewData.cobScale, time, rh))
                }
                // CARBS ABSORPTION (rate, g/5min) -- history only, same split point as activity.
                // Exponentially smoothed (see carbAbsAlpha/carbAbsEma above) -- the raw per-bucket rate
                // is too jagged to read at a glance.
                if (time <= now) {
                    val rawAbs = autosensData.this5MinAbsorption
                    carbAbsEma = carbAbsEma?.let { it + carbAbsAlpha * (rawAbs - it) } ?: rawAbs
                    val smoothedAbs = carbAbsEma!!
                    carbAbsArrayHist.add(ScaledDataPoint(time, smoothedAbs, data.overviewData.carbAbsorptionScale))
                    data.overviewData.maxCarbAbsorptionValue = max(data.overviewData.maxCarbAbsorptionValue, abs(smoothedAbs))

                    // UAM Carb Impact + Combined Carbs (see setup comment above) -- nearest AIV row to
                    // this bucket within 4 min; skipped (not zero-filled) if none found, same as a
                    // missing bucket anywhere else in this file, rather than dragging the EMA toward 0.
                    val nearestAiv = autoIsfResults.minByOrNull { abs(it.timestamp - time) }
                    if (nearestAiv != null && abs(nearestAiv.timestamp - time) <= 4 * 60 * 1000L) {
                        val rawUam = nearestAiv.uamCarbImpact
                        uamCarbEma = uamCarbEma?.let { it + uamCarbAlpha * (rawUam - it) } ?: rawUam
                        val smoothedUam = uamCarbEma!!
                        uamCarbImpactArrayHist.add(ScaledDataPoint(time, smoothedUam, data.overviewData.uamCarbImpactScale))
                        data.overviewData.maxUamCarbImpactValue = max(data.overviewData.maxUamCarbImpactValue, abs(smoothedUam))

                        // uci (source of uamCarbImpact) is the SAME deviation formula as ci, but kept as
                        // a separate unclamped copy in DetermineBasalAutoISF.kt -- ci gets capped to
                        // maxCI (30g/h -> 2.5g/5min) before it ever reaches the empirical absorption
                        // line, uci never does. Left uncapped on the standalone UAM line above (a large
                        // spike there is itself useful diagnostic info), but capped here to that same
                        // 2.5g/5min ceiling for the SUM specifically -- otherwise one big unclamped
                        // deviation dominates the total and the empirical (clamped, physiologically-
                        // bounded) component becomes visually negligible next to it.
                        val uamContribution = smoothedUam.coerceAtMost(2.5)
                        val combined = smoothedAbs + uamContribution
                        combinedCarbsArrayHist.add(ScaledDataPoint(time, combined, data.overviewData.combinedCarbsScale))
                        data.overviewData.maxCombinedCarbsValue = max(data.overviewData.maxCombinedCarbsValue, abs(combined))
                    }
                }
                // BGI
                val devBgiScale = overviewMenus.isEnabledIn(OverviewMenus.CharType.DEV) == overviewMenus.isEnabledIn(OverviewMenus.CharType.BGI)
                val deviation = if (devBgiScale) autosensData.deviation else 0.0
                val sens = autosensData.sens
                val bgi: Double = iob.activity * sens * 5.0
                if (time <= now) bgiArrayHist.add(ScaledDataPoint(time, bgi, data.overviewData.bgiScale))
                else bgiArrayPrediction.add(ScaledDataPoint(time, bgi, data.overviewData.bgiScale))
                data.overviewData.maxBGIValue = max(data.overviewData.maxBGIValue, max(abs(bgi), deviation))

                // DEVIATIONS
                var color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationBlackColor)  // "="
                if (autosensData.type == "" || autosensData.type == "non-meal") {
                    if (autosensData.pastSensitivity == "C") color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationGreyColor)
                    if (autosensData.pastSensitivity == "+") color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationGreenColor)
                    if (autosensData.pastSensitivity == "-") color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationRedColor)
                } else if (autosensData.type == "uam") {
                    color = rh.gac(ctx, app.aaps.core.ui.R.attr.uamColor)
                } else if (autosensData.type == "csf") {
                    color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationGreyColor)
                }
                devArray.add(DeviationDataPoint(time.toDouble(), autosensData.deviation, color, data.overviewData.devScale))
                data.overviewData.maxDevValueFound = maxOf(data.overviewData.maxDevValueFound, abs(autosensData.deviation), abs(bgi))
            }

            // ACTIVITY
            if (time <= now) actArrayHist.add(ScaledDataPoint(time, iob.activity, data.overviewData.actScale))
            else actArrayPrediction.add(ScaledDataPoint(time, iob.activity, data.overviewData.actScale))
            rawActPoints.add(iob.activity)
            data.overviewData.maxIAValue = max(data.overviewData.maxIAValue, abs(iob.activity))

            // CARB MODEL CURVE (optional -- see setup comment above). Computed across the whole display
            // window, past AND future -- no time <= now restriction, unlike carbAbsArrayHist, since this
            // is a pure calculation from already-known carb entries, not something needing unobserved
            // future deviations.
            if (showCarbModel) {
                var raPer5Min = 0.0
                for (carbEntry in recentCarbs) {
                    val tauMin = (time - carbEntry.timestamp) / 60_000.0
                    if (tauMin > 0.0 && tauMin <= carbModelCutoffMin) {
                        raPer5Min += 5.0 * carbEntry.amount * carbModelF * carbModelK * carbModelK * tauMin * exp(-carbModelK * tauMin)
                    }
                }
                carbModelArrayHist.add(ScaledDataPoint(time, raPer5Min, data.overviewData.carbModelScale))
                data.overviewData.maxCarbModelValue = max(data.overviewData.maxCarbModelValue, raPer5Min)
            }

            // RATIO
            if (autosensData != null) {
                ratioArray.add(ScaledDataPoint(time, 100.0 * (autosensData.autosensResult.ratio - 1), data.overviewData.ratioScale))
                data.overviewData.maxRatioValueFound = max(data.overviewData.maxRatioValueFound, 100.0 * (autosensData.autosensResult.ratio - 1))
                data.overviewData.minRatioValueFound = min(data.overviewData.minRatioValueFound, 100.0 * (autosensData.autosensResult.ratio - 1))
            }

            // DEV SLOPE
            if (autosensData != null) {
                dsMaxArray.add(ScaledDataPoint(time, autosensData.slopeFromMaxDeviation, data.overviewData.dsMaxScale))
                dsMinArray.add(ScaledDataPoint(time, autosensData.slopeFromMinDeviation, data.overviewData.dsMinScale))
                data.overviewData.maxFromMaxValueFound = max(data.overviewData.maxFromMaxValueFound, abs(autosensData.slopeFromMaxDeviation))
                data.overviewData.maxFromMinValueFound = max(data.overviewData.maxFromMinValueFound, abs(autosensData.slopeFromMinDeviation))
            }

            time += 5 * 60 * 1000L
        }

        // IOB_TH -- reuses autoIsfResults fetched earlier (above the main loop, for the UAM/combined
        // carbs bucket lookups) rather than querying the same range twice.
        val iobThArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxIobThValueFound = Double.MIN_VALUE
        data.overviewData.minIobThValueFound = Double.MAX_VALUE
        autoIsfResults.forEach {
            it.iobThEffective.let { iobThEffective ->
                iobThArray.add(ScaledDataPoint(it.timestamp, iobThEffective, data.overviewData.iobThScale))
                data.overviewData.maxIobThValueFound = max(data.overviewData.maxIobThValueFound, iobThEffective)
                data.overviewData.minIobThValueFound = min(data.overviewData.minIobThValueFound, iobThEffective)
            }
        }
        //aapsLogger.debug(LTag.APS, "iob_TH min/max range is ${data.overviewData.minIobThValueFound} to ${data.overviewData.maxIobThValueFound}")
        data.overviewData.iobThSeries = LineGraphSeries(Array(iobThArray.size) { i -> iobThArray[i] }).also {
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
                paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.iobThColor)
            })
        }

        // IOB
        data.overviewData.iobSeries = FixedLineGraphSeries(Array(iobArray.size) { i -> iobArray[i] }).also {
            it.isDrawBackground = true
            it.backgroundColor = -0x7f000001 and rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor)  //50%
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor)
            it.thickness = 3
        }
        data.overviewData.absIobSeries = FixedLineGraphSeries(Array(absIobArray.size) { i -> absIobArray[i] }).also {
            it.isDrawBackground = true
            it.backgroundColor = -0x7f000001 and rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor) //50%
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor)
            it.thickness = 3
        }

        if (overviewMenus.setting[0][OverviewMenus.CharType.PRE.ordinal]) {
            val autosensData = adsData.getLastAutosensData("GraphData", aapsLogger, dateUtil)
            val lastAutosensResult = autosensData?.autosensResult ?: AutosensResult()
            val isTempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null
            val iobPrediction: MutableList<DataPointWithLabelInterface> = ArrayList()
            val iobPredictionArray = data.iobCobCalculator.calculateIobArrayForSMB(lastAutosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
            for (i in iobPredictionArray) {
                iobPrediction.add(IobTotalDataPoint(i).setColor(rh.gac(ctx, app.aaps.core.ui.R.attr.iobPredASColor)))
                data.overviewData.maxIobValueFound = max(data.overviewData.maxIobValueFound, abs(i.iob))
            }
            if (overviewMenus.setting[0][OverviewMenus.CharType.IOB_TH.ordinal]) {
                data.overviewData.maxIobValueFound = max(data.overviewData.maxIobValueFound, abs(data.overviewData.maxIobValueFound))
                data.overviewData.maxIobThValueFound = data.overviewData.maxIobValueFound
            }

            data.overviewData.iobPredictions1Series = PointsWithLabelGraphSeries(Array(iobPrediction.size) { i -> iobPrediction[i] })
            aapsLogger.debug(LTag.AUTOSENS, "IOB prediction for AS=" + decimalFormatter.to2Decimal(lastAutosensResult.ratio) + ": " + data.iobCobCalculator.iobArrayToString(iobPredictionArray))
        } else {
            data.overviewData.iobPredictions1Series = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        }

        // COB
        data.overviewData.cobSeries = FixedLineGraphSeries(Array(cobArray.size) { i -> cobArray[i] }).also {
            it.isDrawBackground = true
            it.backgroundColor = -0x7f000001 and rh.gac(ctx, app.aaps.core.ui.R.attr.cobColor) //50%
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.cobColor)
            it.thickness = 3
        }
        data.overviewData.cobMinFailOverSeries = PointsWithLabelGraphSeries(Array(minFailOverActiveList.size) { i -> minFailOverActiveList[i] })

        // ACTIVITY peak labels — only the genuinely dominant peaks, not one per local bump. A point first
        // has to be a local peak at all (strictly higher than its predecessor, at least as high as its
        // successor — picks the leading edge of a flat-topped plateau, not every point along it), then it
        // only gets a label if ALL of: (1) it's over 75% of the day's overall maximum peak (maxIAValue),
        // (2) it's the maximum activity value within the trailing 6-hour window ending at its own time —
        // i.e. nothing taller occurred in the preceding 6 hours, and (3) at least 2 hours have passed
        // since the last point that itself got a label — a hard repeat-rate cap on top of (1)/(2), evaluated
        // sequentially in chronological order (allActPoints/rawActPoints are already time-ordered) so an
        // accepted label's own timestamp becomes the cooldown baseline for everything after it.
        val allActPoints = actArrayHist + actArrayPrediction
        val sixHoursMs = 6 * 60 * 60 * 1000L
        val twoHoursMs = 2 * 60 * 60 * 1000L
        // Nullable, not Long.MIN_VALUE — subtracting a real timestamp from Long.MIN_VALUE overflows
        // (wraps to a huge negative number instead of "practically infinite gap"), which made the very
        // first candidate always fail the cooldown check below and, since lastLabeledTimestamp then never
        // got set, every candidate after it too — no peak was ever labeled. null cleanly means "nothing
        // labeled yet, don't gate the first candidate at all."
        var lastLabeledTimestamp: Long? = null
        val peakIndices = allActPoints.indices.filter { i ->
            val raw = abs(rawActPoints[i])
            val isLocalPeak = (i == 0 || raw > abs(rawActPoints[i - 1])) &&
                (i == allActPoints.size - 1 || raw >= abs(rawActPoints[i + 1]))
            if (!isLocalPeak) return@filter false
            if (raw <= data.overviewData.maxIAValue * 0.75) return@filter false
            val windowStart = allActPoints[i].x - sixHoursMs
            val maxInTrailingWindow = allActPoints.indices
                .filter { j -> allActPoints[j].x in windowStart..allActPoints[i].x }
                .maxOf { j -> abs(rawActPoints[j]) }
            if (raw < maxInTrailingWindow) return@filter false
            val thisTimestamp = allActPoints[i].x.toLong()
            val sinceLast = lastLabeledTimestamp
            if (sinceLast != null && thisTimestamp - sinceLast < twoHoursMs) return@filter false
            lastLabeledTimestamp = thisTimestamp
            true
        }
        data.overviewData.activityPeakSeries = if (peakIndices.isNotEmpty() && data.overviewData.maxIAValue > 0.0) {
            PointsWithLabelGraphSeries(
                peakIndices.map { i ->
                    val point = allActPoints[i]
                    ActivityPeakDataPoint(point.x, point.y, decimalFormatter.to2Decimal(rawActPoints[i] * 60.0), rh) as DataPointWithLabelInterface
                }.toTypedArray()
            )
        } else {
            PointsWithLabelGraphSeries()
        }

        // ACTIVITY
        data.overviewData.activitySeries = FixedLineGraphSeries(Array(actArrayHist.size) { i -> actArrayHist[i] }).also {
            it.isDrawBackground = false
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.activityColor)
            it.thickness = 3
        }
        data.overviewData.activityPredictionSeries = FixedLineGraphSeries(Array(actArrayPrediction.size) { i -> actArrayPrediction[i] }).also {
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
                paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.activityColor)
            })
        }

        // CARBS ABSORPTION -- dashed (trying this alongside the already-dashed carb model curve below,
        // rather than solid -- easier to trace visually against the grid at a glance).
        data.overviewData.carbAbsorptionSeries = FixedLineGraphSeries(Array(carbAbsArrayHist.size) { i -> carbAbsArrayHist[i] }).also {
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
                paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.carbAbsorptionColor)
            })
        }

        // CARB MODEL CURVE -- dashed, distinct color, to visually mark it as a calculated model overlay
        // rather than measured data (same convention as activity's own dashed prediction segment).
        data.overviewData.carbModelSeries = FixedLineGraphSeries(Array(carbModelArrayHist.size) { i -> carbModelArrayHist[i] }).also {
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
                paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.carbModelCurveColor)
            })
        }

        // UAM CARB IMPACT -- dashed, distinct color, to mark it as inferred/calculated (deviation-based)
        // rather than measured or logged, same convention as the carb model curve above.
        data.overviewData.uamCarbImpactSeries = FixedLineGraphSeries(Array(uamCarbImpactArrayHist.size) { i -> uamCarbImpactArrayHist[i] }).also {
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
                paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.uamCarbImpactColor)
            })
        }

        // COMBINED CARBS -- carbAbsorptionSeries + uamCarbImpactSeries summed at matching bucket
        // timestamps (see setup comment near carbAbsAlpha above). Solid (not dashed), distinct color --
        // it's a derived total of the two component lines above, not itself another inferred model.
        data.overviewData.combinedCarbsSeries = FixedLineGraphSeries(Array(combinedCarbsArrayHist.size) { i -> combinedCarbsArrayHist[i] }).also {
            it.isDrawBackground = false
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.combinedCarbsColor)
            it.thickness = 5
        }

        // BGI
        data.overviewData.minusBgiSeries = FixedLineGraphSeries(Array(bgiArrayHist.size) { i -> bgiArrayHist[i] }).also {
            it.isDrawBackground = false
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.bgiColor)
            it.thickness = 3
        }
        data.overviewData.minusBgiHistSeries = FixedLineGraphSeries(Array(bgiArrayPrediction.size) { i -> bgiArrayPrediction[i] }).also {
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
                paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.bgiColor)
            })
        }

        // DEVIATIONS
        data.overviewData.deviationsSeries = BarGraphSeries(Array(devArray.size) { i -> devArray[i] }).also {
            it.setValueDependentColor { data: DeviationDataPoint -> data.color }
        }

        // RATIO
        data.overviewData.ratioSeries = LineGraphSeries(Array(ratioArray.size) { i -> ratioArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.ratioColor)
            it.thickness = 3
        }

        // DEV SLOPE
        data.overviewData.dsMaxSeries = LineGraphSeries(Array(dsMaxArray.size) { i -> dsMaxArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.devSlopePosColor)
            it.thickness = 3
        }
        data.overviewData.dsMinSeries = LineGraphSeries(Array(dsMinArray.size) { i -> dsMinArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.devSlopeNegColor)
            it.thickness = 3
        }

        // VAR_SENS
        val varSensArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxVarSensValueFound = Double.MIN_VALUE
        data.overviewData.minVarSensValueFound = Double.MAX_VALUE
        val apsResults = persistenceLayer.getApsResults(fromTime, endTime)
        apsResults.forEach {
            it.variableSens?.let { variableSens ->
                val varSens = profileUtil.fromMgdlToUnits(variableSens)
                varSensArray.add(ScaledDataPoint(it.date, varSens, data.overviewData.varSensScale))
                data.overviewData.maxVarSensValueFound = max(data.overviewData.maxVarSensValueFound, varSens)
                data.overviewData.minVarSensValueFound = min(data.overviewData.minVarSensValueFound, varSens)
            }
        }
        data.overviewData.varSensSeries = LineGraphSeries(Array(varSensArray.size) { i -> varSensArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.ratioColor)
            it.thickness = 3
        }

        // AUTO_ISF
        // BG PARABOLA
        if (overviewMenus.isActiveCharTypeData(0,OverviewMenus.CharType.BG_PARAB.ordinal)) {
            val bgParabolaArrayHist: MutableList<ScaledDataPoint> = ArrayList()
            val bgParabolaArrayPrediction: MutableList<ScaledDataPoint> = ArrayList()
            val glucoseStatus = glucoseStatusProvider.glucoseStatusData as GlucoseStatusAutoIsf?    //glucoseStatusProvider.glucoseStatusData
            val corr = glucoseStatus?.corrSqu ?: 0.0
            if (corr > 0.0) {
                val a0 = glucoseStatus!!.a0
                val a1 = glucoseStatus.a1
                val a2 = glucoseStatus.a2
                // parabola extrapolation
                for (i in 0 until 21 step 5) {
                    val timestamp = now + (i * 60 * 1000).toLong()
                    val value = a0 + a1 * i / 5 + a2 * i * i / 25
                    bgParabolaArrayPrediction.add(ScaledDataPoint(timestamp, value * toUnits, data.overviewData.bgParabolaScale))
                }
                // fitted parabola
                val dur = (glucoseStatus.parabolaMinutes).toInt()
                for (i in -dur until 1 step 5) {
                    val timestamp = now + (i * 60 * 1000).toLong()
                    val value = a0 + a1 * i / 5 + a2 * i * i / 25
                    bgParabolaArrayHist.add(ScaledDataPoint(timestamp, value * toUnits, data.overviewData.bgParabolaScale))
                }
            }
            data.overviewData.bgParabolaSeries = FixedLineGraphSeries(Array(bgParabolaArrayHist.size) { i -> bgParabolaArrayHist[i] }).also {
                it.isDrawBackground = false
                it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.bgParabolaColor)
                it.thickness = 14
            }
            data.overviewData.bgParabolaPredictionSeries = FixedLineGraphSeries(Array(bgParabolaArrayPrediction.size) { i -> bgParabolaArrayPrediction[i] }).also {
                it.setCustomPaint(Paint().also { paint ->
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 14f
                    paint.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
                    paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.bgParabolaColor)
                })
            }
        }
        val acceIsfArray: MutableList<ScaledDataPoint> = ArrayList()
        val bgIsfArray: MutableList<ScaledDataPoint> = ArrayList()
        val ppIsfArray: MutableList<ScaledDataPoint> = ArrayList()
        val duraIsfArray: MutableList<ScaledDataPoint> = ArrayList()
        val finalIsfArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxAcceIsfValueFound = 1.0
        data.overviewData.minAcceIsfValueFound = 1.0
        data.overviewData.maxBgIsfValueFound = 1.0
        data.overviewData.minBgIsfValueFound = 1.0
        data.overviewData.maxPpIsfValueFound = 1.0
        data.overviewData.minPpIsfValueFound = 1.0
        data.overviewData.maxDuraIsfValueFound = 1.0
        data.overviewData.minDuraIsfValueFound = 1.0
        data.overviewData.maxFinalIsfValueFound = 1.0
        data.overviewData.minFinalIsfValueFound = 1.0
        autoIsfResults.forEach {
            it.acceIsf.let { acceIsf ->
                acceIsfArray.add(ScaledDataPoint(it.timestamp, acceIsf, data.overviewData.acceIsfScale))
                data.overviewData.maxAcceIsfValueFound = max(data.overviewData.maxAcceIsfValueFound, acceIsf)
                data.overviewData.minAcceIsfValueFound = min(data.overviewData.minAcceIsfValueFound, acceIsf)
            }
            it.bgIsf.let { bgIsf ->
                bgIsfArray.add(ScaledDataPoint(it.timestamp, bgIsf, data.overviewData.bgIsfScale))
                data.overviewData.maxBgIsfValueFound = max(data.overviewData.maxBgIsfValueFound, bgIsf)
                data.overviewData.minBgIsfValueFound = min(data.overviewData.minBgIsfValueFound, bgIsf)
            }
            it.ppIsf.let { ppIsf ->
                ppIsfArray.add(ScaledDataPoint(it.timestamp, ppIsf, data.overviewData.ppIsfScale))
                data.overviewData.maxPpIsfValueFound = max(data.overviewData.maxPpIsfValueFound, ppIsf)
                data.overviewData.minPpIsfValueFound = min(data.overviewData.minPpIsfValueFound, ppIsf)
            }
            it.duraIsf.let { duraIsf ->
                duraIsfArray.add(ScaledDataPoint(it.timestamp, duraIsf, data.overviewData.duraIsfScale))
                data.overviewData.maxDuraIsfValueFound = max(data.overviewData.maxDuraIsfValueFound, duraIsf)
                data.overviewData.minDuraIsfValueFound = min(data.overviewData.minDuraIsfValueFound, duraIsf)
            }
           it.finalIsf.let { finalIsf ->
                finalIsfArray.add(ScaledDataPoint(it.timestamp, finalIsf, data.overviewData.finalIsfScale))
                data.overviewData.maxFinalIsfValueFound = max(data.overviewData.maxFinalIsfValueFound, finalIsf)
                data.overviewData.minFinalIsfValueFound = min(data.overviewData.minFinalIsfValueFound, finalIsf)
            }
        }
        //aapsLogger.debug(LTag.APS, "acce_ISF min/max range is ${data.overviewData.minAcceIsfValueFound} to ${data.overviewData.maxAcceIsfValueFound}")
        //aapsLogger.debug(LTag.APS, "bg_ISF min/max range is ${data.overviewData.minBgIsfValueFound} to ${data.overviewData.maxBgIsfValueFound}")
        //aapsLogger.debug(LTag.APS, "pp_ISF min/max range is ${data.overviewData.minPpIsfValueFound} to ${data.overviewData.maxPpIsfValueFound}")
        //aapsLogger.debug(LTag.APS, "dura_ISF min/max range is ${data.overviewData.minDuraIsfValueFound} to ${data.overviewData.maxDuraIsfValueFound}")
        //aapsLogger.debug(LTag.APS, "final_ISF min/max range is ${data.overviewData.minFinalIsfValueFound} to ${data.overviewData.maxFinalIsfValueFound}")
        data.overviewData.acceIsfSeries = LineGraphSeries(Array(acceIsfArray.size) { i -> acceIsfArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.acceIsfColor)
            it.thickness = 3
        }
        data.overviewData.bgIsfSeries = LineGraphSeries(Array(bgIsfArray.size) { i -> bgIsfArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.bgIsfColor)
            it.thickness = 3
        }
        data.overviewData.ppIsfSeries = LineGraphSeries(Array(ppIsfArray.size) { i -> ppIsfArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.ppIsfColor)
            it.thickness = 3
        }
        data.overviewData.duraIsfSeries = LineGraphSeries(Array(duraIsfArray.size) { i -> duraIsfArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.duraIsfColor)
            it.thickness = 3
        }
        data.overviewData.finalIsfSeries = LineGraphSeries(Array(finalIsfArray.size) { i -> finalIsfArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.finalIsfColor)
            it.thickness = 8
        }

        rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_IOB_AUTOSENS_DATA, 100, null))
        return Result.success()
    }
}