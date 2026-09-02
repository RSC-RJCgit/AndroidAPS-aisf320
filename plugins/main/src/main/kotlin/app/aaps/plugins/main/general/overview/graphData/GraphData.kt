package app.aaps.plugins.main.general.overview.graphData

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.graph.data.AreaGraphSeries
import app.aaps.core.graph.data.BarGraphSeries
import app.aaps.core.graph.data.BolusDataPoint
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.DeviationDataPoint
import app.aaps.core.graph.data.DoubleDataPoint
import app.aaps.core.graph.data.EffectiveProfileSwitchDataPoint
import app.aaps.core.graph.data.FixedLineGraphSeries
import app.aaps.core.graph.data.GlucoseValueDataPoint
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.ScaledDataPoint
import app.aaps.core.graph.data.TimeAsXAxisLabelFormatter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.toast.ToastUtils
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.Series
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

@Suppress("UNCHECKED_CAST")
class GraphData @Inject constructor(
    private val profileFunction: ProfileFunction,
    private val preferences: Preferences,
    private val rh: ResourceHelper
) {

    private var maxY = Double.MIN_VALUE
    private var minY = Double.MAX_VALUE
    private val units: GlucoseUnit get() = profileFunction.getUnits()
    private val series: MutableList<Series<*>> = ArrayList()

    private lateinit var graph: GraphView
    private lateinit var overviewData: OverviewData

    fun with(graph: GraphView, overviewData: OverviewData): GraphData = this.also {
        it.graph = graph
        it.overviewData = overviewData
    }

    fun addBucketedData() {
        addSeries(overviewData.bucketedGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addBgReadings(addPredictions: Boolean, context: Context?, drawSeries: Boolean = true) {
        maxY = if (overviewData.bgReadingsArray.isEmpty()) {
            if (units == GlucoseUnit.MGDL) 180.0 else 10.0
        } else overviewData.maxBgValue
        minY = 0.0
        if (drawSeries) {
            addSeries(overviewData.bgReadingGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
            if (addPredictions) addSeries(overviewData.predictionsGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
            (overviewData.bgReadingGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).setOnDataPointTapListener { _, dataPoint ->
                if (dataPoint is GlucoseValueDataPoint) ToastUtils.infoToast(context, dataPoint.label)
            }
        }
    }

    fun addInRangeArea(fromTime: Long, toTime: Long, lowLine: Double, highLine: Double) {
        val inRangeAreaDataPoints = arrayOf(
            DoubleDataPoint(fromTime.toDouble(), lowLine, highLine),
            DoubleDataPoint(toTime.toDouble(), lowLine, highLine)
        )
        addSeries(AreaGraphSeries(inRangeAreaDataPoints).also {
            it.color = 0
            it.isDrawBackground = true
            it.backgroundColor = rh.gac(graph.context, app.aaps.core.ui.R.attr.inRangeBackground)
        })
    }

    fun addBasals() {
        overviewData.basalScale.multiplier = 1.0 // get unscaled Y-values for max calculation
        var maxBasalValue =
            maxOf(0.1, (overviewData.baseBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY, (overviewData.tempBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY)
        maxBasalValue =
            maxOf(
                maxBasalValue,
                (overviewData.basalLineGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY,
                (overviewData.absoluteBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY
            )
        addSeries(overviewData.baseBasalGraphSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.tempBasalGraphSeries as LineGraphSeries<ScaledDataPoint>)
        if (!PointsWithLabelGraphSeries.uniformGreenBg) {
            addSeries(overviewData.tempBasalAcceIsfSeries as LineGraphSeries<ScaledDataPoint>)
            addSeries(overviewData.tempBasalBgIsfSeries as LineGraphSeries<ScaledDataPoint>)
            addSeries(overviewData.tempBasalPpIsfSeries as LineGraphSeries<ScaledDataPoint>)
            addSeries(overviewData.tempBasalDuraIsfSeries as LineGraphSeries<ScaledDataPoint>)
        }
        addSeries(overviewData.basalLineGraphSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.absoluteBasalGraphSeries as LineGraphSeries<ScaledDataPoint>)
        maxY = max(maxY, preferences.get(UnitDoubleKey.OverviewHighMark))
        val scale = preferences.get(UnitDoubleKey.OverviewLowMark) / maxY / 1.2
        overviewData.basalScale.multiplier = maxY * scale / maxBasalValue
    }

    fun addTargetLine() {
        addSeries(overviewData.temporaryTargetSeries as LineGraphSeries<DataPoint>)
    }

    fun addRunningModes() {
        addSeries(overviewData.runningModesSeries as PointsWithLabelGraphSeries<DataPoint>)
    }

    fun addTreatments(context: Context?) {
        maxY = maxOf(maxY, overviewData.maxTreatmentsValue)
        addSeries(overviewData.treatmentsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        (overviewData.treatmentsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is BolusDataPoint) ToastUtils.infoToast(context, dataPoint.label)
        }
    }

    fun addEps(context: Context?, scale: Double) {
        addSeries(overviewData.epsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        (overviewData.epsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is EffectiveProfileSwitchDataPoint) ToastUtils.infoToast(context, dataPoint.data.originalCustomizedName)
        }
        overviewData.epsScale.multiplier = maxY * scale / overviewData.maxEpsValue
    }

    // expandYRange: pulls maxY up to the highest glucose value among therapy events (e.g. a finger-stick
    // or MBG check) — correct on the main (glucose-scaled) graph so those points never get clipped, but
    // wrong on a secondary graph (e.g. an IOB/percentage-scaled one), where a glucose-scale value would
    // badly distort that graph's own intended Y range. Notes' own on-screen position is pixel-based
    // (see PointsWithLabelGraphSeries.GENERAL_WITH_DURATION), not tied to this Y-scale at all, so
    // secondary-graph callers can safely skip the expansion entirely.
    fun addTherapyEvents(expandYRange: Boolean = true) {
        if (expandYRange) maxY = maxOf(maxY, overviewData.maxTherapyEventValue)
        addSeries(overviewData.therapyEventSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    // Plain TE.Type.NOTE events (split out of therapyEventSeries — see OverviewData.kt's noteEventSeries
    // comment). Pixel-positioned like the SMB labels below, not tied to Y-scale, so never expands maxY.
    fun addNoteEvents() {
        addSeries(overviewData.noteEventSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    // Same notes as addNoteEvents(), rendered as plain unscaled arrowheads at graph4's top half.
    fun addNoteArrowheads() {
        addSeries(overviewData.noteArrowheadSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addNoisyBgDeltaAnnotation() {
        addSeries(overviewData.noisyBgDeltaSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    // "pp= acc= du=" row, fixed near the bottom of graph3, same style/mechanism as addNoisyBgDeltaAnnotation()'s row on graph1.
    fun addIsfWeightsRow() {
        addSeries(overviewData.isfWeightsRowSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addA1DeltaAnnotation() {
        addSeries(overviewData.a1DeltaSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addUkfDeltaAnnotation() {
        addSeries(overviewData.ukfDeltaSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addHpAnnotation() {
        addSeries(overviewData.hpSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addIobPeakMainAnnotation() {
        addSeries(overviewData.iobPeakMainSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addTargetOffsetDuTAnnotation() {
        addSeries(overviewData.targetOffsetDuTSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addTargetOffsetDuTGraph1Annotation() {
        addSeries(overviewData.targetOffsetDuTGraph1Series as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addStepsStackedAnnotation() {
        addSeries(overviewData.stepsStackedSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addStepsExtra() {
        addSeries(overviewData.stepsExtraSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addIsfIndices() {
        addSeries(overviewData.isfIndicesSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addActivity(scale: Double) {
        addSeries(overviewData.activitySeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.activityPredictionSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.activityPeakSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        overviewData.actScale.multiplier = maxY * scale / overviewData.maxIAValue
    }

    fun addCarbAbsorption(scale: Double) {
        addSeries(overviewData.carbAbsorptionSeries as FixedLineGraphSeries<ScaledDataPoint>)
        if (overviewData.maxCarbAbsorptionValue > 0.0)
            overviewData.carbAbsorptionScale.multiplier = maxY * scale / overviewData.maxCarbAbsorptionValue
    }

    fun addUamCarbImpact(scale: Double) {
        addSeries(overviewData.uamCarbImpactSeries as FixedLineGraphSeries<ScaledDataPoint>)
        if (overviewData.maxUamCarbImpactValue > 0.0)
            overviewData.uamCarbImpactScale.multiplier = maxY * scale / overviewData.maxUamCarbImpactValue
    }

    fun addCombinedCarbs(scale: Double) {
        addSeries(overviewData.combinedCarbsSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.combinedCarbsPeakSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        // Self-normalised against its own max, same as every other series here (activity, carb
        // absorption, ...). The point is that EVERY line's peak lands at the same height (maxY*scale),
        // so lines can be read against each other for SHAPE and TIMING -- when each rises, where it
        // peaks, how fast it decays -- without differing magnitudes dominating the picture. That is
        // what makes a g/5min carb line and a U/min activity line legible on one panel at all: they
        // have no common unit, so only their profiles over time are meaningfully comparable.
        //
        // Was briefly normalised against maxCarbAbsorptionValue instead, to make the UAM contribution
        // show up as a vertical gap above the orange carb line. That works, but it is the opposite
        // trade: it fixes this line's height relative to the carb line and so breaks the equal-peaks
        // property against everything else on the panel. Reverted deliberately -- shape/timing
        // comparison across all lines was preferred over magnitude comparison against one of them.
        // Consequence to be aware of: with equal peaks restored, any constant multiplier applied to
        // the value upstream cancels out exactly ((K*v)/(K*max) == v/max), so uamShareOfSumFactor in
        // PrepareIobAutosensGraphDataWorker.kt is now the only knob that changes what is drawn -- it
        // alters the line's SHAPE (the mix of the two components) rather than its height.
        if (overviewData.maxCombinedCarbsValue > 0.0)
            overviewData.combinedCarbsScale.multiplier = maxY * scale / overviewData.maxCombinedCarbsValue
    }

    fun addCarbModelCurve(scale: Double) {
        addSeries(overviewData.carbModelSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.carbModelPeakSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        if (overviewData.maxCarbModelValue > 0.0)
            overviewData.carbModelScale.multiplier = maxY * scale / overviewData.maxCarbModelValue
    }

    fun addRawBg(useForScale: Boolean) {
        if (useForScale) {
            minY = 0.0
            maxY = overviewData.maxBgValue
        }
        // Split into color-banded segments (red / yellow low / yellow high) rather than one series —
        // see PrepareBgDataWorker.kt.
        overviewData.rawBgSeries.forEach { addSeries(it as LineGraphSeries<*>) }
    }

    // UKF-smoothed trace of the same raw/noise values as addRawBg() — own checkbox (RAW_BG_SMOOTHED),
    // independently selectable from RAW_BG. See PrepareBgDataWorker.kt.
    fun addRawBgSmoothed(useForScale: Boolean) {
        if (useForScale) {
            minY = 0.0
            maxY = overviewData.maxBgValue
        }
        addSeries(overviewData.rawBgSmoothedSeries as LineGraphSeries<*>)
        // With UKF2 this is the original LibreSpecial EMA immediately before UKF; otherwise empty.
        // It shares this graph selection so no saved chart-menu ordinals/configurations are disturbed.
        addSeries(overviewData.libreSpecialPreUkfSeries as LineGraphSeries<*>)
        // UKF3 (display-only, always populated regardless of any toggle): LibreSpecial EMA run against
        // UKF1's own output instead of raw values. Same shared-selection reasoning as libreSpecialPreUkfSeries.
        addSeries(overviewData.libreSpecialFromUkf1Series as LineGraphSeries<*>)
    }

    // Graph5-only counterpart of addRawBgSmoothed() above: UKF1 only (UKF2/UKF3 lines stopped
    // 2026-09-02). Still independent of graph 0's ShowUkf1Graph toggle.
    fun addRawBgSmoothedGraph5(useForScale: Boolean) {
        if (useForScale) {
            minY = 0.0
            maxY = overviewData.maxBgValue
        }
        addSeries(overviewData.rawBgSmoothedSeriesGraph5 as LineGraphSeries<*>)
        addSeries(overviewData.libreSpecialPreUkfSeriesGraph5 as LineGraphSeries<*>)
        addSeries(overviewData.libreSpecialFromUkf1SeriesGraph5 as LineGraphSeries<*>)
    }

    fun addBgParabola(addPredictions: Boolean, scale: Double) {
        addSeries(overviewData.bgParabolaSeries as FixedLineGraphSeries<ScaledDataPoint>)
        if (addPredictions) addSeries(overviewData.bgParabolaPredictionSeries as FixedLineGraphSeries<ScaledDataPoint>)
        overviewData.bgParabolaScale.multiplier = maxY * scale / overviewData.maxBgValue
    }

    //Function below show -BGI to be able to compare curves with deviations
    fun addMinusBGI(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxBGIValue
            minY = -overviewData.maxBGIValue
        }
        overviewData.bgiScale.multiplier = maxY * scale / overviewData.maxBGIValue
        addSeries(overviewData.minusBgiSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.minusBgiHistSeries as FixedLineGraphSeries<ScaledDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addSmbLabels() {
        addSeries(overviewData.smbLabelSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    // Total-SMB-per-stack labels, fixed at the base of this graph, white. See PrepareTreatmentsDataWorker.kt.
    fun addSmbStackTotalLabels() {
        addSeries(overviewData.smbStackTotalSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addIobTh(useForScale: Boolean, scale: Double, maxCommonIob: Double) {
        if (maxCommonIob>0.0) {
            maxY = maxCommonIob
            minY = -maxY
        } else if (useForScale) {
            maxY =max(overviewData.maxIobThValueFound, maxCommonIob)
            minY = -maxY    //overviewData.maxIobThValueFound
        }
        overviewData.iobThScale.multiplier = maxY * scale / max(overviewData.maxIobThValueFound, maxCommonIob)
        addSeries(overviewData.iobThSeries as LineGraphSeries<ScaledDataPoint>)
        //addSeries(overviewData.iobPredictions2Series)
    }

    // scale in % of vertical size (like 0.3)
    fun addIob(useForScale: Boolean, scale: Double, maxCommonIob: Double) {
        if (maxCommonIob>0.0) {
            maxY = maxCommonIob
            minY = -maxY
        } else if (useForScale) {
            maxY = max(overviewData.maxIobValueFound, maxCommonIob)
            minY = -maxY    //overviewData.maxIobValueFound
        }
        overviewData.iobScale.multiplier = maxY * scale / max(overviewData.maxIobValueFound, maxCommonIob)      //overviewData.maxIobValueFound
        addSeries(overviewData.iobSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.iobPredictions1Series as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        addSeries(overviewData.iobPeakSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        //addSeries(overviewData.iobPredictions2Series)
    }

    // scale in % of vertical size (like 0.3)
    fun addAbsIob(useForScale: Boolean, scale: Double, maxCommonIob: Double) {
        if (maxCommonIob>0.0) {
            maxY = maxCommonIob
            minY = -maxY
        } else if (useForScale) {
            maxY = max(overviewData.maxIobValueFound, maxCommonIob)
            minY = -maxY    //overviewData.maxIobValueFound
        }
        overviewData.iobScale.multiplier = maxY * scale / max(overviewData.maxIobValueFound, maxCommonIob)
        addSeries(overviewData.absIobSeries as FixedLineGraphSeries<ScaledDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addCob(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxCobValueFound
            minY = -overviewData.maxCobValueFound
        }
        overviewData.cobScale.multiplier = maxY * scale / overviewData.maxCobValueFound
        addSeries(overviewData.cobSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.cobMinFailOverSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    // scale in % of vertical size (like 0.3)
    fun addDeviations(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxDevValueFound
            minY = -maxY
        }
        overviewData.devScale.multiplier = maxY * scale / overviewData.maxDevValueFound
        addSeries(overviewData.deviationsSeries as BarGraphSeries<DeviationDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addRatio(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = 100.0 + max(overviewData.maxRatioValueFound, abs(overviewData.minRatioValueFound))
            minY = 100.0 - max(overviewData.maxRatioValueFound, abs(overviewData.minRatioValueFound))
            overviewData.ratioScale.multiplier = 1.0
            overviewData.ratioScale.shift = 100.0
        } else {
            overviewData.ratioScale.multiplier = maxY * scale / max(overviewData.maxRatioValueFound, abs(overviewData.minRatioValueFound))
            overviewData.ratioScale.shift = 0.0
        }
        addSeries(overviewData.ratioSeries as LineGraphSeries<ScaledDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addDeviationSlope(useForScale: Boolean, scale: Double, isRatioScale: Boolean = false) {
        if (useForScale) {
            maxY = max(overviewData.maxFromMaxValueFound, overviewData.maxFromMinValueFound)
            minY = -maxY
        }
        var graphMaxY = maxY
        if (isRatioScale) {
            graphMaxY = maxY - 100.0
            overviewData.dsMinScale.shift = 100.0
            overviewData.dsMaxScale.shift = 100.0
        } else {
            overviewData.dsMinScale.shift = 0.0
            overviewData.dsMaxScale.shift = 0.0
        }
        overviewData.dsMaxScale.multiplier = graphMaxY * scale / overviewData.maxFromMaxValueFound
        overviewData.dsMinScale.multiplier = graphMaxY * scale / overviewData.maxFromMinValueFound
        addSeries(overviewData.dsMaxSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.dsMinSeries as LineGraphSeries<ScaledDataPoint>)
    }

    // scale in % of vertical size (like 0.3)
    fun addNowLine(now: Long) {
        val nowPoints = arrayOf(
            DataPoint(now.toDouble(), 0.0),
            DataPoint(now.toDouble(), maxY)
        )
        addSeries(LineGraphSeries(nowPoints).also {
            it.isDrawDataPoints = false
            // custom paint to make a dotted line
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
                paint.color = rh.gac(graph.context, app.aaps.core.ui.R.attr.dotLineColor)
            })
        })
    }

    // scale in % of vertical size (like 0.3)
    fun addVarSens(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxVarSensValueFound
            minY = overviewData.minVarSensValueFound
        }
        overviewData.varSensScale.multiplier = maxY * scale / overviewData.maxVarSensValueFound
        addSeries(overviewData.varSensSeries as LineGraphSeries<ScaledDataPoint>)
    }

    fun setNumVerticalLabels() {
        graph.gridLabelRenderer.numVerticalLabels = max(3, if (units == GlucoseUnit.MGDL) (maxY / 40 + 1).toInt() else (maxY / 2 + 1).toInt())
    }

    fun formatAxis(fromTime: Long, endTime: Long) {
        graph.viewport.setMaxX(endTime.toDouble())
        graph.viewport.setMinX(fromTime.toDouble())
        graph.viewport.isXAxisBoundsManual = true
        graph.gridLabelRenderer.labelFormatter = TimeAsXAxisLabelFormatter("HH")
        graph.gridLabelRenderer.numHorizontalLabels = 7 // only 7 because of the space
    }

    private fun addSeries(s: Series<*>) = series.add(s)

    fun applyFontScale(scale: Float) {
        val scaled = (18 * scale).toInt()
        series.filterIsInstance<PointsWithLabelGraphSeries<*>>().forEach { it.setSpSize(scaled) }
    }

    fun performUpdate() {
        // clear old data - use removeAllSeries() to properly detach GraphView from series
        graph.removeAllSeries()

        // add pre calculated series
        for (s in series) {
            if (!s.isEmpty) {
                s.onGraphViewAttached(graph)
                graph.series.add(s)
            }
        }
        var step = 1.0
        if (maxY < 1) step = 0.1
        graph.viewport.setMaxY(Round.ceilTo(maxY, step))
        graph.viewport.setMinY(Round.floorTo(minY, step))
        graph.viewport.isYAxisBoundsManual = true

        // draw it
        graph.onDataChanged(false, false)
        series.clear()
    }

    fun addHeartRate(useForScale: Boolean, scale: Double) {
        val maxHR = (overviewData.heartRateGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).highestValueY
        if (useForScale) {
            minY = 30.0
            maxY = maxHR
        }
        addSeries(overviewData.heartRateGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        overviewData.heartRateScale.multiplier = maxY * scale / maxHR
    }

    fun addSteps(useForScale: Boolean, scale: Double) {
        val maxSteps = (overviewData.stepsCountGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).highestValueY
        if (useForScale) {
            minY = 0.0
            maxY = maxSteps
        }
        addSeries(overviewData.stepsCountGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        overviewData.stepsForScale.multiplier = maxY * scale / maxSteps
    }

    // AutoISF interim data
    fun addAcceIsf(useForScale: Boolean, scale: Double, useCommonFactor: Boolean,  maxCommonFactor: Double) {
        if (useCommonFactor) {
            maxY = maxCommonFactor
            minY = 2.0 - maxY
        } else if (useForScale) {
            maxY = max(overviewData.maxAcceIsfValueFound, maxCommonFactor)
            minY = 2.0 - maxY
        }
        if (maxY == 1.0) { maxY += 1.0e-6 }
        if (minY == 1.0) { minY -= 1.0e-6 }
        //aapsLogger.debug ( "addAcceIsf -  maxY: $maxY, minY: $minY, useForScale: $useForScale, maxCommonFactor: $maxCommonFactor")
        overviewData.acceIsfScale.multiplier = maxY * scale / max(overviewData.maxAcceIsfValueFound, maxCommonFactor)
        addSeries(overviewData.acceIsfSeries as LineGraphSeries<ScaledDataPoint>)
    }

    fun addBgIsf(useForScale: Boolean, scale: Double,useCommonFactor: Boolean,  maxCommonFactor: Double) {
        if (useCommonFactor) {
            maxY = maxCommonFactor
            minY = 2.0 - maxY
        } else if (useForScale) {
            maxY = max(overviewData.maxBgIsfValueFound,  maxCommonFactor)
            minY = 2.0 - maxY
        }
        if (maxY == 1.0) { maxY += 1.0e-6 }
        if (minY == 1.0) { minY -= 1.0e-6 }
        //aapsLogger.debug ( "addBgIsf - maxY: $maxY, minY: $minY, useForScale: $useForScale, maxCommonFactor: $maxCommonFactor")
        overviewData.bgIsfScale.multiplier = maxY * scale / max(overviewData.maxBgIsfValueFound ,maxCommonFactor)
        addSeries(overviewData.bgIsfSeries as LineGraphSeries<ScaledDataPoint>)
    }

    fun addPpIsf(useForScale: Boolean, scale: Double, useCommonFactor: Boolean,  maxCommonFactor: Double) {
        if (useCommonFactor) {
            maxY = maxCommonFactor
            minY = 2.0 - maxY
        } else if (useForScale) {
            maxY = max(overviewData.maxPpIsfValueFound,  maxCommonFactor)
            minY = 2.0 - maxY
        }
        if (maxY == 1.0) { maxY += 1.0e-6 }
        if (minY == 1.0) { minY -= 1.0e-6 }
        //aapsLogger.debug ( "addPpIsf - maxY: $maxY, minY: $minY, useForScale: $useForScale, maxCommonFactor: $maxCommonFactor")
        overviewData.ppIsfScale.multiplier = maxY * scale / max(overviewData.maxPpIsfValueFound, maxCommonFactor)
        addSeries(overviewData.ppIsfSeries as LineGraphSeries<ScaledDataPoint>)
    }

    fun addDuraIsf(useForScale: Boolean, scale: Double, useCommonFactor: Boolean,  maxCommonFactor: Double) {
        if (useCommonFactor) {
            maxY = maxCommonFactor
            minY = 2.0 - maxY
        } else if (useForScale) {
            maxY = max(overviewData.maxDuraIsfValueFound,  maxCommonFactor)
            minY = 2.0 - maxY
        }
        if (maxY == 1.0) { maxY += 1.0e-6 }
        if (minY == 1.0) { minY -= 1.0e-6 }
        //aapsLogger.debug ( "addDuraIsf - maxY: $maxY, minY: $minY, useForScale: $useForScale, maxCommonFactor: $maxCommonFactor")
        overviewData.duraIsfScale.multiplier = maxY * scale / max(overviewData.maxDuraIsfValueFound, maxCommonFactor)
        addSeries(overviewData.duraIsfSeries as LineGraphSeries<ScaledDataPoint>)
    }

    fun addFinalIsf(useForScale: Boolean, scale: Double, useCommonFactor: Boolean,  maxCommonFactor: Double) {
        if (useCommonFactor) {
            maxY = maxCommonFactor
            minY = 2.0 - maxY
        } else if (useForScale) {
            maxY = max(overviewData.maxFinalIsfValueFound, maxCommonFactor)
            minY = 2.0 - maxY
        }
        if (maxY == 1.0) { maxY += 1.0e-6 }
        if (minY == 1.0) { minY -= 1.0e-6 }
        //aapsLogger.debug ( "addFinalIsf - maxY: $maxY, minY: $minY, useForScale: $useForScale, maxCommonFactor: $maxCommonFactor")
        overviewData.finalIsfScale.multiplier = maxY * scale / max(overviewData.maxFinalIsfValueFound, maxCommonFactor)
        addSeries(overviewData.finalIsfSeries as LineGraphSeries<ScaledDataPoint>)
    }

}
