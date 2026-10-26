package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.round
import kotlin.time.Clock
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.treatments.WizardInfoDialog
import app.aaps.core.graph.vico.Square
import app.aaps.core.interfaces.overview.graph.ActivityGraphData
import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.EpsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.DominantIsf
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TargetLineData
import app.aaps.core.interfaces.overview.graph.TherapyEventType
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.isLandscape
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.icons.IcBgCheck
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
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
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
import kotlin.math.roundToLong

/** Series identifiers */
/** Basal on BG graph — deprecated, now shown as flipped overlay on IOB graph. Set to true to restore. */
@Deprecated("Basal moved to IOB graph as flipped overlay")
private const val showBasalOnBgGraph = false

/** The announcement text Live writes for its Tier 3 UAM boost, drawn over the BG as on Live's main graph. */
private const val UAM_BOOST_GRAPH_MARK = "B"
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

/** A zero-bolus arrow is this much larger than an SMB arrow. */
private const val ZERO_BOLUS_ARROW_SCALE = 1.5f
private const val SERIES_CARBS = "carbs"

/** All prediction series identifiers */
private val PREDICTION_SERIES = listOf(SERIES_PRED_IOB, SERIES_PRED_COB, SERIES_PRED_ACOB, SERIES_PRED_UAM, SERIES_PRED_ZT)

/**
 * Half the space between the insulin and the carbs half of one meal, as a share of the Y axis.
 * The axis is a fixed height on screen, so this is a steady distance in dp whatever the glucose
 * range is — about 1 dp on each side. Enough that the two triangles do not merge into a block,
 * small enough that they still read as one marker.
 */
private const val MARKER_GAP_FRACTION = 0.015

/** Height of a marker that has no reading near it, as a share of the Y axis above its floor. */
private const val MARKER_FALLBACK_FRACTION = 0.04

/** Marker sizes per [MarkerTier]. The largest must stay within [NORMALIZER_POINT_SIZE]. */
private val MARKER_SIZE_SMALL = 12.dp
private val MARKER_SIZE_MEDIUM = 17.dp
private val MARKER_SIZE_LARGE = 22.dp

/** Size of the icon drawn at a measured glucose value (calibration, BG check). */
private val MARKER_SIZE_CALIBRATION = 14.dp

/**
 * Y data for an empty marker slot. Far below any axis floor, so Vico clips it away — the treatment
 * block always emits every series, so the line list never has to be matched to the data.
 */
private val MARKER_EMPTY_Y = listOf(-10_000.0, -10_000.0)

/**
 * CartesianChartModelProducer.update() skips notifying receivers (and thus skips recomputing axis
 * ranges) when a transaction's partials AND extraStore are both unchanged from the last one. Since
 * scrolling/zooming re-submits identical series data (only the visible window changed), stashing
 * the visible window here forces the extraStore to differ, so Vico actually reprocesses the
 * transaction instead of silently dropping it. Same mechanism as SecondaryGraphCompose.kt.
 */
private val BG_VISIBLE_RANGE_KEY = ExtraStore.Key<Pair<Long?, Long?>>()

/**
 * Share of the BG axis height, from the top, used by the Graph 5 top band lines. The band is split into four equal lanes,
 * one per line (bottom to top: SMBdel, aISFwt, ppISFwt, profBasal), so usual values can never sit on top of each other.
 */
private const val BG_TOP_BAND_FRACTION = 0.28

/**
 * Colours of the Graph 5 top band lines. SMB delivery and profile basal use the same colours as the 3426 graph (pink and
 * orange), so neither can be mistaken for the blue acce weight or the green pp weight, which keep their ISF colours.
 */
private val SMB_BAND_COLOR = Color(0xFFFF40C0)
private val PROFILE_BASAL_BAND_COLOR = Color(0xFFFFA040)
// Top strip of the main graph: the ISF line has a colour per field, the steps line is orange, so the DR line and the target offset line get their own.
private val STATUS_RATIO_COLOR = Color(0xFF4FC3F7)
private val STATUS_TARGET_COLOR = Color(0xFF66BB6A)

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
/** With "Show the high area" off: the most the area above the high mark may take, as a part of the green range's height. */
/** Fixed glucose range of the main graph, in mmol/L (2026-10-16): 2 to 12, a whole number of steps so the axis cannot grow past the top. Graph 5 does not use it. */
private const val MAIN_GRAPH_MIN_MMOL = 2.0
private const val MAIN_GRAPH_MAX_MMOL = 12.0
private const val MAIN_GRAPH_STEP_MMOL = 2.0

/** The main graph's fixed axis, 2.0 to 12.0 mmol/L, in the units the graph is drawn in. */
internal fun mainGraphScale(mgdl: Boolean): NiceScale {
    val factor = if (mgdl) Constants.MMOLL_TO_MGDL else 1.0
    return NiceScale(MAIN_GRAPH_MIN_MMOL * factor, MAIN_GRAPH_MAX_MMOL * factor, MAIN_GRAPH_STEP_MMOL * factor)
}

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
 * Layer 5 (start axis): Graph 5 top band lines.
 * Layer 6 (start axis): treatment markers. In the fork only the measured values (calibration, BG check) are drawn
 * here, on their own value; boluses and carbs keep the fork's marks in the BG layer (2026-10-22 merge). See
 * [treatmentMarkers] for the placement rules.
 *
 * Basal Y-axis is scaled so maxBasal occupies [BASAL_HEIGHT_FRACTION] of the chart height (maxY = maxBasal / BASAL_HEIGHT_FRACTION).
 *
 * The insulin activity overlay has no axis of its own: it starts at the bottom of the panel and peaks at 80% of its
 * height, as on 3426 (fork, kept over upstream's low-mark scaling in the 2026-10-16 merge), and never changes the BG scale.
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
    /** Graph 5 only: draw SMB delivery, acce weight, pp weight and profile basal in the top band of the BG axis. */
    topBandLines: Boolean = false,
    automaticCleanGraph: Boolean = false,
    /** With [automaticCleanGraph]: also hide the text lines (the "Clean graph hides text lines" setting). */
    cleanGraphHidesTextLines: Boolean = false,
    /** Press and hold to see the values at a reading. Null turns it off. */
    inspect: GraphInspectState? = null,
    /** Filled with this graph's layout on each draw, for the time-based scroll and zoom sync (see ChartGeometry). */
    geometryHolder: ChartGeometryHolder? = null,
    modifier: Modifier = Modifier
) {
    val dateUtil = LocalDateUtil.current
    val decimalFormatter = LocalDecimalFormatter.current
    val profileUtil = LocalProfileUtil.current
    val tapTargets = remember { GraphTapTargets() }
    var selectedBg by remember { mutableStateOf<BgDataPoint?>(null) }
    var selectedBolus by remember { mutableStateOf<BolusGraphPoint?>(null) }
    var selectedSmb by remember { mutableStateOf<BolusGraphPoint?>(null) }
    var selectedCalculation by remember { mutableStateOf<BCR?>(null) }
    var calculationLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(selectedBolus) {
        calculationLoaded = false
        selectedCalculation = selectedBolus?.let { viewModel.calculationForBolus(it.timestamp, it.amount) }
        calculationLoaded = true
    }
    if (selectedBolus != null && calculationLoaded) {
        val calculation = selectedCalculation
        if (calculation != null) {
            WizardInfoDialog(calculation, decimalFormatter, onDismiss = { selectedBolus = null })
        } else {
            val bolus = selectedBolus!!
            AlertDialog(
                onDismissRequest = { selectedBolus = null },
                title = { Text(stringResource(InterfacesStrings.bolus)) },
                text = {
                    Column {
                        Text(dateUtil.dateAndTimeAndSecondsString(bolus.timestamp))
                        Text(stringResource(InterfacesStrings.format_insulin_units, bolus.amount))
                        if (!bolus.isValid) Text(stringResource(CoreUiStrings.invalid))
                        Text(stringResource(UiStrings.graph_bolus_calculation_unavailable))
                    }
                },
                confirmButton = { TextButton(onClick = { selectedBolus = null }) { Text(stringResource(CoreUiStrings.close)) } },
            )
        }
    }
    // Collect flows independently - each triggers recomposition only when it changes
    val bgReadings by viewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val savedGraphDisplay by viewModel.graphDisplay.collectAsStateWithLifecycle()
    val graphDisplay = if (automaticCleanGraph) savedGraphDisplay.copy(
        showSmbLabels = false, basalToggleIndex = 2, showTextLines = savedGraphDisplay.showTextLines && !cleanGraphHidesTextLines
    ) else savedGraphDisplay
    val bucketedData by viewModel.bucketedDataFlow.collectAsStateWithLifecycle()
    val showPredictions = SeriesType.PREDICTIONS in bgOverlays
    // 2026-10-23, per explicit request: the automatic clean graph (zoomed out past its width) also hides Raw and UKF raw BG,
    // the insulin activity and carbs model curves and the bolus numbers, and draws boluses as slim arrows (bolusArrowMarks)
    val showRaw = SeriesType.RAW_BG in bgOverlays && !automaticCleanGraph
    val showUkf = SeriesType.UKF_BG in bgOverlays && !automaticCleanGraph
    val rawPredictions by viewModel.predictionsFlow.collectAsStateWithLifecycle()
    val predictions = if (showPredictions) rawPredictions else emptyList()
    val rawBasalData by viewModel.basalGraphFlow.collectAsStateWithLifecycle()
    val targetData by viewModel.targetLineFlow.collectAsStateWithLifecycle()

    // Basal on BG graph is deprecated — now shown as flipped overlay on IOB graph instead.
    // Keep the layer structure intact (dummy data) to avoid chart restructuring.
    @Suppress("DEPRECATION")
    val basalData = if (showBasalOnBgGraph) rawBasalData else BasalGraphData(emptyList(), emptyList(), 0.0)
    val epsPoints by viewModel.epsGraphFlow.collectAsStateWithLifecycle()
    val showActivity = SeriesType.ACTIVITY in bgOverlays && !automaticCleanGraph
    val showCarbModel = !automaticCleanGraph
    val activityData by viewModel.activityGraphFlow.collectAsStateWithLifecycle()
    val chartConfig by viewModel.chartConfigFlow.collectAsStateWithLifecycle()
    val treatments by viewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val autoIsfGraph = viewModel.autoIsfGraphFlow.collectAsStateWithLifecycle().value

    // Graph 5 top band lines (lowest to highest lane: SMBdel, aISFwt, ppISFwt, profBasal). Each is scaled to its own lowest
    // and highest value over the last 24 hours, so the lines always use the full lane. Profile basal is read at each loop's time.
    val topBandLines5 = remember(autoIsfGraph, rawBasalData.profileBasal, nowTimestamp / 60_000L) {
        val now = nowTimestamp
        val basalSteps = rawBasalData.profileBasal.sortedBy { it.timestamp }.map { it.timestamp.toDouble() to it.value }
        listOf(
            topBandLine(autoIsfGraph.smbDelivery.map { it.timestamp to it.value }, now),
            topBandLine(autoIsfGraph.acceWeight.map { it.timestamp to it.value }, now),
            topBandLine(autoIsfGraph.ppWeight.map { it.timestamp to it.value }, now),
            topBandLine(
                if (basalSteps.isEmpty()) emptyList() else autoIsfGraph.smbDelivery.mapNotNull { loop ->
                    stepValueAt(basalSteps, loop.timestamp.toDouble())?.let { loop.timestamp to it }
                },
                now
            )
        )
    }

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
    val insulinColor = AapsTheme.elementColors.insulin
    val carbsColor = AapsTheme.elementColors.carbs
    val bgCheckColor = AapsTheme.elementColors.bgCheck

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
        currentMarkers: TreatmentMarkers,
        currentVisibleTimeRange: Pair<Long, Long>?,
        bolusPoints: List<Pair<Long, Double>>,
        carbPoints: List<Pair<Long, Double>>,
        axisMin: Double,
        axisMax: Double,
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

                // Prediction cloud stays inside the green band. A point under the low mark is drawn on that line.
                val cloudFloor = chartConfig.lowMark
                for (predSeries in PREDICTION_SERIES) {
                    val predPoints = seriesRegistry[predSeries]
                    if (!predPoints.isNullOrEmpty()) {
                        val dataPoints = predPoints
                            .map { timestampToX(it.timestamp, minTimestamp) to it.value.coerceAtLeast(cloudFloor) }
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

            // Activity and the carb model start at the bottom of the panel and peak at 80% of its height, as on 3426
            // (actScale: maxY * 0.8 / peak). currentMaxBgY is the axis top without the Graph 5 top band, so the
            // peaks stay below the band lanes.
            lineModel {
                val floor = axisMin
                val headroom = (currentMaxBgY - floor).coerceAtLeast(1.0)
                val activityTop = headroom * 0.8
                val maxAct = currentActivityData.maxActivity
                if (!showActivity || maxAct <= 0.0 || currentActivityData.activity.size < 2) {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                } else {
                    val scaleFactor = activityTop / maxAct

                    val pts = currentActivityData.activity
                        .map { timestampToX(it.timestamp, minTimestamp) to (floor + it.value * scaleFactor) }
                        .sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })

                    if (currentActivityData.activityPrediction.size >= 2) {
                        val predPts = currentActivityData.activityPrediction
                            .map { timestampToX(it.timestamp, minTimestamp) to (floor + it.value * scaleFactor) }
                            .sortedBy { it.first }
                        series(x = predPts.map { it.first }, y = predPts.map { it.second })
                    } else {
                        series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    }
                }
                val maxCarb = currentActivityData.maxCarbModel
                val carbs = currentActivityData.carbModel
                if (!showCarbModel || maxCarb <= 0.0 || carbs.size < 2) {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                } else {
                    val carbScale = activityTop / maxCarb
                    val carbPts = carbs
                        .map { timestampToX(it.timestamp, minTimestamp) to (floor + it.value * carbScale) }
                        .sortedBy { it.first }
                    series(x = carbPts.map { it.first }, y = carbPts.map { it.second })
                }
            }

            // Block 6 → Top band layer (layer 5, start axis): SMB delivery, acce weight, pp weight and profile basal, per
            // loop, each as a 0..1 fraction of its own scale drawn in the top BG_TOP_BAND_FRACTION of the BG axis (the axis is raised so that band sits above the green range).
            // Always four series (dummy at y=0 when off or empty) so the series count matches the layer's four lines.
            lineModel {
                val bandHeight = BG_TOP_BAND_FRACTION * (axisMax - axisMin).coerceAtLeast(1.0)
                val bandBottom = axisMax - bandHeight
                val laneHeight = bandHeight / 4.0
                // Each line gets its own lane (0 = bottom). Its 24-hour lowest..highest range fills 90% of the lane, so lines stay apart.
                fun addBand(lane: Int, line: TopBandLine) {
                    val range = line.range
                    if (!topBandLines || line.points.size < 2 || range == null) {
                        series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    } else {
                        series(
                            x = line.points.map { timestampToX(it.first, minTimestamp) },
                            y = line.points.map { bandBottom + (lane + range.fraction(it.second) * 0.9) * laneHeight }
                        )
                    }
                }
                topBandLines5.forEachIndexed { lane, line -> addBand(lane, line) }
            }

            // Block 6 → Treatment markers (layer 5, start axis — drawn at the glucose value of the
            // moment they happened, see TreatmentMarkers.kt). Insulin grows up from the reading and
            // carbs down from the same value, so a meal entered with its bolus is one diamond; a
            // measured value sits on its own value.
            // Drawn last on purpose: a carbs marker hangs into the curve, and under the BG layer it
            // was covered by the reading dots and the profile icon. It does cover the one reading
            // it sits on.
            // Fixed series order, always all 7: insulin small/medium/large, carbs small/medium/large,
            // measured glucose. An empty slot gets a clipped dummy, so the line list below is a
            // plain fixed list instead of something that has to be matched to the data.
            lineModel {
                fun markerSeries(markers: List<TreatmentMarker>) {
                    if (markers.isEmpty()) series(x = listOf(0.0, 1.0), y = MARKER_EMPTY_Y)
                    else series(x = markers.map { it.x }, y = markers.map { it.y })
                }
                for (tier in MarkerTier.entries) markerSeries(currentMarkers.insulin.filter { it.tier == tier })
                for (tier in MarkerTier.entries) markerSeries(currentMarkers.carbs.filter { it.tier == tier })
                val measured = currentMarkers.calibrations
                if (measured.isEmpty()) series(x = listOf(0.0, 1.0), y = MARKER_EMPTY_Y)
                else series(x = measured.map { it.first }, y = measured.map { it.second })
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
    var epsLabelAnchors by remember { mutableStateOf(emptyList<SmbStackItem>()) }

    // One rebuild after the other, and none is cancelled half-way (upstream #5376, ported 2026-10-27). An effect keyed on
    // these inputs cancelled the running transaction on every scroll, zoom or new reading. When that came just after Vico
    // had stopped the animation of the one before, the lines stayed part-way between the old and the new scale while the
    // labels showed the new one. Now one collector lives as long as the graph: the newest input waits for the running
    // rebuild, and the inputs in between are skipped.
    val rebuildInputs = listOf<Any?>(
        bgReadings, bucketedData, predictionsByType, rawPoints, ukfPoints, basalData, targetData, epsPoints, activityData, showActivity,
        showCarbModel, chartConfig, stableTimeRange, visibleTimeRange, treatments, graphDisplay.uniformGreenBg, autoIsfGraph, rawBasalData,
        topBandLines, topBandLines5
    )
    val latestRebuildInputs by rememberUpdatedState(rebuildInputs)
    val latestRebuild by rememberUpdatedState<suspend () -> Unit>({
        seriesRegistry[SERIES_REGULAR] = bgReadings
        seriesRegistry[SERIES_BUCKETED] = bucketedData
        seriesRegistry[SERIES_RAW] = rawPoints
        seriesRegistry[SERIES_UKF] = ukfPoints
        for ((key, points) in predictionsByType) {
            seriesRegistry[key] = points
        }
        // maxBgY/minBgY clamped against highMark/lowMark (same as legacy GraphData.maxY logic) —
        // used only for the EPS baseline, NOT the axis range itself (see below for that — windowed,
        // unlike these full-range values).
        val allBgValues = (bgReadings + bucketedData).map { it.value }
        val maxBgY = if (allBgValues.isNotEmpty()) maxOf(allBgValues.max(), chartConfig.highMark) else chartConfig.highMark
        val minBgY = if (allBgValues.isNotEmpty()) minOf(allBgValues.min(), chartConfig.lowMark) else chartConfig.lowMark
        val epsBaseline = (maxBgY - minBgY) * 0.75
        epsLabelAnchors = epsPoints.mapNotNull { eps ->
            if (eps.label.isEmpty()) return@mapNotNull null
            SmbStackItem(
                x = timestampToX(eps.timestamp, minTimestamp),
                label = eps.label,
                stackIndex = 0,
                anchorY = minBgY + eps.originalPercentage / 100.0 * epsBaseline,
            )
        }

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
        // A measured value (calibration, BG check) is drawn at its own glucose value and can sit
        // well off the curve, so it widens the axis like a reading — otherwise it would be clamped
        // to the edge and lie about what the meter said.
        val measuredPoints = measuredGlucosePoints(treatments.calibrations, treatments.therapyEvents)
        val allBgAndPredictionValues = (bgReadings + bucketedData + predictions + rawPoints + ukfPoints).map { it.value } +
            measuredPoints.map { it.second }
        val windowedValues = (bgReadings + bucketedData + predictions + rawPoints + ukfPoints).filter { inWindow(it.timestamp) }.map { it.value } +
            measuredPoints.filter { inWindow(it.first) }.map { it.second }
        val windowedOrFull = windowedValues.ifEmpty { allBgAndPredictionValues }
        val dataMax = maxOf(windowedOrFull.maxOrNull() ?: chartConfig.highMark, chartConfig.highMark)
        val dataMin = minOf(windowedOrFull.minOrNull() ?: chartConfig.lowMark, chartConfig.lowMark)
        // Activity overlay on: the panel top is the 3426 one (24 h highest or high mark, rounded, plus a margin), and the
        // activity peak sits at 80% of the panel height, so it ends up above the green range as on 3426.
        val axisDataMax = if (showActivity) maxOf(dataMax, activityChartTop(maxBgY, chartConfig.highMark, chartConfig.lowMark > 30.0)) else dataMax
        // Graph 5: raise the top so the four top band lanes sit above the green range and the curve, not on them.
        // Main graph (2026-10-16, per explicit request): always 2.0 to 12.0 mmol/L, readings outside are cut off.
        // Graph 5 keeps the range that follows the data.
        val niceBgScale = if (topBandLines) axisWithTopBand(dataMin, axisDataMax, BG_TOP_BAND_FRACTION) else mainGraphScale(chartConfig.lowMark > 30.0)
        startAxisRangeProvider.maxX = maxX
        startAxisRangeProvider.minY = niceBgScale.min
        startAxisRangeProvider.maxY = niceBgScale.max
        startAxisRangeProvider.yStep = niceBgScale.step

        fun nearest(timestamp: Long): Double? =
            bgReadings.minByOrNull { abs(it.timestamp - timestamp) }?.value
        val markDrop = if (chartConfig.lowMark > 30.0) 15.0 else 0.8
        // A zero bolus gets a small arrow (zeroBolusArrows), not the big mark
        val bolusPoints = if (automaticCleanGraph) emptyList() else treatments.boluses.filter { it.bolusType == BolusType.NORMAL && it.amount > 0.0 }.mapNotNull { bolus ->
            nearest(bolus.timestamp)?.let { bolus.timestamp to it }
        }
        val carbPoints = treatments.carbs.mapNotNull { carb ->
            nearest(carb.timestamp)?.let { carb.timestamp to (it - markDrop) }
        }
        // Activity scales to the axis as it is without the Graph 5 top band, so its peak never reaches the band lanes.
        val activityAxisMax = if (!showActivity) maxBgY else if (topBandLines) niceScale(dataMin, axisDataMax).max else niceBgScale.max
        // Upstream's treatment markers (2026-10 merge): only the measured values here (calibrations, BG checks). Boluses
        // and carbs keep the fork's own marks on the curve (bolusPoints / carbPoints above), so they are not drawn twice.
        val axisSpan = niceBgScale.max - niceBgScale.min
        val markers = treatmentMarkers(
            boluses = emptyList(),
            carbs = emptyList(),
            calibrations = treatments.calibrations,
            therapyEvents = treatments.therapyEvents,
            readings = bucketedData.ifEmpty { bgReadings },
            minTimestamp = minTimestamp,
            minX = 0.0,
            maxX = maxX,
            minY = niceBgScale.min,
            maxY = niceBgScale.max,
            gap = axisSpan * MARKER_GAP_FRACTION,
            fallbackY = niceBgScale.min + axisSpan * MARKER_FALLBACK_FRACTION
        )
        rebuildChart(
            basalData, targetData, epsPoints, activityData, minBgY, activityAxisMax, markers, visibleTimeRange,
            bolusPoints, carbPoints, niceBgScale.min, niceBgScale.max
        )
    })
    LaunchedEffect(modelProducer) {
        snapshotFlow { latestRebuildInputs }.collect { latestRebuild() }
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

    // Top band lines (layer 5): SMB delivery, acce weight, pp weight, profile basal. Graph 5 only.
    val topBandLineList = remember(acceColor, ppColor) {
        fun line(color: Color) = LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
            areaFill = null
        )
        listOf(line(SMB_BAND_COLOR), line(acceColor), line(ppColor), line(PROFILE_BASAL_BAND_COLOR))
    }

    // =========================================================================
    // Treatment marker lines (layer 5) — order must match block 6 in rebuildChart
    // =========================================================================

    val bgCheckPainter = rememberVectorPainter(IcBgCheck)

    val treatmentLines = remember(insulinColor, carbsColor, bgCheckColor, bgCheckPainter) {
        buildList {
            for (size in listOf(MARKER_SIZE_SMALL, MARKER_SIZE_MEDIUM, MARKER_SIZE_LARGE)) {
                add(createMarkerLine(insulinColor, TriangleShape, size))
            }
            for (size in listOf(MARKER_SIZE_SMALL, MARKER_SIZE_MEDIUM, MARKER_SIZE_LARGE)) {
                add(createMarkerLine(carbsColor, TriangleBelowShape, size))
            }
            add(
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                    areaFill = null,
                    pointProvider = LineCartesianLayer.PointProvider.single(
                        LineCartesianLayer.Point(
                            component = PainterComponent(bgCheckPainter, tint = bgCheckColor),
                            size = MARKER_SIZE_CALIBRATION
                        )
                    )
                )
            )
        }
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
    val targetRangeArea = AapsTheme.generalColors.bgTargetRangeArea
    val inRangeBox = remember(lowMark, highMark, targetRangeArea) {
        HorizontalBox(
            y = { lowMark..highMark },
            box = ShapeComponent(fill = Fill(targetRangeArea)),
            verticalAxisPosition = Axis.Position.Vertical.Start
        )
    }

    // Upstream's press-and-hold inspect (2026-10-16 merge): its marker, and its line on top of our own decorations.
    val inspectMarker = remember(inspect) { inspect?.let { GraphInspectMarker(it) } }
    // Always called, so the composable calls do not depend on inspect
    val defaultMarkerController = CartesianMarkerController.rememberShowOnPress()
    val inspectLine = rememberInspectLine(inspect)
    fun isfColor(kind: DominantIsf?): Color = when (kind) {
        DominantIsf.ACCE -> acceColor
        DominantIsf.BG   -> bgIsfColor
        DominantIsf.PP   -> ppColor
        DominantIsf.DURA -> duraColor
        else             -> smbMarkColor
    }
    val smbText = rememberTextMeasurer(cacheSize = LABEL_TEXT_CACHE_SIZE)
    val smbStack = remember(treatments, bgReadings, minTimestamp, graphDisplay.showSmbLabels) {
        if (!graphDisplay.showSmbLabels) return@remember emptyList()
        val smbs = treatments.boluses.filter { it.bolusType == BolusType.SMB && it.label.isNotEmpty() }
        val times = smbs.map { it.timestamp }
        val stack = smbStackIndex(times)
        // Each number sits at its own SMB time (as on 3426 and graph 2) and stacks upward; older doses are no longer
        // moved onto the newest dose's column (columnX), which put them away from their own arrow.
        smbs.mapIndexed { index, smb ->
            val dot = bgReadings.minByOrNull { abs(it.timestamp - smb.timestamp) }
            SmbStackItem(
                x = timestampToX(smb.timestamp, minTimestamp),
                label = formatBolusLabel(smb.amount, decimalFormatter),
                stackIndex = stack[index],
                anchorY = dot?.value,
                color = isfColor(dot?.dominantIsf),
                tapTimestamp = smb.timestamp,
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
    val smbLabelSize = rememberSmbLabelSize()
    // The newest BG value next to its dot (2026-10-27, per explicit request), 1.6 x the SMB number size. Only when it is
    // from the last 15 minutes, so an older day in the history browser gets no label.
    val lastBgText = rememberTextMeasurer(cacheSize = LABEL_TEXT_CACHE_SIZE)
    val lastBgLabel = remember(bgReadings, minTimestamp, nowTimestamp, lastBgText, smbLabelSize, lowColor, inRangeColor, highColor) {
        bgReadings.filter { it.type == BgType.REGULAR || it.type == BgType.BUCKETED }.maxByOrNull { it.timestamp }
            ?.takeIf { nowTimestamp - it.timestamp <= 15 * 60_000L }?.let { bg ->
            val mgdl = if (profileUtil.units == GlucoseUnit.MGDL) bg.value else bg.value * Constants.MMOLL_TO_MGDL
            LastBgLabel(
                x = timestampToX(bg.timestamp, minTimestamp),
                bg = bg.value,
                text = profileUtil.fromMgdlToStringInUnits(mgdl),
                color = when (bg.range) {
                    BgRange.HIGH     -> highColor
                    BgRange.IN_RANGE -> inRangeColor
                    BgRange.LOW      -> lowColor
                },
                textMeasurer = lastBgText,
                labelSize = smbLabelSize * 1.6f,
            )
        }
    }
    val smbNumbers = remember(smbStack, smbText, smbLabelSize, treatments) {
        SmbStackLabels(smbStack, smbText, pinToBottom = false, labelSize = smbLabelSize, onBounds = { bounds ->
            tapTargets.smbLabels = bounds.mapNotNull { (item, rect) ->
                treatments.boluses.firstOrNull { it.bolusType == BolusType.SMB && it.timestamp == item.tapTimestamp }?.let { GraphTapHit(rect, smb = it) }
            }
        })
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
    val carbText = rememberTextMeasurer(cacheSize = LABEL_TEXT_CACHE_SIZE)
    val carbNumbers = remember(carbLabels, carbText) {
        SmbStackLabels(carbLabels, carbText, pinToBottom = false)
    }
    // Live's Tier 3 UAM boost writes an announcement "B" (2026-10-20, per explicit request). Live draws it as "B" over
    // the BG on its main graph; here it was only an icon on the treatment row. Drawn the same way now.
    val uamBoostColor = AapsTheme.elementColors.announcement
    val uamBoostText = rememberTextMeasurer(cacheSize = LABEL_TEXT_CACHE_SIZE)
    val uamBoostLabels = remember(treatments, bgReadings, minTimestamp, uamBoostColor) {
        treatments.therapyEvents
            .filter { it.eventType == TherapyEventType.ANNOUNCEMENT && it.label.trim() == UAM_BOOST_GRAPH_MARK }
            .mapNotNull { event ->
                val y = bgReadings.minByOrNull { abs(it.timestamp - event.timestamp) }?.value ?: return@mapNotNull null
                SmbStackItem(
                    x = timestampToX(event.timestamp, minTimestamp),
                    label = UAM_BOOST_GRAPH_MARK,
                    stackIndex = 0,
                    anchorY = y,
                    color = uamBoostColor,
                )
            }
    }
    val uamBoostMarks = remember(uamBoostLabels, uamBoostText) {
        SmbStackLabels(uamBoostLabels, uamBoostText, pinToBottom = false)
    }
    val bolusText = rememberTextMeasurer(cacheSize = LABEL_TEXT_CACHE_SIZE)
    val bolusStack = remember(treatments, minTimestamp, lowMark, automaticCleanGraph) {
        if (automaticCleanGraph) return@remember emptyList()
        val boluses = treatments.boluses.filter { it.bolusType == BolusType.NORMAL && it.label.isNotEmpty() }
        val stack = smbStackIndex(boluses.map { it.timestamp })
        boluses.mapIndexed { index, bolus ->
            SmbStackItem(
                x = timestampToX(bolus.timestamp, minTimestamp),
                // The number only, without "U" (2026-10-23, per explicit request); the press-and-hold pill keeps the unit
                label = bolus.number.ifEmpty { bolus.label },
                stackIndex = stack[index],
                anchorY = lowMark,
                color = Color(0xFFFFFF00),
                tapTimestamp = bolus.timestamp,
            )
        }
    }
    val bolusNumbers = remember(bolusStack, bolusText, treatments) {
        SmbStackLabels(bolusStack, bolusText, pinToBottom = false, onBounds = { bounds ->
            tapTargets.labels = bounds.mapNotNull { (item, rect) ->
                treatments.boluses.singleOrNull { it.timestamp == item.tapTimestamp }?.let { GraphTapHit(rect, bolus = it) }
            }
        })
    }
    val epsText = rememberTextMeasurer(cacheSize = LABEL_TEXT_CACHE_SIZE)
    val epsNumbers = remember(epsLabelAnchors, epsText, profileSwitchColor) {
        SmbStackLabels(
            epsLabelAnchors.map { it.copy(color = profileSwitchColor) },
            epsText,
            pinToBottom = false
        )
    }
    val smbArrowMarks = remember(smbArrows) { SmbArrows(smbArrows) }
    val smbBaseArrowMarks = remember(smbBaseArrows) { SmbArrows(smbBaseArrows, pinToBottom = true) }
    // A zero bolus: an SMB-style arrow under the BG dot, a little larger, in the bolus colour. Its 0 label stays at
    // the bottom (bolusStack).
    // In the automatic clean graph every bolus is a slim arrow under its BG dot, in the bolus colour, like a zero bolus
    val bolusArrowMarks = remember(treatments, bgReadings, minTimestamp, automaticCleanGraph) {
        val boluses = if (automaticCleanGraph) treatments.boluses.filter { it.bolusType == BolusType.NORMAL && it.amount > 0.0 } else emptyList()
        SmbArrows(
            boluses.map { bolus ->
                SmbStackItem(
                    x = timestampToX(bolus.timestamp, minTimestamp),
                    label = bolus.number.ifEmpty { bolus.label },
                    stackIndex = 0,
                    anchorY = bgReadings.minByOrNull { abs(it.timestamp - bolus.timestamp) }?.value,
                    color = Color(0xFFFF00FF),
                )
            },
            scale = ZERO_BOLUS_ARROW_SCALE
        )
    }
    val zeroBolusArrowMarks = remember(treatments, bgReadings, minTimestamp) {
        val zeroBoluses = treatments.boluses.filter { it.bolusType == BolusType.NORMAL && it.amount <= 0.0 }
        SmbArrows(
            zeroBoluses.map { bolus ->
                SmbStackItem(
                    x = timestampToX(bolus.timestamp, minTimestamp),
                    label = bolus.number.ifEmpty { bolus.label },
                    stackIndex = 0,
                    anchorY = bgReadings.minByOrNull { abs(it.timestamp - bolus.timestamp) }?.value,
                    color = Color(0xFFFF00FF),
                )
            },
            scale = ZERO_BOLUS_ARROW_SCALE
        )
    }
    val tapReadings = bgReadings + bucketedData +
        (if (showRaw) seriesRegistry[SERIES_RAW].orEmpty() else emptyList()) +
        (if (showUkf) seriesRegistry[SERIES_UKF].orEmpty() else emptyList())
    val tapReporter = remember(tapReadings, bgReadings, treatments, minTimestamp, lowMark) {
        GraphPointTapReporter(
            tapTargets, tapReadings, bgReadings, treatments.boluses.filter { it.bolusType == BolusType.NORMAL }, minTimestamp,
            smbs = treatments.boluses.filter { it.bolusType == BolusType.SMB },
            lowMark = lowMark,
        )
    }
    val basalPeaks = remember(basalData, nowTimestamp) {
        if (showBasalOnBgGraph) hourlyPeaks(basalData.actualBasal, nowTimestamp, stepped = true) else emptyList()
    }
    val basalPeakLabel = remember(basalPeaks, minTimestamp, smbText, basalColor) {
        basalPeaks.takeIf { it.isNotEmpty() }?.let { peaks ->
            RecentPeakLabels(
                peaks.map { PeakMark(timestampToX(it.timestamp, minTimestamp), it.value, decimalFormatter.to2Decimal(it.value)) },
                basalColor, smbText, endAxis = true
            )
        }
    }
    val geometryReporter = rememberChartGeometryReporter(geometryHolder)
    // Upstream's clock change mark (2026-10-27 merge): a dashed line and a clock where the UTC offset of the readings changes
    val clockChanges = rememberClockChangeDecoration(minTimestamp, maxTimestamp, bgReadings, showIcon = true)
    val decorations = remember(inRangeBox, nowLine, clockChanges, smbNumbers, smbArrowMarks, smbBaseArrowMarks, zeroBolusArrowMarks, bolusArrowMarks, bolusNumbers, carbNumbers, uamBoostMarks, epsNumbers, tapReporter, basalPeakLabel, lastBgLabel, inspectLine, geometryReporter) {
        listOf(inRangeBox, nowLine, clockChanges, smbNumbers, smbArrowMarks, smbBaseArrowMarks, zeroBolusArrowMarks, bolusArrowMarks, bolusNumbers, carbNumbers, uamBoostMarks, epsNumbers, tapReporter) + listOfNotNull(basalPeakLabel, lastBgLabel, inspectLine, geometryReporter)
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
    // 2026-10-09, per explicit request, MAIN graph only (Graph 5 is unchanged): the ISF line and steps line sit in a strip directly above
    // the chart, and the hypo prediction line in a strip directly under it, with the DR line and target offset below ISF and steps, both off the plot. The chart keeps the height it
    // was given; the two strips add height.
    val stripSp = 11.sp
    val stripStyle = TextStyle(
        fontSize = stripSp,
        lineHeight = stripSp,
        fontWeight = FontWeight.Bold,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both
        )
    )
    Column(modifier = Modifier.fillMaxWidth()) {
    // 2026-10-18: the automatic clean graph hides these rows but keeps their space (alpha 0), so the graphs below do not jump
    // up when a zoom crosses the clean-graph limit. Only the user's own switch (savedGraphDisplay) removes them.
    if (savedGraphDisplay.showTextLines && !topBandLines && (autoIsfGraph.statusIsf != null || autoIsfGraph.statusSteps != null ||
            autoIsfGraph.statusRatio != null || autoIsfGraph.statusTarget != null)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 40.dp, top = 2.dp, bottom = 2.dp)
                .alpha(if (graphDisplay.showTextLines) 1f else 0f)
        ) {
            // The last item of the first three lines (smb=, HP=, IOd5=) is left off the strip. It is still in the data, so the
            // AutoISF detail dialog shows the whole line.
            autoIsfGraph.statusIsf?.let { line ->
                IsfStatusLine(line.withoutLastItem("smb="), stripStyle, acceColor, bgIsfColor, ppColor, duraColor)
            }
            autoIsfGraph.statusSteps?.let { line ->
                Text(text = line.withoutLastItem("HP="), color = duraColor, style = stripStyle)
            }
            // The DR line and the target offset line (the latter was in the corner of the IOB graph) follow, still off the plot.
            autoIsfGraph.statusRatio?.let { line ->
                Text(text = line.withoutLastItem("IOd5="), color = STATUS_RATIO_COLOR, style = stripStyle)
            }
            autoIsfGraph.statusTarget?.let { line ->
                Text(text = line, color = STATUS_TARGET_COLOR, style = stripStyle)
            }
        }
    }
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
            // Layer 5: top band lines (start axis — shares BG Y-axis range, values placed in rebuildChart)
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(topBandLineList),
                rangeProvider = startAxisRangeProvider,
                verticalAxisPosition = Axis.Position.Vertical.Start
            ),
            // Layer 6: Treatment markers (start axis — drawn last, see block 6)
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(treatmentLines),
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
            getXStep = { _, _, _ -> 1.0 },
            marker = inspectMarker,
            markerVisibilityListener = inspect?.visibilityListener,
            markerController = inspect?.controller ?: defaultMarkerController
        ),
        modelProducer = modelProducer,
        modifier = Modifier.fillMaxSize().pointerInput(tapTargets) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var moved = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1 || event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) {
                        moved = true
                        selectedBg = null
                        selectedSmb = null
                    }
                    if (event.changes.none { it.pressed }) {
                        val up = event.changes.firstOrNull { it.id == down.id }
                        if (!moved && up != null && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                            val hit = tapTargets.at(up.position)
                            selectedBg = hit?.bg
                            selectedSmb = hit?.smb
                            hit?.bolus?.let { bolus ->
                                calculationLoaded = false
                                selectedBolus = bolus
                            }
                            if (hit != null) up.consume()
                        }
                        break
                    }
                } while (true)
            }
        },
        scrollState = scrollState,
        zoomState = zoomState
    )
    selectedBg?.let { reading ->
        val mgdl = if (profileUtil.units == GlucoseUnit.MGDL) reading.value else reading.value * Constants.MMOLL_TO_MGDL
        val valueText = profileUtil.fromMgdlToStringWithUnits(mgdl)
        // A raw or UKF dot is a copy of a BG reading with that value in place of the BG, so without a name the popup
        // looked like the BG (2026-10-20). The same object is in that line's list, so it is found by identity.
        val lineName = when {
            seriesRegistry[SERIES_UKF].orEmpty().any { it === reading } -> stringResource(CoreUiStrings.ukf_bg_shortname)
            seriesRegistry[SERIES_RAW].orEmpty().any { it === reading } -> stringResource(CoreUiStrings.raw_bg_shortname)
            else                                                         -> null
        }
        Surface(modifier = Modifier.align(Alignment.TopStart).padding(8.dp), tonalElevation = 4.dp, shadowElevation = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                Column {
                    Text(if (lineName != null) stringResource(CoreUiStrings.graph_point_line_value, lineName, valueText) else valueText)
                    Text(dateUtil.dateAndTimeString(reading.timestamp))
                }
                TextButton(onClick = { selectedBg = null }) { Text(stringResource(CoreUiStrings.close)) }
            }
        }
    }
    selectedSmb?.let { smb ->
        Surface(modifier = Modifier.align(Alignment.TopStart).padding(8.dp), tonalElevation = 4.dp, shadowElevation = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                Column {
                    Text(stringResource(CoreUiStrings.graph_point_line_value, stringResource(CoreUiStrings.smb_shortname), stringResource(InterfacesStrings.format_insulin_units, smb.amount)))
                    Text(dateUtil.dateAndTimeAndSecondsString(smb.timestamp))
                }
                TextButton(onClick = { selectedSmb = null }) { Text(stringResource(CoreUiStrings.close)) }
            }
        }
    }
    // How many hours the main graph shows now, at the top left next to the BG axis (2026-10-20, per explicit request).
    // Rounded to 5 minutes, so it does not flicker while a pinch settles.
    val shownSpanText = visibleTimeRange?.let { (from, to) ->
        val minutes = (((to - from) / 60_000.0) / 5.0).roundToLong() * 5
        val hours = (minutes / 60).toInt()
        val rest = (minutes % 60).toInt()
        if (rest == 0) stringResource(CoreUiStrings.format_hours_only, hours)
        else stringResource(CoreUiStrings.format_hour_minute, hours, rest)
    }
    val shownSpanStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!topBandLines && shownSpanText != null) {
        Text(text = shownSpanText, style = shownSpanStyle, modifier = Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = 2.dp))
    }
    if (topBandLines) {
        // Legend for the four top band lines, each name in its own line colour, followed by that line's last-24-hour range
        // (lowest on the lane bottom, highest on its top). A name is shown only when that line really has data to draw,
        // so the legend also says what is missing.
        val legend = buildList {
            val names = listOf("SMBdel" to SMB_BAND_COLOR, "aISFwt" to acceColor, "ppISFwt" to ppColor, "profBasal" to PROFILE_BASAL_BAND_COLOR)
            names.forEachIndexed { lane, (name, color) ->
                val line = topBandLines5[lane]
                val range = line.range
                if (line.points.size >= 2 && range != null) add("$name ${range.label()}" to color)
            }
        }
        // Two rows of two (with the ranges the names no longer fit on one row of a phone).
        Column(modifier = Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = 2.dp)) {
            // The shown hours first, then the legend below it
            if (shownSpanText != null) Text(text = shownSpanText, style = shownSpanStyle)
            legend.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { (name, color) ->
                        Text(text = name, color = color, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
    if (graphDisplay.showTextLines && topBandLines && (autoIsfGraph.statusIsf != null || autoIsfGraph.statusSteps != null)) {
        val lineSp = 11.sp
        val threeLinesDown = with(LocalDensity.current) { (lineSp * 3).toDp() }
        val linesUp = with(LocalDensity.current) { (lineSp * 1.0f).toDp() }
        val tight = TextStyle(
            fontSize = lineSp,
            lineHeight = lineSp,
            fontWeight = FontWeight.Bold,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            )
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = (44.dp - threeLinesDown + linesUp).coerceAtLeast(0.dp))
        ) {
            autoIsfGraph.statusIsf?.let { line ->
                IsfStatusLine(line, tight, acceColor, bgIsfColor, ppColor, duraColor)
            }
            autoIsfGraph.statusSteps?.let { line ->
                Text(text = line, color = duraColor, style = tight)
            }
        }
    }
    }
    // The IOB graph below is pulled 8dp up under this strip, hence the extra bottom padding.
    if (!topBandLines && autoIsfGraph.hypoPrediction != null) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp)) {
            autoIsfGraph.hypoPrediction?.let { hypo ->
                Text(text = "hypoprediction= ${oneDecimalText(hypo)}", color = Color.White, style = stripStyle)
            }
        }
    }
    }
}

private fun oneDecimalText(value: Double): String {
    val scaled = round(value * 10.0).toInt()
    val sign = if (scaled < 0) "-" else ""
    val whole = abs(scaled)
    return "$sign${whole / 10}.${whole % 10}"
}

/** One colour per field, the same colours as the UK ISF row. */
@Composable
private fun IsfStatusLine(
    line: String,
    style: TextStyle,
    acceColor: Color,
    bgColor: Color,
    ppColor: Color,
    duraColor: Color
) {
    val parts = line.split(" ").filter { it.isNotEmpty() }
    fun valueOf(key: String): Double? = parts.firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.toDoubleOrNull()
    val acceDev = abs((valueOf("ac") ?: 1.0) - 1.0)
    val bgDev = abs((valueOf("bg") ?: 1.0) - 1.0)
    val ppDev = abs((valueOf("pp") ?: 1.0) - 1.0)
    val duraDev = abs((valueOf("du") ?: 1.0) - 1.0)
    val maxDev = maxOf(acceDev, bgDev, ppDev, duraDev)
    val dominant = when {
        maxDev <= 0.01 -> null
        acceDev >= maxDev -> acceColor
        bgDev >= maxDev -> bgColor
        ppDev >= maxDev -> ppColor
        else -> duraColor
    }
    val fallbackFinal = Color(0xFFFF6060)
    val fallbackSmb = Color(0xFF4A9EFF)
    Row {
        parts.forEachIndexed { index, token ->
            val color = when (token.substringBefore("=")) {
                "f" -> dominant ?: fallbackFinal
                "ac" -> acceColor
                "bg" -> bgColor
                "pp" -> ppColor
                "du" -> duraColor
                "smb" -> {
                    val smb = valueOf("smb")
                    if (smb == null || smb == 0.0) fallbackSmb else dominant ?: fallbackSmb
                }
                else -> duraColor
            }
            if (index > 0) Spacer(Modifier.width(4.dp))
            Text(text = token, color = color, style = style)
        }
    }
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
        val now = Clock.System.now().toEpochMilliseconds()
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

/** The line without its last space separated item, when that item starts with [prefix]. Otherwise the line as it was. */
private fun String.withoutLastItem(prefix: String): String {
    val items = trim().split(' ')
    return if (items.size > 1 && items.last().startsWith(prefix)) items.dropLast(1).joinToString(" ") else this
}

/**
 * A line that draws no line at all, only a [shape] marker in [color] at every point. Used for the
 * treatment markers on the BG graph, one line per size.
 */
private fun createMarkerLine(color: Color, shape: Shape, size: Dp): LineCartesianLayer.Line =
    LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
        areaFill = null,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(color), shape = shape), size = size)
        )
    )
