package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.round
import app.aaps.core.data.configuration.Constants
import app.aaps.core.graph.vico.Square
import app.aaps.core.interfaces.overview.graph.ActivityGraphData
import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.EpsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.DominantIsf
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TargetLineData
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.isLandscape
import app.aaps.core.ui.compose.icons.IcProfile
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalBox
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Position
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore

/** Series identifiers */
/** Basal on BG graph — deprecated, now shown as flipped overlay on IOB graph. Set to true to restore. */
@Deprecated("Basal moved to IOB graph as flipped overlay")
private const val showBasalOnBgGraph = false

private const val SERIES_REGULAR = "regular"
private const val SERIES_BUCKETED = "bucketed"
private const val SERIES_RAW = "raw"
private const val SERIES_UKF = "ukf"
private const val SERIES_PRED_IOB = "pred_iob"
private const val SERIES_PRED_COB = "pred_cob"
private const val SERIES_PRED_ACOB = "pred_acob"
private const val SERIES_PRED_UAM = "pred_uam"
private const val SERIES_PRED_ZT = "pred_zt"
private const val SERIES_BOLUS = "bolus"
private const val SERIES_CARBS = "carbs"

/** All prediction series identifiers */
private val PREDICTION_SERIES = listOf(SERIES_PRED_IOB, SERIES_PRED_COB, SERIES_PRED_ACOB, SERIES_PRED_UAM, SERIES_PRED_ZT)

/**
 * CartesianChartModelProducer.update() skips notifying receivers (and thus skips recomputing axis
 * ranges) when a transaction's partials AND extraStore are both unchanged from the last one. Since
 * scrolling/zooming re-submits identical series data (only the visible window changed), stashing
 * the visible window here forces the extraStore to differ, so Vico actually reprocesses the
 * transaction instead of silently dropping it. Same mechanism as SecondaryGraphCompose.kt.
 */
private val BG_VISIBLE_RANGE_KEY = ExtraStore.Key<Pair<Long?, Long?>>()

/**
 * A [CartesianLayerRangeProvider] backed by plain mutable fields instead of an immutable value
 * object. [CartesianLayerRangeProvider.fixed] returns a NEW instance whenever bounds change,
 * which forces Vico to rebuild the [com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer]
 * and mint a new CartesianChart id — that in turn triggers Vico's internal chart re-registration,
 * which was found to destabilize BG's live pinch-zoom/scroll gesture handling (BG is the only
 * graph with an interactive chart). This object's IDENTITY never changes across scroll/zoom; only
 * its field values do, read fresh whenever Vico next processes a modelProducer transaction — so
 * the chart itself is never rebuilt mid-gesture. Private to this file: BG is the only graph that
 * needs this (secondary graphs are non-interactive, so `.fixed(...)` churn never affected them).
 */
private class MutableYRangeProvider(
    @Volatile var maxX: Double,
    @Volatile var minY: Double,
    @Volatile var maxY: Double,
    @Volatile var yStep: Double = 0.0
) : CartesianLayerRangeProvider {
    override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
    override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = this.maxX
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = this.minY
    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = this.maxY
}

/**
 * BG Graph using Vico — dual-layer chart.
 *
 * Layer 0 (start axis): BG readings — regular (outlined circles) + bucketed (filled, range-colored)
 * Layer 1 (end axis, hidden): Basal — profile (dashed step) + actual delivered (solid step + area fill)
 *
 * Basal Y-axis is scaled so maxBasal occupies [BASAL_HEIGHT_FRACTION] of the chart height (maxY = maxBasal / BASAL_HEIGHT_FRACTION).
 *
 * Scroll/Zoom:
 * - Accepts external scroll/zoom states for synchronization with secondary graphs
 * - This is the primary interactive graph - user controls scroll/zoom here
 */
@Composable
fun BgGraphCompose(
    viewModel: GraphViewModel,
    bgOverlays: List<SeriesType>,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    nowTimestamp: Long,
    visibleTimeRange: Pair<Long, Long>? = null,
    modifier: Modifier = Modifier
) {
    val dateUtil = LocalDateUtil.current
    // Collect flows independently - each triggers recomposition only when it changes
    val bgReadings by viewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val graphDisplay by viewModel.graphDisplay.collectAsStateWithLifecycle()
    val bucketedData by viewModel.bucketedDataFlow.collectAsStateWithLifecycle()
    val showPredictions = SeriesType.PREDICTIONS in bgOverlays
    val showRaw = SeriesType.RAW_BG in bgOverlays
    val showUkf = SeriesType.UKF_BG in bgOverlays
    val rawPredictions by viewModel.predictionsFlow.collectAsStateWithLifecycle()
    val predictions = if (showPredictions) rawPredictions else emptyList()
    val rawBasalData by viewModel.basalGraphFlow.collectAsStateWithLifecycle()
    val targetData by viewModel.targetLineFlow.collectAsStateWithLifecycle()

    // Basal on BG graph is deprecated — now shown as flipped overlay on IOB graph instead.
    // Keep the layer structure intact (dummy data) to avoid chart restructuring.
    @Suppress("DEPRECATION")
    val basalData = if (showBasalOnBgGraph) rawBasalData else BasalGraphData(emptyList(), emptyList(), 0.0)
    val epsPoints by viewModel.epsGraphFlow.collectAsStateWithLifecycle()
    val showActivity = SeriesType.ACTIVITY in bgOverlays
    val activityData by viewModel.activityGraphFlow.collectAsStateWithLifecycle()
    val chartConfig by viewModel.chartConfigFlow.collectAsStateWithLifecycle()
    val treatments by viewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val autoIsfGraph = viewModel.autoIsfGraphFlow.collectAsStateWithLifecycle().value
    val hypoPrediction = autoIsfGraph.hypoPrediction

    // Use derived time range or fall back to default (last GRAPH_TIME_RANGE_HOURS hours)
    val (minTimestamp, maxTimestamp) = derivedTimeRange ?: run {
        val now = dateUtil.now()
        val dayAgo = now - Constants.GRAPH_TIME_RANGE_HOURS * 60 * 60 * 1000L
        dayAgo to now
    }

    // Single model producer shared by all layers
    val modelProducer = remember { CartesianChartModelProducer() }

    // Series registry - tracks current data for each series
    val seriesRegistry = remember { mutableStateMapOf<String, List<BgDataPoint>>() }

    // Colors from theme (stable - won't change)
    val regularColor = AapsTheme.generalColors.originalBgValue
    val lowColor = AapsTheme.generalColors.bgLow
    val inRangeColor = AapsTheme.generalColors.bgInRange
    val highColor = AapsTheme.generalColors.bgHigh
    val acceColor = AapsTheme.generalColors.acceIsf
    val bgIsfColor = AapsTheme.generalColors.bgIsf
    val ppColor = AapsTheme.generalColors.ppIsf
    val duraColor = AapsTheme.generalColors.duraIsf
    val basalColor = AapsTheme.elementColors.tempBasal
    val targetLineColor = AapsTheme.elementColors.tempTarget
    val activityColor = AapsTheme.elementColors.activity

    // Prediction colors
    val iobPredColor = AapsTheme.generalColors.iobPrediction
    val cobPredColor = AapsTheme.generalColors.cobPrediction
    val aCobPredColor = AapsTheme.generalColors.aCobPrediction
    val uamPredColor = AapsTheme.generalColors.uamPrediction
    val ztPredColor = AapsTheme.generalColors.ztPrediction

    // Calculate x-axis range (must match COB graph for alignment)
    val maxX = remember(minTimestamp, maxTimestamp) {
        timestampToX(maxTimestamp, minTimestamp)
    }

    // Stable range-provider instance for the start (BG) axis — created once, mutated in place by
    // the LaunchedEffect below rather than recreated (see MutableYRangeProvider).
    val startAxisRangeProvider = remember {
        val initialScale = niceScale(chartConfig.lowMark, chartConfig.highMark)
        MutableYRangeProvider(maxX = maxX, minY = initialScale.min, maxY = initialScale.max, yStep = initialScale.step)
    }

    // Track which series are currently included (for matching LineProvider)
    val activeSeriesState = remember { mutableStateOf(listOf<String>()) }

    // Stable time range - only changes when timestamps change by more than 1 minute
    val stableTimeRange = remember(minTimestamp / 60000, maxTimestamp / 60000) {
        minTimestamp to maxTimestamp
    }

    // Function to rebuild chart from registry
    suspend fun rebuildChart(
        currentBasalData: BasalGraphData,
        currentTargetData: TargetLineData,
        currentEpsPoints: List<EpsGraphPoint>,
        currentActivityData: ActivityGraphData,
        currentMinBgY: Double,
        currentMaxBgY: Double,
        currentVisibleTimeRange: Pair<Long, Long>?,
        bolusPoints: List<Pair<Long, Double>>,
        carbPoints: List<Pair<Long, Double>>,
    ) {
        val regularPoints = seriesRegistry[SERIES_REGULAR] ?: emptyList()
        val bucketedPoints = seriesRegistry[SERIES_BUCKETED] ?: emptyList()

        // Note: do NOT early-return when there are no BG points. With a clean DB the chart must
        // still build its frame (axes, now-line, in-range belt) via the normalizer + dummy layers,
        // matching the COB graph. The per-layer logic below already handles empty series.

        modelProducer.runTransaction {
            // Block 1 → BG layer (layer 0, start axis)
            lineModel {
                val activeSeries = mutableListOf<String>()

                if (regularPoints.isNotEmpty()) {
                    val dataPoints = regularPoints
                        .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                        .sortedBy { it.first }
                    series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                    activeSeries.add(SERIES_REGULAR)
                }

                if (bucketedPoints.isNotEmpty()) {
                    val dataPoints = bucketedPoints
                        .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                        .sortedBy { it.first }
                    series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                    activeSeries.add(SERIES_BUCKETED)
                }

                for (extra in listOf(SERIES_RAW, SERIES_UKF)) {
                    val extraPoints = seriesRegistry[extra]
                    if (!extraPoints.isNullOrEmpty()) {
                        val dataPoints = extraPoints
                            .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                            .sortedBy { it.first }
                        series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                        activeSeries.add(extra)
                    }
                }

                // Prediction series - each type as a separate line
                for (predSeries in PREDICTION_SERIES) {
                    val predPoints = seriesRegistry[predSeries]
                    if (!predPoints.isNullOrEmpty()) {
                        val dataPoints = predPoints
                            .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                            .sortedBy { it.first }
                        series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                        activeSeries.add(predSeries)
                    }
                }

                fun addMarks(points: List<Pair<Long, Double>>, key: String) {
                    if (points.isEmpty()) return
                    val dataPoints = points
                        .map { timestampToX(it.first, minTimestamp) to it.second }
                        .sortedBy { it.first }
                    series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                    activeSeries.add(key)
                }
                addMarks(bolusPoints, SERIES_BOLUS)
                addMarks(carbPoints, SERIES_CARBS)

                // Normalizer series
                series(x = normalizerX(maxX), y = NORMALIZER_Y)

                activeSeriesState.value = activeSeries.toList()
            }

            // Block 2 → Basal layer (layer 1, end axis)
            lineModel {
                fun addBasalSeries(points: List<GraphDataPoint>) {
                    if (points.size >= 2) {
                        val pts = points
                            .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                            .sortedBy { it.first }
                        series(x = pts.map { it.first }, y = pts.map { it.second })
                    } else {
                        // Dummy series - invisible at y=0
                        series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    }
                }
                addBasalSeries(currentBasalData.profileBasal)
                addBasalSeries(currentBasalData.actualBasal)
                val colouredBasal = !graphDisplay.uniformGreenBg
                addBasalSeries(if (colouredBasal) currentBasalData.acceTemp else emptyList())
                addBasalSeries(if (colouredBasal) currentBasalData.bgTemp else emptyList())
                addBasalSeries(if (colouredBasal) currentBasalData.ppTemp else emptyList())
                addBasalSeries(if (colouredBasal) currentBasalData.duraTemp else emptyList())
            }

            // Block 3 → Target line layer (layer 2, start axis)
            lineModel {
                if (currentTargetData.targets.size >= 2) {
                    val pts = currentTargetData.targets
                        .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                        .sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })
                } else {
                    // Dummy series - invisible at y=0
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }

            // Block 4 → EPS layer (layer 3, start axis — Y based on profile %, scaled into BG coordinate space)
            // Same principle as legacy (originalPercentage/100 * baseline); baseline = 75% of the BG axis
            // height. Anchored at currentMinBgY (not 0) — since the axis floor is no longer fixed at 0,
            // a 0%-profile point must sit at the axis' actual bottom, not fall below it and disappear.
            lineModel {
                if (currentEpsPoints.isNotEmpty()) {
                    val epsBaseline = (currentMaxBgY - currentMinBgY) * 0.75
                    val pts = currentEpsPoints
                        .map { eps -> timestampToX(eps.timestamp, minTimestamp) to (currentMinBgY + eps.originalPercentage / 100.0 * epsBaseline) }
                        .sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })
                } else {
                    // Dummy series - invisible at y=0
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }

            // Block 5 → Activity layer. UK graph 0 starts at 0 and puts the activity peak at
            // 80% of that top (maxY * 0.8 / max activity). The same scale is used for the carb model.
            lineModel {
                val maxAct = currentActivityData.maxActivity
                val activityTop = currentMaxBgY * 0.8
                if (!showActivity || maxAct <= 0.0 || currentActivityData.activity.size < 2) {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                } else {
                    val scaleFactor = activityTop / maxAct

                    val pts = currentActivityData.activity
                        .map { timestampToX(it.timestamp, minTimestamp) to (it.value * scaleFactor) }
                        .sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })

                    if (currentActivityData.activityPrediction.size >= 2) {
                        val predPts = currentActivityData.activityPrediction
                            .map { timestampToX(it.timestamp, minTimestamp) to (it.value * scaleFactor) }
                            .sortedBy { it.first }
                        series(x = predPts.map { it.first }, y = predPts.map { it.second })
                    } else {
                        series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    }
                }
                val maxCarb = currentActivityData.maxCarbModel
                val carbs = currentActivityData.carbModel
                if (maxCarb <= 0.0 || carbs.size < 2) {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                } else {
                    val carbScale = activityTop / maxCarb
                    val carbPts = carbs
                        .map { timestampToX(it.timestamp, minTimestamp) to (it.value * carbScale) }
                        .sortedBy { it.first }
                    series(x = carbPts.map { it.first }, y = carbPts.map { it.second })
                }
            }

            // Forces Vico to reprocess this transaction even when the series data above is
            // identical to last time (see BG_VISIBLE_RANGE_KEY doc) — otherwise scrolling/zooming
            // would re-submit the same partials and get silently skipped, never picking up the
            // updated startAxisRangeProvider.
            extras { it[BG_VISIBLE_RANGE_KEY] = currentVisibleTimeRange?.first to currentVisibleTimeRange?.second }
        }
    }

    // Split predictions by type into registry
    val predictionsByType = remember(predictions) {
        mapOf(
            SERIES_PRED_IOB to predictions.filter { it.type == BgType.IOB_PREDICTION },
            SERIES_PRED_COB to predictions.filter { it.type == BgType.COB_PREDICTION },
            SERIES_PRED_ACOB to predictions.filter { it.type == BgType.A_COB_PREDICTION },
            SERIES_PRED_UAM to predictions.filter { it.type == BgType.UAM_PREDICTION },
            SERIES_PRED_ZT to predictions.filter { it.type == BgType.ZT_PREDICTION }
        )
    }

    // Single LaunchedEffect for all data - ensures atomic updates
    val rawPoints = if (showRaw) bgReadings.mapNotNull { point ->
        point.rawValue.takeIf { it > 0.0 }?.let { point.copy(value = it) }
    } else emptyList()
    val ukfPoints = if (showUkf) bgReadings.mapNotNull { point ->
        point.ukfValue.takeIf { it > 0.0 }?.let { point.copy(value = it) }
    } else emptyList()

    LaunchedEffect(bgReadings, bucketedData, predictionsByType, rawPoints, ukfPoints, basalData, targetData, epsPoints, activityData, showActivity, chartConfig, stableTimeRange, visibleTimeRange, treatments, graphDisplay.uniformGreenBg) {
        seriesRegistry[SERIES_REGULAR] = bgReadings
        seriesRegistry[SERIES_BUCKETED] = bucketedData
        seriesRegistry[SERIES_RAW] = rawPoints
        seriesRegistry[SERIES_UKF] = ukfPoints
        for ((key, points) in predictionsByType) {
            seriesRegistry[key] = points
        }
        // maxBgY/minBgY clamped against highMark/lowMark (same as legacy GraphData.maxY logic) —
        // used only for EPS baseline / Activity overlay proportional scaling, NOT the axis range
        // itself (see below for that — windowed, unlike these full-range values).
        val allBgValues = (bgReadings + bucketedData).map { it.value }
        val maxBgY = if (allBgValues.isNotEmpty()) maxOf(allBgValues.max(), chartConfig.highMark) else chartConfig.highMark
        val minBgY = if (allBgValues.isNotEmpty()) minOf(allBgValues.min(), chartConfig.lowMark) else chartConfig.lowMark

        // Windowed axis min/max: BG values within the visible scroll/zoom window (not the full
        // loaded range), floored/ceiled at chartConfig.lowMark/highMark (the "Low mark"/"High mark"
        // target-range preferences) so the axis never shrinks past the configured target range —
        // but also never stays locked at a fixed 0 floor when real data sits well above it, which
        // used to leave a large empty band under the curve (worse for mmol/L users, since niceScale
        // can round the top up to a proportionally huge ceiling like 15 mmol/L). niceScale(...)
        // rounds both bounds and the tick step to clean numbers (e.g. 70, 180) instead of the raw
        // data values. Mutate the stable provider in place (see MutableYRangeProvider) — Vico picks
        // up the new values when it processes the transaction submitted below, without ever
        // recreating BG's chart object.
        // Includes predictions (when shown) — otherwise scrolling into a region with only future
        // prediction data (no real BG readings) makes the windowed set empty, falling back to the
        // full unwindowed history's max instead of the actually-visible prediction values.
        fun inWindow(timestamp: Long) = visibleTimeRange == null || timestamp in visibleTimeRange.first..visibleTimeRange.second
        val allBgAndPredictionValues = (bgReadings + bucketedData + predictions + rawPoints + ukfPoints).map { it.value }
        val windowedValues = (bgReadings + bucketedData + predictions + rawPoints + ukfPoints).filter { inWindow(it.timestamp) }.map { it.value }
        val windowedOrFull = windowedValues.ifEmpty { allBgAndPredictionValues }
        val dataMax = maxOf(windowedOrFull.maxOrNull() ?: chartConfig.highMark, chartConfig.highMark)
        val dataMin = minOf(windowedOrFull.minOrNull() ?: chartConfig.lowMark, chartConfig.lowMark)
        // Activity is drawn from 0, so the axis has to start at 0 or the bottom of the curve is cut off.
        val niceBgScale = if (showActivity) niceScale(0.0, dataMax) else niceScale(dataMin, dataMax)
        startAxisRangeProvider.maxX = maxX
        startAxisRangeProvider.minY = niceBgScale.min
        startAxisRangeProvider.maxY = niceBgScale.max
        startAxisRangeProvider.yStep = niceBgScale.step

        fun nearest(timestamp: Long): Double? =
            bgReadings.minByOrNull { abs(it.timestamp - timestamp) }?.value
        val markDrop = if (chartConfig.lowMark > 30.0) 15.0 else 0.8
        val bolusPoints = treatments.boluses.filter { it.bolusType == BolusType.NORMAL }.mapNotNull { bolus ->
            nearest(bolus.timestamp)?.let { bolus.timestamp to it }
        }
        val carbPoints = treatments.carbs.mapNotNull { carb ->
            nearest(carb.timestamp)?.let { carb.timestamp to (it - markDrop) }
        }
        val activityAxisMax = if (showActivity) niceBgScale.max else maxBgY
        rebuildChart(basalData, targetData, epsPoints, activityData, minBgY, activityAxisMax, visibleTimeRange, bolusPoints, carbPoints)
    }

    // Build lookup map for BUCKETED points: x-value -> BgDataPoint (for PointProvider)
    val bucketedLookup = remember(bucketedData, minTimestamp) {
        bucketedData.associateBy { timestampToX(it.timestamp, minTimestamp) }
    }

    val readingLookup = remember(bgReadings, minTimestamp) {
        bgReadings.associateBy { timestampToX(it.timestamp, minTimestamp) }
    }

    val uniformGreen = if (graphDisplay.uniformGreenBg) Color(0x8C00C800) else null
    val bucketedPointProvider = remember(bucketedLookup, lowColor, inRangeColor, highColor, acceColor, bgIsfColor, ppColor, duraColor, uniformGreen) {
        BucketedPointProvider(bucketedLookup, lowColor, inRangeColor, highColor, acceColor, bgIsfColor, ppColor, duraColor, uniformGreen)
    }

    val readingPointProvider = remember(readingLookup, regularColor, acceColor, bgIsfColor, ppColor, duraColor, uniformGreen) {
        ReadingPointProvider(readingLookup, regularColor, acceColor, bgIsfColor, ppColor, duraColor, uniformGreen)
    }

    // Time formatter and axis configuration
    val timeFormatter = rememberTimeFormatter(minTimestamp)
    val bottomAxisItemPlacer = rememberBottomAxisItemPlacer(minTimestamp)

    // =========================================================================
    // BG layer lines (layer 0)
    // =========================================================================

    val regularLine = remember(readingPointProvider) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = readingPointProvider
        )
    }

    val bucketedLine = remember(bucketedPointProvider) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = bucketedPointProvider
        )
    }

    val normalizerLine = remember { createNormalizerLine() }

    // Prediction lines - transparent connecting line with small filled circle points
    val iobPredLine = remember(iobPredColor) { createPredictionLine(iobPredColor) }
    val cobPredLine = remember(cobPredColor) { createPredictionLine(cobPredColor) }
    val aCobPredLine = remember(aCobPredColor) { createPredictionLine(aCobPredColor) }
    val uamPredLine = remember(uamPredColor) { createPredictionLine(uamPredColor) }
    val ztPredLine = remember(ztPredColor) { createPredictionLine(ztPredColor) }

    val activeSeries by activeSeriesState
    val rawLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color(0xFFFF0000))),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
            areaFill = null
        )
    }
    val ukfLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color(0xFF4FC3F7))),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
            areaFill = null
        )
    }

    val smbMarkColor = AapsTheme.elementColors.insulin
    val bolusMarkLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(Color(0xFFFF00FF)), shape = BolusUnderLineShape), size = 48.dp)
            )
        )
    }
    val carbMarkLine = remember {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(Color(0xFFFF9800)), shape = TriangleShape), size = 28.dp)
            )
        )
    }

    val bgLines = remember(activeSeries, regularLine, bucketedLine, rawLine, ukfLine, iobPredLine, cobPredLine, aCobPredLine, uamPredLine, ztPredLine, bolusMarkLine, carbMarkLine, normalizerLine) {
        buildList {
            if (SERIES_REGULAR in activeSeries) add(regularLine)
            if (SERIES_BUCKETED in activeSeries) add(bucketedLine)
            if (SERIES_RAW in activeSeries) add(rawLine)
            if (SERIES_UKF in activeSeries) add(ukfLine)
            if (SERIES_PRED_IOB in activeSeries) add(iobPredLine)
            if (SERIES_PRED_COB in activeSeries) add(cobPredLine)
            if (SERIES_PRED_ACOB in activeSeries) add(aCobPredLine)
            if (SERIES_PRED_UAM in activeSeries) add(uamPredLine)
            if (SERIES_PRED_ZT in activeSeries) add(ztPredLine)
            if (SERIES_BOLUS in activeSeries) add(bolusMarkLine)
            if (SERIES_CARBS in activeSeries) add(carbMarkLine)
            add(normalizerLine)
        }
    }

    // =========================================================================
    // Basal layer lines (layer 1) — always 2 lines: [profileLine, actualLine]
    // =========================================================================

    // Profile basal: dashed line, no fill, step connector
    val profileBasalLine = remember(basalColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(basalColor)),
            stroke = LineCartesianLayer.LineStroke.Dashed(
                thickness = 1.dp,
                cap = StrokeCap.Round,
                dashLength = 1.dp,
                gapLength = 2.dp
            ),
            areaFill = null,
            interpolator = Square
        )
    }

    // Actual delivered basal: solid line with semi-transparent area fill, step connector
    val actualBasalLine = remember(basalColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(basalColor)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(basalColor.copy(alpha = 0.3f))),
            interpolator = Square
        )
    }

    fun factorBasalLine(color: Color) = LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.7f))),
        interpolator = Square
    )

    val acceBasalLine = remember(acceColor) { factorBasalLine(acceColor) }
    val bgBasalLine = remember(bgIsfColor) { factorBasalLine(bgIsfColor) }
    val ppBasalLine = remember(ppColor) { factorBasalLine(ppColor) }
    val duraBasalLine = remember(duraColor) { factorBasalLine(duraColor) }

    val basalLines = remember(profileBasalLine, actualBasalLine, acceBasalLine, bgBasalLine, ppBasalLine, duraBasalLine) {
        listOf(profileBasalLine, actualBasalLine, acceBasalLine, bgBasalLine, ppBasalLine, duraBasalLine)
    }

    // =========================================================================
    // Target line (layer 2) — single line on start (BG) axis
    // =========================================================================

    val targetLine = remember(targetLineColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(targetLineColor)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
            areaFill = null,
            interpolator = Square
        )
    }

    val targetLines = remember(targetLine) { listOf(targetLine) }

    // =========================================================================
    // EPS layer lines (layer 3) — profile icon points
    // =========================================================================

    val profileSwitchColor = AapsTheme.elementColors.profileSwitch
    val profilePainter = rememberVectorPainter(IcProfile)

    val epsLine = remember(profileSwitchColor, profilePainter) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(
                    component = PainterComponent(profilePainter, tint = profileSwitchColor),
                    size = 16.dp
                )
            )
        )
    }

    val epsLines = remember(epsLine) { listOf(epsLine) }

    // =========================================================================
    // Activity layer lines (layer 4) — solid historical + dashed prediction
    // =========================================================================

    val activityHistLine = remember(activityColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(activityColor)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
            areaFill = null
        )
    }

    val activityPredLine = remember(activityColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(activityColor)),
            stroke = LineCartesianLayer.LineStroke.Dashed(
                thickness = 1.5.dp,
                cap = StrokeCap.Round,
                dashLength = 4.dp,
                gapLength = 4.dp
            ),
            areaFill = null
        )
    }

    val carbModelColor = AapsTheme.elementColors.carbs
    val carbModelLine = remember(carbModelColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(carbModelColor)),
            stroke = LineCartesianLayer.LineStroke.Dashed(
                thickness = 1.5.dp,
                cap = StrokeCap.Round,
                dashLength = 4.dp,
                gapLength = 4.dp
            ),
            areaFill = null
        )
    }

    val activityLines = remember(activityHistLine, activityPredLine, carbModelLine) {
        listOf(activityHistLine, activityPredLine, carbModelLine)
    }

    // Basal Y-axis range: maxBasal / BASAL_HEIGHT_FRACTION so basal occupies that fraction of chart height
    val basalMaxY = remember(basalData.maxBasal) {
        if (basalData.maxBasal > 0.0) basalData.maxBasal / BASAL_HEIGHT_FRACTION else 1.0
    }

    // =========================================================================
    // Decorations
    // =========================================================================

    val nowLineColor = MaterialTheme.colorScheme.onSurface
    val nowLine = rememberNowLine(minTimestamp, nowTimestamp, nowLineColor)

    // In-range belt — translucent band between lowMark and highMark on the BG axis
    val lowMark = chartConfig.lowMark
    val highMark = chartConfig.highMark
    val inRangeBox = remember(lowMark, highMark, inRangeColor) {
        HorizontalBox(
            y = { lowMark..highMark },
            box = ShapeComponent(fill = Fill(inRangeColor.copy(alpha = 0.2f))),
            verticalAxisPosition = Axis.Position.Vertical.Start
        )
    }

    fun isfColor(kind: DominantIsf?): Color = when (kind) {
        DominantIsf.ACCE -> acceColor
        DominantIsf.BG   -> bgIsfColor
        DominantIsf.PP   -> ppColor
        DominantIsf.DURA -> duraColor
        else             -> smbMarkColor
    }
    val smbText = rememberTextMeasurer()
    val smbStack = remember(treatments, bgReadings, minTimestamp, graphDisplay.showSmbLabels) {
        if (!graphDisplay.showSmbLabels) return@remember emptyList()
        val smbs = treatments.boluses.filter { it.bolusType == BolusType.SMB && it.label.isNotEmpty() }
        val times = smbs.map { it.timestamp }
        val stack = smbStackIndex(times)
        val columns = smbColumnTimes(times)
        smbs.mapIndexed { index, smb ->
            val dot = bgReadings.minByOrNull { abs(it.timestamp - smb.timestamp) }
            SmbStackItem(
                x = timestampToX(smb.timestamp, minTimestamp),
                label = smb.label,
                stackIndex = stack[index],
                anchorY = dot?.value,
                color = isfColor(dot?.dominantIsf),
                columnX = timestampToX(columns[index], minTimestamp),
            )
        }
    }
    val smbArrows = remember(treatments, bgReadings, minTimestamp, graphDisplay.showSmbArrows) {
        if (!graphDisplay.showSmbArrows) return@remember emptyList()
        treatments.boluses.filter { it.bolusType == BolusType.SMB }.map { smb ->
            val dot = bgReadings.minByOrNull { abs(it.timestamp - smb.timestamp) }
            SmbStackItem(
                x = timestampToX(smb.timestamp, minTimestamp),
                label = smb.label,
                stackIndex = 0,
                anchorY = dot?.value,
                color = isfColor(dot?.dominantIsf),
            )
        }
    }
    val smbNumbers = remember(smbStack, smbText) {
        SmbStackLabels(smbStack, smbText, pinToBottom = false)
    }
    val smbBaseArrows = remember(treatments, bgReadings, minTimestamp, lowMark) {
        treatments.boluses.filter { it.bolusType == BolusType.SMB }.map { smb ->
            val dot = bgReadings.minByOrNull { abs(it.timestamp - smb.timestamp) }
            SmbStackItem(
                x = timestampToX(smb.timestamp, minTimestamp),
                label = smb.label,
                stackIndex = 0,
                anchorY = lowMark,
                color = isfColor(dot?.dominantIsf),
                stemUnits = 1 + (((smb.amount - 0.05 + 0.001) / 0.05).toInt().coerceAtLeast(0)),
            )
        }
    }
    val carbDrop = if (lowMark > 30.0) 15.0 else 0.8
    val carbLabels = remember(treatments, bgReadings, minTimestamp, carbDrop) {
        treatments.carbs.mapNotNull { carb ->
            val y = bgReadings.minByOrNull { abs(it.timestamp - carb.timestamp) }?.value ?: return@mapNotNull null
            SmbStackItem(
                x = timestampToX(carb.timestamp, minTimestamp),
                label = carb.label,
                stackIndex = 0,
                anchorY = y - carbDrop,
                color = Color(0xFFFF9800),
                belowAnchor = true,
            )
        }
    }
    val carbText = rememberTextMeasurer()
    val carbNumbers = remember(carbLabels, carbText) {
        SmbStackLabels(carbLabels, carbText, pinToBottom = false)
    }
    val bolusText = rememberTextMeasurer()
    val bolusStack = remember(treatments, minTimestamp, lowMark) {
        val boluses = treatments.boluses.filter { it.bolusType == BolusType.NORMAL && it.label.isNotEmpty() }
        val stack = smbStackIndex(boluses.map { it.timestamp })
        boluses.mapIndexed { index, bolus ->
            SmbStackItem(
                x = timestampToX(bolus.timestamp, minTimestamp),
                label = bolus.label,
                stackIndex = stack[index],
                anchorY = lowMark,
                color = Color(0xFFE53935),
            )
        }
    }
    val bolusNumbers = remember(bolusStack, bolusText) {
        SmbStackLabels(bolusStack, bolusText, pinToBottom = false)
    }
    val smbArrowMarks = remember(smbArrows) { SmbArrows(smbArrows) }
    val smbBaseArrowMarks = remember(smbBaseArrows) { SmbArrows(smbBaseArrows, pinToBottom = true) }
    val decorations = remember(inRangeBox, nowLine, smbNumbers, smbArrowMarks, smbBaseArrowMarks, bolusNumbers, carbNumbers) {
        listOf(inRangeBox, nowLine, smbNumbers, smbArrowMarks, smbBaseArrowMarks, bolusNumbers, carbNumbers)
    }

    // =========================================================================
    // Range providers — hoisted out of rememberCartesianChart so keys are re-evaluated on recomposition
    // =========================================================================

    // startAxisRangeProvider (BG axis) is created once, further up, and mutated in place — see
    // MutableYRangeProvider and the LaunchedEffect above.
    val endAxisRangeProvider = remember(maxX, basalMaxY) {
        CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = 0.0, maxY = basalMaxY)
    }

    // =========================================================================
    // Chart — multi layer
    // =========================================================================

    val axisLock = remember { GraphAxisLock() }
    Box(
        modifier = modifier.fillMaxWidth().then(
            if (isLandscape()) Modifier.nestedScroll(axisLock) else Modifier
        )
    ) {
    CartesianChartHost(
        chart = rememberCartesianChart(
            // Layer 0: BG (start axis, visible)
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(bgLines),
                rangeProvider = startAxisRangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.Start
            ),
            // Layer 1: Basal (end axis, hidden — no endAxis parameter)
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(basalLines),
                rangeProvider = endAxisRangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.End
            ),
            // Layer 2: Target line (start axis — shares BG Y-axis range)
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(targetLines),
                rangeProvider = startAxisRangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.Start
            ),
            // Layer 3: EPS (start axis — Y based on profile %, same range as BG layer)
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(epsLines),
                rangeProvider = startAxisRangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.Start
            ),
            // Layer 4: Activity (start axis — shares BG Y-axis range, values normalized in rebuildChart)
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(activityLines),
                rangeProvider = startAxisRangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.Start
            ),
            startAxis = VerticalAxis.rememberStart(
                itemPlacer = VerticalAxis.ItemPlacer.step({ startAxisRangeProvider.yStep }),
                label = rememberTextComponent(
                    style = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    minWidth = TextComponent.MinWidth.fixed(30.dp)
                ),
                guideline = LineComponent(fill = Fill(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = timeFormatter,
                itemPlacer = bottomAxisItemPlacer,
                label = rememberTextComponent(
                    style = TextStyle(color = MaterialTheme.colorScheme.onSurface)
                ),
                guideline = LineComponent(fill = Fill(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
            ),
            decorations = decorations,
            getXStep = { _, _, _ -> 1.0 }
        ),
        modelProducer = modelProducer,
        modifier = Modifier.fillMaxSize(),
        scrollState = scrollState,
        zoomState = zoomState
    )
    if (autoIsfGraph.statusTarget != null || autoIsfGraph.statusIsf != null || hypoPrediction != null) {
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 44.dp)) {
            autoIsfGraph.statusTarget?.let { line ->
                Text(text = line, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            autoIsfGraph.statusIsf?.let { line ->
                Text(text = line, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (hypoPrediction != null) {
                Text(
                    text = "hypoprediction= ${oneDecimal(hypoPrediction)}",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    }
}

private fun oneDecimal(value: Double): String {
    val tenths = round(value * 10.0).toInt()
    val sign = if (tenths < 0) "-" else ""
    val absTenths = abs(tenths)
    return "$sign${absTenths / 10}.${absTenths % 10}"
}

/**
 * In landscape a drag on the graph must scroll one way only.
 * Sideways moves the graph. Up or down moves the page.
 * The choice waits until the finger has moved far enough, so a small sideways
 * start does not block an up or down swipe. The other direction is not applied,
 * so the screen is not pulled on a diagonal.
 */
private class GraphAxisLock : NestedScrollConnection {
    private var orientation: Orientation? = null
    private var accX = 0f
    private var accY = 0f
    private var lastMs = 0L

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val now = System.currentTimeMillis()
        if (now - lastMs > 120L) {
            orientation = null
            accX = 0f
            accY = 0f
        }
        lastMs = now
        accX += available.x
        accY += available.y
        if (orientation == null && (abs(accX) > 48f || abs(accY) > 48f)) {
            orientation = if (abs(accX) >= abs(accY)) Orientation.Horizontal else Orientation.Vertical
        }
        val axis = orientation ?: if (abs(accX) >= abs(accY)) Orientation.Horizontal else Orientation.Vertical
        return when (axis) {
            Orientation.Horizontal -> Offset(x = 0f, y = available.y)
            Orientation.Vertical -> Offset(x = available.x, y = 0f)
            else -> Offset.Zero
        }
    }
}
