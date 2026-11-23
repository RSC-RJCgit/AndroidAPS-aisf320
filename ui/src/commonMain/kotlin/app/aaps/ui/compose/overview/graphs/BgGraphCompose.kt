package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.concurrent.Volatile
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.math.round
import kotlin.time.Clock
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.AutoIsfGraphData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
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
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.EpsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.DominantIsf
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TargetLineData
import app.aaps.core.interfaces.overview.graph.TherapyEventGraphPoint
import app.aaps.core.interfaces.overview.graph.TherapyEventType
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.core.ui.compose.isLandscape
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.icons.IcBgCheck
import app.aaps.core.ui.compose.icons.IcProfile
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
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

/**
 * Fork: share of the BG graph's height the highest basal reaches when "Basal on the BG graph (as v3)" is on. 3426 tops
 * basal out just under the low mark (low mark / 1.2), which is about a fifth of a usual BG graph, so the columns stay
 * below the BG readings. (The IOB graph's flipped basal keeps [BASAL_HEIGHT_FRACTION].)
 */
private const val BG_GRAPH_BASAL_HEIGHT_FRACTION = 0.22

/** The note the 3426 loop phone and (since 2026-11-19) the v4 masters write when Tier 3's UAM boost fires. */
private const val TIER3_NOTE = "UamBst"

/**
 * The Tier 3 UAM boost SMBs (2026-11-19, per explicit request): every SMB above 0 from 1 min before to 3 min after a
 * [TIER3_NOTE] note (the note is written in the loop run that asks for the dose, which is given just after).
 */
internal fun tier3Values(treatments: TreatmentGraphData): List<BolusGraphPoint> {
    val notes = treatments.therapyEvents
        .filter { (it.eventType == TherapyEventType.GENERAL || it.eventType == TherapyEventType.GENERAL_WITH_DURATION) && it.label.split(' ', ',', ';').any { word -> word.trim() == TIER3_NOTE } }
        .map { it.timestamp }
    if (notes.isEmpty()) return emptyList()
    return treatments.boluses
        .filter { it.bolusType == BolusType.SMB && it.isValid && it.amount > 0.0 }
        .filter { smb -> notes.any { note -> smb.timestamp - note in -60_000L..180_000L } }
}

/** The announcement text Live writes for its Tier 3 UAM boost, drawn over the BG as on Live's main graph. */
private const val UAM_BOOST_GRAPH_MARK = "B"

/** Announcements this close together are stacked, so their labels do not sit on one another */
private const val ANNOUNCEMENT_STACK_MS = 20L * 60 * 1000

/**
 * The announcements the BG graph labels at the BG line. A client shows every one Live writes - its hypo alarms (_____H4,
 * __________A4), gentle hypo (________________Gentle5), Batt1%, PreSoak24hrs and the rest - as Live's own BG graph does
 * (2026-11-01, per explicit request); they were only written on the notes graph. A master labels none of them, not even
 * Live's "B" (2026-11-01, per explicit request): the "B" mark is for clients only.
 */
internal fun bgGraphAnnouncements(events: List<TherapyEventGraphPoint>, isClient: Boolean): List<TherapyEventGraphPoint> =
    if (!isClient) emptyList()
    else events.filter { it.eventType == TherapyEventType.ANNOUNCEMENT && it.label.isNotBlank() }.sortedBy { it.timestamp }
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

/** AutoISF fork (2026-11-14, from blaqone's AAPS-autoISF): the BG parabola, the fitted part and its extrapolation */
private const val SERIES_PARABOLA_FIT = "parabola_fit"
private const val SERIES_PARABOLA_EXTRAPOLATION = "parabola_extrapolation"

/** The zero-bolus arrows of [others] (larger) and of [fpu] (protein/fat, SMB size), drawn as one decoration. */
private class ZeroBolusArrows(private val others: Decoration, private val fpu: Decoration) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) {
        others.drawUnderLayers(context)
        fpu.drawUnderLayers(context)
    }

    override fun drawOverLayers(context: CartesianDrawingContext) {
        others.drawOverLayers(context)
        fpu.drawOverLayers(context)
    }
}

/** A dose check dot sits above the BG reading nearest its time when that reading is this close; else at the top. */
private const val DOSE_CHECK_BG_REACH_MS = 10 * 60_000L

/** A zero-bolus arrow is this much larger than an SMB arrow. */
private const val ZERO_BOLUS_ARROW_SCALE = 1.5f
private const val SERIES_CARBS = "carbs"

/** All prediction series identifiers */
private val PREDICTION_SERIES = listOf(SERIES_PRED_IOB, SERIES_PRED_COB, SERIES_PRED_ACOB, SERIES_PRED_UAM, SERIES_PRED_ZT)

/**
 * From the centre of a reading to the tip of the marker that points at it: the 6 dp dot's radius
 * and 1 dp of air, the same rule as the watch. Both halves of a meal keep this distance, so the
 * reading stays visible as the seam between them. The gap is drawn into the marker shapes, see
 * [createMarkerLine], not added to the marker's y: a dp is steady, a share of the axis is not.
 */
private val MARKER_TIP_GAP = 4.dp

/** Height of a marker that has no reading near it, as a share of the Y axis above its floor. */
private const val MARKER_FALLBACK_FRACTION = 0.04

/** Size of the icon drawn at a measured glucose value (calibration, BG check). */
private val MARKER_SIZE_CALIBRATION = 14.dp

/**
 * Y data for an empty marker slot. Far below any axis floor, so Vico clips it away — the treatment
 * block always emits every series, so the line list never has to be matched to the data.
 */
private val MARKER_EMPTY_Y = listOf(-10_000.0, -10_000.0)

/**
 * Protein/fat (FPU) dose triangles, values and 0 U arrows (2026-11-18, per explicit request). The yellow every bolus value had
 * before; every other bolus now takes the insulin colour of its triangle.
 */
private val FPU_BOLUS_COLOR = Color(0xFFFFFF00)

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

/** The steps arrow (2026-10-29): what step counts took off an SMB, mirrored on the SMB's baseline arrow. */
private val STEP_CUT_ARROW_COLOR = Color(0xFFFF2020)
private val PROFILE_BASAL_BAND_COLOR = Color(0xFFFFA040)

/** Graph 5 top band tap (2026-10-29): a line further than this from the tapped time is left out of the popup. */
private const val TOP_BAND_TAP_MAX_MS = 5 * 60_000L

/** Graph 5 (2026-10-29): updated while drawing, so a tap in the top band can be turned into a time on the current scroll and zoom. */
private class TopBandTapTarget : Decoration {
    private var bounds: Rect? = null
    private var bandBottom = 0f
    private var originX = 0f
    private var minX = 0.0
    private var minutesPerPixel = 0.0

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            bounds = layerBounds
            // The band is the top BG_TOP_BAND_FRACTION of the BG axis
            bandBottom = layerBounds.top + layerBounds.height * BG_TOP_BAND_FRACTION.toFloat()
            originX = layerBounds.left + layerDimensions.startPadding - scroll
            minX = ranges.minX
            minutesPerPixel = if (layerDimensions.xSpacing > 0f) ranges.xStep / layerDimensions.xSpacing else 0.0
        }
    }

    /** The time under [position], or null when it is not in the top band. */
    fun timestampAt(position: Offset, minTimestamp: Long): Long? {
        val plot = bounds ?: return null
        if (position !in plot || position.y > bandBottom || minutesPerPixel <= 0.0) return null
        return minTimestamp + ((minX + (position.x - originX) * minutesPerPixel) * 60_000.0).toLong()
    }
}
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

/**
 * The main graph's fixed axis, 2.0 to 12.0 mmol/L, in the units the graph is drawn in. [fromZero] starts it at 0 instead
 * (2026-11-09, per explicit request): with basal on the BG graph the columns stand on 0, as on v3.
 */
internal fun mainGraphScale(mgdl: Boolean, fromZero: Boolean = false): NiceScale {
    val factor = if (mgdl) Constants.MMOLL_TO_MGDL else 1.0
    val min = if (fromZero) 0.0 else MAIN_GRAPH_MIN_MMOL * factor
    return NiceScale(min, MAIN_GRAPH_MAX_MMOL * factor, MAIN_GRAPH_STEP_MMOL * factor)
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
    scaleLookbackHours: Int,
    visibleTimeRange: Pair<Long, Long>? = null,
    /** Graph 5 only: draw SMB delivery, acce weight, pp weight and profile basal in the top band of the BG axis. */
    topBandLines: Boolean = false,
    /** Graph 5 only (2026-11-16, per explicit request): no insulin activity and no carb lines. */
    bglOnly: Boolean = false,
    automaticCleanGraph: Boolean = false,
    /** With [automaticCleanGraph]: also hide the text lines (the "Clean graph hides text lines" setting). */
    cleanGraphHidesTextLines: Boolean = false,
    /**
     * Simple graphs, main graph only (2026-11-21, per explicit request): the clean graph, its columns without their values, no profile
     * % numbers and stars, SMB numbers and arrows, bolus and carb values, carb split dots, zero and FPU bolus arrows, pod and
     * sensor marks, text lines and the strip under the graph. Kept: the readings and their last value, the manual bolus
     * triangles, the carb marks and the Tier 3 boost "B".
     */
    superClean: Boolean = false,
    /** Press and hold to see the values at a reading. Null turns it off. */
    inspect: GraphInspectState? = null,
    /** Filled with this graph's layout on each draw, for the time-based scroll and zoom sync (see ChartGeometry). */
    geometryHolder: ChartGeometryHolder? = null,
    modifier: Modifier = Modifier,
    masterZone: TimeZone? = null,
    basalSnapshot: BasalGraphData? = null,
    onRenderReady: (Boolean) -> Unit = {}
) {
    val dateUtil = LocalDateUtil.current
    val decimalFormatter = LocalDecimalFormatter.current
    val tapTargets = remember { GraphTapTargets() }
    var selectedBg by remember { mutableStateOf<BgDataPoint?>(null) }
    var selectedBolus by remember { mutableStateOf<BolusGraphPoint?>(null) }
    var selectedSmb by remember { mutableStateOf<BolusGraphPoint?>(null) }
    // Graph 5: the time tapped in the top band, for the popup with the four lines' values
    var selectedBandTime by remember { mutableStateOf<Long?>(null) }
    val topBandTap = remember { TopBandTapTarget() }
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
                        Text(graphTimestamp(dateUtil, bolus.timestamp, masterZone, showDate = true, showSeconds = true))
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
    val graphDisplay = (if (automaticCleanGraph) savedGraphDisplay.copy(
        showSmbLabels = false, basalToggleIndex = 2, showTextLines = savedGraphDisplay.showTextLines && !cleanGraphHidesTextLines
    ) else savedGraphDisplay).let {
        // The columns stay (2026-11-21, per explicit request); their values are left out with the other decorations
        if (superClean) it.copy(showSmbLabels = false, basalToggleIndex = 2, showTextLines = false) else it
    }
    val bucketedData by viewModel.bucketedDataFlow.collectAsStateWithLifecycle()
    val showPredictions = SeriesType.PREDICTIONS in bgOverlays
    // 2026-10-23, per explicit request: the automatic clean graph (zoomed out past its width) hides the insulin activity and
    // carbs model curves. Regular bolus labels stay visible. 2026-11-15, per explicit request: Raw and UKF raw BG are no
    // longer hidden by it; they follow their own overlay switches, as set.
    val showRaw = SeriesType.RAW_BG in bgOverlays
    val showUkf = SeriesType.UKF_BG in bgOverlays
    val rawPredictions by viewModel.predictionsFlow.collectAsStateWithLifecycle()
    val predictions = if (showPredictions) rawPredictions else emptyList()
    // AutoISF fork: the BG parabola (pink, as in AutoISF 3.2.1). Only its extrapolation counts as a prediction.
    val rawBgParabola by viewModel.bgParabolaGraphFlow.collectAsStateWithLifecycle()
    val bgParabolaPoints = remember(rawBgParabola, bgOverlays, showPredictions, automaticCleanGraph) {
        fun List<GraphDataPoint>.asBg() = map { BgDataPoint(it.timestamp, it.value, BgRange.IN_RANGE, BgType.REGULAR) }
        if (SeriesType.BG_PARABOLA !in bgOverlays || automaticCleanGraph) emptyList<BgDataPoint>() to emptyList()
        else rawBgParabola.fit.asBg() to (if (showPredictions) rawBgParabola.extrapolation.asBg() else emptyList())
    }
    val rawBasalData = basalSnapshot ?: viewModel.basalGraphFlow.collectAsStateWithLifecycle().value
    val targetData by viewModel.targetLineFlow.collectAsStateWithLifecycle()

    // Basal is a flipped overlay on the IOB graph. Settings > AutoISF "Basal on the BG graph (as v3)" also puts it on the
    // main graph (fork). Graph 5 always shows it (2026-11-09, per explicit request), whatever that setting says.
    // Keep the layer structure intact (dummy data) to avoid chart restructuring.
    val showBasalOnBgGraph = graphDisplay.basalOnBgGraph || topBandLines
    // 2026-11-19, per explicit request: Settings > AutoISF "Insulin total columns on the BG graph" puts the insulin delivered per
    // slice in place of basal. Main graph only (Graph 5 keeps basal for its top band).
    val insulinColumns = graphDisplay.bgGraphInsulinColumns && !topBandLines
    val maxBolusSetting = LocalPreferences.current.get(DoubleKey.SafetyMaxBolus)
    val basalData = if (showBasalOnBgGraph) rawBasalData else BasalGraphData(emptyList(), emptyList(), 0.0)
    val savedEpsPoints by viewModel.epsGraphFlow.collectAsStateWithLifecycle()
    // Simple graphs: no profile % numbers or stars
    val epsPoints = if (superClean) emptyList() else savedEpsPoints
    val showActivity = SeriesType.ACTIVITY in bgOverlays && !automaticCleanGraph && !bglOnly
    val showCarbModel = !automaticCleanGraph && !bglOnly
    val activityData by viewModel.activityGraphFlow.collectAsStateWithLifecycle()
    val chartConfig by viewModel.chartConfigFlow.collectAsStateWithLifecycle()
    val treatments by viewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val savedAutoIsfGraph = viewModel.autoIsfGraphFlow.collectAsStateWithLifecycle().value
    val autoIsfGraph = if (viewModel.showStepText(graphDisplay)) savedAutoIsfGraph else savedAutoIsfGraph.copy(statusSteps = null)

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

    // Insulin-total columns (2026-11-19/20, per explicit request), shared by the column layer and their value labels: a twelfth of
    // the shown span per column, on a grid from the loaded range's start, each in the ISF colour of the reading nearest its start.
    // Full height is the day's largest slice, at most the max bolus setting + 0.3 U (insulinColumnTop).
    val insulinColumnData = remember(
        insulinColumns, visibleTimeRange, basalData, treatments, bgReadings, minTimestamp, maxTimestamp, maxBolusSetting,
        nowTimestamp / 60_000L, scaleLookbackHours
    ) {
        if (!insulinColumns) emptyList<InsulinColumn>() to 1.0
        else {
            val span = visibleTimeRange?.let { it.second - it.first } ?: (maxTimestamp - minTimestamp)
            val sliceMs = (span / INSULIN_TOTAL_SLICES / 60_000L).coerceAtLeast(1L) * 60_000L
            val totals = insulinSliceTotals(
                minTimestamp, maxTimestamp, sliceMs, nowTimestamp, basalData.actualBasal, treatments.boluses, treatments.extendedBoluses
            )
            // The ISF colours stay in the clean graph too (2026-11-20, per explicit request), as on the IOB graph's basal
            // Use the recent window when the day's largest slice exceeds 1.5 U.
            insulinColumnsOf(totals, sliceMs, bgReadings, coloured = true) to insulinColumnTopLookBack(totals, maxBolusSetting, nowTimestamp, scaleLookbackHours)
        }
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
    val insulinColor = AapsTheme.elementColors.insulin


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

    val renderReporter = remember { GraphRenderReporter() }

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
        renderToken: GraphRenderToken,
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

                // AutoISF fork: the BG parabola, one point per minute
                for (parabola in listOf(SERIES_PARABOLA_FIT, SERIES_PARABOLA_EXTRAPOLATION)) {
                    val parabolaPoints = seriesRegistry[parabola]
                    if (parabolaPoints != null && parabolaPoints.size >= 2) {
                        val dataPoints = parabolaPoints
                            .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                            .sortedBy { it.first }
                        series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                        activeSeries.add(parabola)
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
                // Each rate as its height on the fixed basal scale (2026-11-19, per explicit request), see basalScaleHeight
                val basalTop = basalScaleTop(currentBasalData)
                fun addBasalSeries(points: List<GraphDataPoint>, top: Double = basalTop, linear: Boolean = false) {
                    if (points.size >= 2) {
                        val pts = points
                            .map {
                                timestampToX(it.timestamp, minTimestamp) to
                                    if (linear) (if (it.value > 0.0) (it.value / top).coerceAtMost(1.0) else 0.0)
                                    else basalScaleHeight(it.value, top)
                            }
                            .sortedBy { it.first }
                        series(x = pts.map { it.first }, y = pts.map { it.second })
                    } else {
                        // Dummy series - invisible at y=0
                        series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    }
                }
                if (insulinColumns) {
                    // Insulin-total columns (insulinColumnData): linear heights (2026-11-19, per explicit request). Each column goes in
                    // the basal slot of its ISF colour (2026-11-20, per explicit request), as the coloured basal columns; NONE stays in
                    // the actual-basal slot.
                    val (columns, columnTop) = insulinColumnData
                    fun stepsOf(kind: DominantIsf) = insulinColumnSteps(columns.filter { it.isf == kind })
                    addBasalSeries(emptyList())
                    addBasalSeries(stepsOf(DominantIsf.NONE), columnTop, linear = true)
                    addBasalSeries(stepsOf(DominantIsf.ACCE), columnTop, linear = true)
                    addBasalSeries(stepsOf(DominantIsf.BG), columnTop, linear = true)
                    addBasalSeries(stepsOf(DominantIsf.PP), columnTop, linear = true)
                    addBasalSeries(stepsOf(DominantIsf.DURA), columnTop, linear = true)
                } else {
                    addBasalSeries(currentBasalData.profileBasal)
                    addBasalSeries(currentBasalData.actualBasal)
                    // The ISF colours stay in the clean graph too (2026-11-20, per explicit request: the IOB graph's basal kept them,
                    // the main graph and Graph 5 went plain); the clean graph only makes the BG line plain green.
                    addBasalSeries(currentBasalData.acceTemp)
                    addBasalSeries(currentBasalData.bgTemp)
                    addBasalSeries(currentBasalData.ppTemp)
                    addBasalSeries(currentBasalData.duraTemp)
                }
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
                // From 0 as on v3 (2026-11-17), not from the axis bottom (2.0 mmol/L on the main graph).
                val floor = 0.0
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
            // moment they happened, see TreatmentMarkers.kt). Insulin points down at the reading from
            // above and carbs point up at it from below, so a meal entered with its bolus is one marker
            // with both tips on the curve; a measured value sits on its own value.
            // Drawn last on purpose: a carbs marker reaches into the curve, and under the BG layer it
            // was covered by the reading dots and the profile icon. It does cover the one reading
            // it sits on.
            // Fixed series order, always all 10: insulin small/medium/large, protein/fat insulin small/medium/large, carbs small/medium/large,
            // measured glucose. An empty slot gets a clipped dummy, so the line list below is a
            // plain fixed list instead of something that has to be matched to the data.
            lineModel {
                fun markerSeries(markers: List<TreatmentMarker>) {
                    if (markers.isEmpty()) series(x = listOf(0.0, 1.0), y = MARKER_EMPTY_Y)
                    else series(x = markers.map { it.x }, y = markers.map { it.y })
                }
                for (tier in MarkerTier.entries) markerSeries(currentMarkers.insulin.filter { it.tier == tier && !it.fpu })
                // Protein/fat doses in yellow (2026-11-18, per explicit request), three more slots after the insulin ones
                for (tier in MarkerTier.entries) markerSeries(currentMarkers.insulin.filter { it.tier == tier && it.fpu })
                for (tier in MarkerTier.entries) markerSeries(currentMarkers.carbs.filter { it.tier == tier })
                val measured = currentMarkers.calibrations
                if (measured.isEmpty()) series(x = listOf(0.0, 1.0), y = MARKER_EMPTY_Y)
                else series(x = measured.map { it.first }, y = measured.map { it.second })
            }

            // Forces Vico to reprocess this transaction even when the series data above is
            // identical to last time (see BG_VISIBLE_RANGE_KEY doc) — otherwise scrolling/zooming
            // would re-submit the same partials and get silently skipped, never picking up the
            // updated startAxisRangeProvider.
            extras {
                it[BG_VISIBLE_RANGE_KEY] = currentVisibleTimeRange?.first to currentVisibleTimeRange?.second
                it[GRAPH_RENDER_TOKEN] = renderToken
            }
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
        insulinColumnData, bgReadings, bucketedData, predictionsByType, rawPoints, ukfPoints, bgParabolaPoints, basalData, targetData, epsPoints, activityData, showActivity,
        showCarbModel, chartConfig, stableTimeRange, visibleTimeRange, treatments, graphDisplay.uniformGreenBg, autoIsfGraph, rawBasalData,
        topBandLines, topBandLines5, showBasalOnBgGraph, insulinColumns, maxBolusSetting
    )
    val renderToken = remember(rebuildInputs) { GraphRenderToken() }
    ReportGraphReady(renderReporter, renderToken, derivedTimeRange == null || visibleTimeRange != null, onRenderReady)
    val latestRebuildInputs by rememberUpdatedState(rebuildInputs)
    val latestRebuild by rememberUpdatedState<suspend () -> Unit>({
        seriesRegistry[SERIES_REGULAR] = bgReadings
        seriesRegistry[SERIES_BUCKETED] = bucketedData
        seriesRegistry[SERIES_RAW] = rawPoints
        seriesRegistry[SERIES_UKF] = ukfPoints
        seriesRegistry[SERIES_PARABOLA_FIT] = bgParabolaPoints.first
        seriesRegistry[SERIES_PARABOLA_EXTRAPOLATION] = bgParabolaPoints.second
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
        // With basal on this graph the axis starts at 0 (2026-11-09, per explicit request), so the columns stand on 0 as on v3.
        val niceBgScale = if (topBandLines) axisWithTopBand(if (showBasalOnBgGraph) 0.0 else dataMin, axisDataMax, BG_TOP_BAND_FRACTION)
        else mainGraphScale(chartConfig.lowMark > 30.0, fromZero = showBasalOnBgGraph)
        startAxisRangeProvider.maxX = maxX
        startAxisRangeProvider.minY = niceBgScale.min
        startAxisRangeProvider.maxY = niceBgScale.max
        startAxisRangeProvider.yStep = niceBgScale.step

        fun nearest(timestamp: Long): Double? =
            bgReadings.minByOrNull { abs(it.timestamp - timestamp) }?.value
        // A zero bolus gets a small arrow (zeroBolusArrows), not the big mark
        // Simple graphs: manual boluses only, no protein/fat (carb split) parts
        val bolusPoints = treatments.boluses.filter { it.bolusType == BolusType.NORMAL && it.amount > 0.0 && !(superClean && it.isFpu) }.mapNotNull { bolus ->
            nearest(bolus.timestamp)?.let { bolus.timestamp to it }
        }
        val carbPoints = treatments.carbs.mapNotNull { carb ->
            nearest(carb.timestamp)?.let { carb.timestamp to it }
        }
        // Activity scales to the axis as it is without the Graph 5 top band, so its peak never reaches the band lanes.
        // Main graph (2026-11-17, per explicit request "Match v3 exactly"): the activity height follows the 3426 panel top
        // (24 h highest or high mark, rounded, plus a margin), not the fixed 2-12 axis, so the peak lands where v3 puts it.
        // On high days that is above 12 mmol/L and the peak is cut off at the axis top.
        val activityAxisMax = if (!showActivity) maxBgY else if (topBandLines) niceScale(dataMin, axisDataMax).max
        else activityChartTop(maxBgY, chartConfig.highMark, chartConfig.lowMark > 30.0)
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
            // The markers sit on the reading itself; the space to it is in the shapes, see MARKER_TIP_GAP
            gap = 0.0,
            fallbackY = niceBgScale.min + axisSpan * MARKER_FALLBACK_FRACTION
        )
        rebuildChart(
            basalData, targetData, epsPoints, activityData, minBgY, activityAxisMax, markers, visibleTimeRange,
            bolusPoints, carbPoints, niceBgScale.min, niceBgScale.max, renderToken
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
    val timeFormatter = rememberTimeFormatter(minTimestamp, masterZone)
    val bottomAxisItemPlacer = rememberBottomAxisItemPlacer(minTimestamp, masterZone)

    // Line styles of the layers (2026-11-19): in their own function, so this one stays small enough for Android to compile
    val lines = rememberBgGraphLines(
        readingPointProvider, bucketedPointProvider, activeSeriesState.value, basalColor, acceColor, bgIsfColor, ppColor, duraColor, insulinColor
    )
    val bgLines = lines.bgLines
    val basalLines = lines.basalLines
    val targetLines = lines.targetLines
    val epsLines = lines.epsLines
    val activityLines = lines.activityLines
    val topBandLineList = lines.topBandLineList
    val treatmentLines = lines.treatmentLines

    // Fixed: full column height always fills BG_GRAPH_BASAL_HEIGHT_FRACTION, whatever is in view (2026-11-19, per explicit
    // request). It used to refit to the highest basal in the visible window, so columns changed height on every scroll.
    val basalMaxY = remember { basalScaleAxisMax(BG_GRAPH_BASAL_HEIGHT_FRACTION) }

    // =========================================================================
    // Decorations
    // =========================================================================

    val nowLineColor = MaterialTheme.colorScheme.onSurface
    val nowLine = rememberNowLine(minTimestamp, nowTimestamp, nowLineColor)

    // In-range belt — translucent band between lowMark and highMark on the BG axis
    val lowMark = chartConfig.lowMark
    val highMark = chartConfig.highMark
    // Drawn under the layers (upstream, ported 2026-10-27): Vico's HorizontalBox drew over them, and its translucent
    // colour tinted every reading and marker inside the band. The fork keeps its own theme colour for the band.
    val targetRangeArea = AapsTheme.generalColors.bgTargetRangeArea
    val inRangeBox = remember(lowMark, highMark, targetRangeArea) {
        InRangeBand(low = lowMark, high = highMark, color = targetRangeArea)
    }

    // Upstream's press-and-hold inspect: the marker draws nothing, the inspect line draws the line and the dot on the
    // reading (2026-10-27 port: a hold works on every graph).
    val inspectMarker = remember(inspect) { inspect?.let { SilentInspectMarker } }
    // Always called, so the composable calls do not depend on inspect
    val defaultMarkerController = CartesianMarkerController.rememberShowOnPress()
    val inspectLine = rememberInspectLine(inspect, dotOnReading = true)
    // The tap handler is set up once, so it reads the current time range through this
    val latestMinTimestamp by rememberUpdatedState(minTimestamp)
    // Labels, arrows and decorations (2026-11-19): in their own function, see rememberBgGraphDecorations
    val decorations = rememberBgGraphDecorations(
        treatments, bgReadings, bucketedData, graphDisplay, minTimestamp, maxTimestamp, nowTimestamp, lowMark, epsLabelAnchors,
        seriesRegistry, showRaw, showUkf, basalData, showBasalOnBgGraph && !insulinColumns, tapTargets, topBandTap, topBandLines, geometryHolder, masterZone,
        renderReporter, inRangeBox, nowLine, inspectLine, insulinColumnData, superClean
    )

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
    // The press-and-hold pill shows these rows, on the overview and in the History browser (2026-10-28), so a graph with a
    // pill does not draw them. Hidden rows reserve no band.
    if (inspect == null && !isLandscape() && graphDisplay.showTextLines && !topBandLines && (autoIsfGraph.statusIsf != null || autoIsfGraph.statusSteps != null ||
            autoIsfGraph.statusRatio != null || autoIsfGraph.statusTarget != null)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 40.dp, top = 2.dp, bottom = 2.dp)
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
                valueFormatter = CartesianValueFormatter { _, value, _ -> decimalFormatter.to0Decimal(value) },
                label = rememberTextComponent(
                    style = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    minWidth = TextComponent.MinWidth.fixed(30.dp)
                ),
                guideline = LineComponent(fill = Fill(AapsTheme.generalColors.graphGrid))
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = timeFormatter,
                itemPlacer = bottomAxisItemPlacer,
                // 10 sp (2026-11-21, per explicit request): the default size let neighbouring times overlap.
                label = rememberTextComponent(
                    style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp)
                ),
                guideline = LineComponent(fill = Fill(AapsTheme.generalColors.graphGrid))
            ),
            decorations = decorations,
            getXStep = { _, _, _ -> 1.0 },
            marker = inspectMarker,
            markerVisibilityListener = inspect?.visibilityListener,
            markerController = inspect?.controller ?: defaultMarkerController
        ),
        modelProducer = modelProducer,
        animationSpec = null,
        modifier = Modifier.fillMaxSize().pointerInput(tapTargets, topBandLines) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var moved = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1 || event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) {
                        moved = true
                        selectedBg = null
                        selectedSmb = null
                        selectedBandTime = null
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
                            // Graph 5: a tap in the top band that hit nothing else shows the four lines at that time
                            val bandTime = if (hit == null && topBandLines) topBandTap.timestampAt(up.position, latestMinTimestamp) else null
                            selectedBandTime = bandTime
                            if (hit != null || bandTime != null) up.consume()
                        }
                        break
                    }
                } while (true)
            }
        },
        scrollState = scrollState,
        zoomState = zoomState
    )
    // Popups, legend and Graph 5 status lines (2026-11-19): in their own function, see BgGraphOverlays
    BgGraphOverlays(
        selectedBg = selectedBg, selectedSmb = selectedSmb, selectedBandTime = selectedBandTime,
        onDismissBg = { selectedBg = null }, onDismissSmb = { selectedSmb = null }, onDismissBandTime = { selectedBandTime = null },
        seriesRegistry = seriesRegistry, visibleTimeRange = visibleTimeRange, topBandLines = topBandLines, topBandLines5 = topBandLines5,
        graphDisplay = graphDisplay, autoIsfGraph = autoIsfGraph, masterZone = masterZone,
    )
    }
    // Hypoprediction line and insulin totals row (2026-11-19): in their own function, see BgGraphBottomStrip
    if (!superClean) BgGraphBottomStrip(topBandLines, graphDisplay.bgGraphInsulinColumns, visibleTimeRange, nowTimestamp, rawBasalData, treatments, stripStyle)
    }
}

/** The line styles of the BG graph layers, built by [rememberBgGraphLines]. */
private class BgGraphLines(
    val bgLines: List<LineCartesianLayer.Line>,
    val basalLines: List<LineCartesianLayer.Line>,
    val targetLines: List<LineCartesianLayer.Line>,
    val epsLines: List<LineCartesianLayer.Line>,
    val activityLines: List<LineCartesianLayer.Line>,
    val topBandLineList: List<LineCartesianLayer.Line>,
    val treatmentLines: List<LineCartesianLayer.Line>,
)

/**
 * The line styles of [BgGraphCompose], moved out of it unchanged (2026-11-19, per explicit request). Android does not compile a
 * method past its instruction limit ("Method exceeds compiler instruction limit: 18640"), so the BG graph ran slowly, uncompiled.
 * Each `remember` here works as it did inline: this is called once per recomposition, always at the same place.
 */
@Composable
private fun rememberBgGraphLines(
    readingPointProvider: ReadingPointProvider,
    bucketedPointProvider: BucketedPointProvider,
    activeSeries: List<String>,
    basalColor: Color,
    acceColor: Color,
    bgIsfColor: Color,
    ppColor: Color,
    duraColor: Color,
    insulinColor: Color,
): BgGraphLines {
    val targetLineColor = AapsTheme.elementColors.tempTarget
    val activityColor = AapsTheme.elementColors.activity
    val carbsColor = AapsTheme.elementColors.carbs
    val bgCheckColor = AapsTheme.elementColors.bgCheck
    val iobPredColor = AapsTheme.generalColors.iobPrediction
    val cobPredColor = AapsTheme.generalColors.cobPrediction
    val aCobPredColor = AapsTheme.generalColors.aCobPrediction
    val uamPredColor = AapsTheme.generalColors.uamPrediction
    val ztPredColor = AapsTheme.generalColors.ztPrediction
    val profileSwitchColor = AapsTheme.elementColors.profileSwitch

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

    // AutoISF fork: BG parabola - the fitted part solid, the extrapolation dashed, as in AutoISF 3.2.1
    val bgParabolaColor = AapsTheme.generalColors.bgParabola
    val parabolaFitLine = remember(bgParabolaColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(bgParabolaColor)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.5.dp),
            areaFill = null
        )
    }
    val parabolaExtrapolationLine = remember(bgParabolaColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(bgParabolaColor)),
            stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 2.5.dp, cap = StrokeCap.Round, dashLength = 4.dp, gapLength = 4.dp),
            areaFill = null
        )
    }

    // Anchor both marks to glucose, with the triangle bodies on opposite sides of the reading.
    // About 15% smaller since 2026-11-15, per explicit request (were 32 and 28 dp)
    val bolusMarkLine = remember {
        createMarkerLine(Color(0xFFFF00FF), ::invertedTriangleShape, 27.dp)
    }
    val carbMarkLine = remember {
        createMarkerLine(Color(0xFFFF9800), ::triangleBelowShape, 24.dp)
    }

    val bgLines = remember(
        activeSeries, regularLine, bucketedLine, rawLine, ukfLine, parabolaFitLine, parabolaExtrapolationLine, iobPredLine, cobPredLine,
        aCobPredLine, uamPredLine, ztPredLine, bolusMarkLine, carbMarkLine, normalizerLine
    ) {
        buildList {
            if (SERIES_REGULAR in activeSeries) add(regularLine)
            if (SERIES_BUCKETED in activeSeries) add(bucketedLine)
            if (SERIES_RAW in activeSeries) add(rawLine)
            if (SERIES_UKF in activeSeries) add(ukfLine)
            // Same order as the series in the BG layer (after raw and UKF, before the predictions)
            if (SERIES_PARABOLA_FIT in activeSeries) add(parabolaFitLine)
            if (SERIES_PARABOLA_EXTRAPOLATION in activeSeries) add(parabolaExtrapolationLine)
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
            for (tier in MarkerTier.entries) {
                add(createMarkerLine(insulinColor, ::invertedTriangleShape, tier.markerSize))
            }
            // Protein/fat doses (2026-11-18): the same triangles in yellow
            for (tier in MarkerTier.entries) {
                add(createMarkerLine(FPU_BOLUS_COLOR, ::invertedTriangleShape, tier.markerSize))
            }
            for (tier in MarkerTier.entries) {
                add(createMarkerLine(carbsColor, ::triangleBelowShape, tier.markerSize))
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

    return BgGraphLines(bgLines, basalLines, targetLines, epsLines, activityLines, topBandLineList, treatmentLines)
}

/**
 * The labels, arrows and other decorations of [BgGraphCompose], moved out of it unchanged (2026-11-19, per explicit request),
 * for the same reason as [rememberBgGraphLines]. Returns the decoration list the chart draws.
 */
@Composable
private fun rememberBgGraphDecorations(
    treatments: TreatmentGraphData,
    bgReadings: List<BgDataPoint>,
    bucketedData: List<BgDataPoint>,
    graphDisplay: GraphDisplay,
    minTimestamp: Long,
    maxTimestamp: Long,
    nowTimestamp: Long,
    lowMark: Double,
    epsLabelAnchors: List<SmbStackItem>,
    seriesRegistry: Map<String, List<BgDataPoint>>,
    showRaw: Boolean,
    showUkf: Boolean,
    basalData: BasalGraphData,
    showBasalOnBgGraph: Boolean,
    tapTargets: GraphTapTargets,
    topBandTap: TopBandTapTarget,
    topBandLines: Boolean,
    geometryHolder: ChartGeometryHolder?,
    masterZone: TimeZone?,
    renderReporter: GraphRenderReporter,
    inRangeBox: Decoration,
    nowLine: Decoration,
    inspectLine: Decoration?,
    insulinColumnData: Pair<List<InsulinColumn>, Double>,
    superClean: Boolean,
): List<Decoration> {
    val decimalFormatter = LocalDecimalFormatter.current
    val profileUtil = LocalProfileUtil.current
    val acceColor = AapsTheme.generalColors.acceIsf
    val bgIsfColor = AapsTheme.generalColors.bgIsf
    val ppColor = AapsTheme.generalColors.ppIsf
    val duraColor = AapsTheme.generalColors.duraIsf
    val basalColor = AapsTheme.elementColors.tempBasal
    val insulinColor = AapsTheme.elementColors.insulin
    val smbMarkColor = AapsTheme.elementColors.insulin
    val profileSwitchColor = AapsTheme.elementColors.profileSwitch
    fun isfColor(kind: DominantIsf?): Color = when (kind) {
        DominantIsf.ACCE -> acceColor
        DominantIsf.BG   -> bgIsfColor
        DominantIsf.PP   -> ppColor
        DominantIsf.DURA -> duraColor
        else             -> smbMarkColor
    }
    // Simple graphs draw no SMB or bolus numbers, so their last tap areas must not stay behind
    LaunchedEffect(superClean) {
        if (superClean) {
            tapTargets.smbLabels = emptyList()
            tapTargets.labels = emptyList()
        }
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
    val smbNumbers = remember(smbStack, smbText, smbLabelSize, treatments) {
        SmbStackLabels(smbStack, smbText, pinToBottom = false, labelSize = smbLabelSize, onBounds = { bounds ->
            tapTargets.smbLabels = bounds.mapNotNull { (item, rect) ->
                treatments.boluses.firstOrNull { it.bolusType == BolusType.SMB && it.timestamp == item.tapTimestamp }?.let { GraphTapHit(rect, smb = it) }
            }
        })
    }
    val smbBaseArrows = remember(treatments, bgReadings, minTimestamp, lowMark) {
        val smbs = treatments.boluses.filter { it.bolusType == BolusType.SMB }
        smbs.map { smb ->
            val dot = bgReadings.minByOrNull { abs(it.timestamp - smb.timestamp) }
            SmbStackItem(
                x = timestampToX(smb.timestamp, minTimestamp),
                label = smb.label,
                stackIndex = 0,
                anchorY = lowMark,
                color = isfColor(dot?.dominantIsf),
                stemUnits = 1 + (((smb.amount - 0.05 + 0.001) / 0.05).toInt().coerceAtLeast(0)),
            )
        } + smbs.filter { it.stepCut >= 0.01 }.map { smb ->
            // Steps arrow (2026-10-29, per explicit request): red, tip to tip with the SMB arrow, rising into the green band.
            // Its shaft is the insulin step counts took off this SMB, on the SMB arrow's scale.
            SmbStackItem(
                x = timestampToX(smb.timestamp, minTimestamp),
                label = smb.label,
                stackIndex = 0,
                anchorY = lowMark,
                color = STEP_CUT_ARROW_COLOR,
                stemUnits = 1 + (((smb.stepCut - 0.05 + 0.001) / 0.05).toInt().coerceAtLeast(0)),
                mirrored = true,
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
    val isClient = LocalConfig.current.AAPSCLIENT
    val uamBoostLabels = remember(treatments, bgReadings, minTimestamp, uamBoostColor, isClient, superClean) {
        var groupStart = Long.MIN_VALUE
        var stack = 0
        bgGraphAnnouncements(treatments.therapyEvents, isClient)
            // Simple graphs: only the Tier 3 boost "B", not G, H4 or A4
            .filter { !superClean || it.label.trim() == UAM_BOOST_GRAPH_MARK }
            .mapNotNull { event ->
                val y = bgReadings.minByOrNull { abs(it.timestamp - event.timestamp) }?.value ?: return@mapNotNull null
                if (groupStart == Long.MIN_VALUE || event.timestamp - groupStart >= ANNOUNCEMENT_STACK_MS) {
                    groupStart = event.timestamp
                    stack = 0
                } else stack++
                SmbStackItem(
                    x = timestampToX(event.timestamp, minTimestamp),
                    label = event.label.trim(),
                    stackIndex = stack,
                    anchorY = y,
                    color = uamBoostColor,
                )
            }
    }
    val uamBoostMarks = remember(uamBoostLabels, uamBoostText) {
        SmbStackLabels(uamBoostLabels, uamBoostText, pinToBottom = false)
    }
    val bolusText = rememberTextMeasurer(cacheSize = LABEL_TEXT_CACHE_SIZE)
    // Carb split and delayed bolus checks that gave nothing: a small dot at the top (2026-11-18, per explicit request)
    val doseCheckDots = remember(treatments, bgReadings, minTimestamp, insulinColor) {
        // Each dot just above the BG reading at its time (2026-11-21, per explicit request)
        DoseCheckDots(
            treatments.doseCheckTimes.map { time ->
                timestampToX(time, minTimestamp) to
                    bgReadings.minByOrNull { abs(it.timestamp - time) }?.takeIf { abs(it.timestamp - time) <= DOSE_CHECK_BG_REACH_MS }?.value
            },
            insulinColor
        )
    }
    val bolusStack = remember(treatments, bgReadings, minTimestamp, lowMark, insulinColor) {
        // A protein/fat dose of 0 U has no "0" here (2026-11-21, per explicit request): its yellow arrow is enough
        val boluses = treatments.boluses.filter { it.bolusType == BolusType.NORMAL && it.label.isNotEmpty() && !(it.isFpu && it.amount <= 0.0) }
        // 2026-11-19, per explicit request: a Tier 3 UAM boost SMB also gets its value in this row, in the ISF colour of
        // that moment. Its "B", SMB arrow and bottom arrow stay as they are. Found by the "UamBst" note (tier3Values).
        val tier3 = tier3Values(treatments)
        val stack = smbStackIndex(boluses.map { it.timestamp } + tier3.map { it.timestamp })
        boluses.mapIndexed { index, bolus ->
            SmbStackItem(
                x = timestampToX(bolus.timestamp, minTimestamp),
                // The number only, without "U" (2026-10-23, per explicit request); the press-and-hold pill keeps the unit
                label = bolus.number.ifEmpty { bolus.label },
                stackIndex = stack[index],
                anchorY = lowMark,
                // Yellow for a protein/fat dose, the triangle's insulin colour for every other bolus (2026-11-18)
                color = if (bolus.isFpu) FPU_BOLUS_COLOR else insulinColor,
                tapTimestamp = bolus.timestamp,
            )
        } + tier3.mapIndexed { index, smb ->
            SmbStackItem(
                x = timestampToX(smb.timestamp, minTimestamp),
                label = smb.number.ifEmpty { formatBolusLabel(smb.amount, decimalFormatter) },
                stackIndex = stack[boluses.size + index],
                anchorY = lowMark,
                color = isfColor(bgReadings.minByOrNull { abs(it.timestamp - smb.timestamp) }?.dominantIsf),
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
    // Simple graphs: the arrows of the Tier 3 UAM boost SMBs only (the other SMB arrows are off)
    val tier3ArrowMarks = remember(treatments, bgReadings, minTimestamp, superClean) {
        SmbArrows(
            if (!superClean) emptyList()
            else tier3Values(treatments).map { smb ->
                val dot = bgReadings.minByOrNull { abs(it.timestamp - smb.timestamp) }
                SmbStackItem(
                    x = timestampToX(smb.timestamp, minTimestamp),
                    label = smb.label,
                    stackIndex = 0,
                    anchorY = dot?.value,
                    color = isfColor(dot?.dominantIsf),
                )
            }
        )
    }
    val smbBaseArrowMarks = remember(smbBaseArrows) { SmbArrows(smbBaseArrows, pinToBottom = true) }
    // Simple graphs (2026-11-21, per explicit request): the bottom SMB arrows under the green band, with their dose shafts and no
    // values, without the red steps arrows
    val smbBaseOnlyMarks = remember(smbBaseArrows) { SmbArrows(smbBaseArrows.filter { !it.mirrored }, pinToBottom = true) }
    // A zero bolus: an SMB-style arrow under the BG dot, a little larger, in the bolus colour. Its 0 label stays at
    // the bottom (bolusStack).
    val zeroBolusArrowMarks = remember(treatments, bgReadings, minTimestamp) {
        val zeroBoluses = treatments.boluses.filter { it.bolusType == BolusType.NORMAL && it.amount <= 0.0 }
        fun arrows(boluses: List<BolusGraphPoint>, scale: Float) = SmbArrows(
            boluses.map { bolus ->
                SmbStackItem(
                    x = timestampToX(bolus.timestamp, minTimestamp),
                    label = bolus.number.ifEmpty { bolus.label },
                    stackIndex = 0,
                    anchorY = bgReadings.minByOrNull { abs(it.timestamp - bolus.timestamp) }?.value,
                    // A 0 U protein/fat dose is yellow too (2026-11-18)
                    color = if (bolus.isFpu) FPU_BOLUS_COLOR else Color(0xFFFF00FF),
                )
            },
            scale = scale
        )
        // A 0 U protein/fat dose: the size of an SMB arrow (2026-11-21, per explicit request); other zero boluses stay larger
        ZeroBolusArrows(arrows(zeroBoluses.filterNot { it.isFpu }, ZERO_BOLUS_ARROW_SCALE), arrows(zeroBoluses.filter { it.isFpu }, 1f))
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
    val basalPeaks = remember(basalData, nowTimestamp, showBasalOnBgGraph) {
        // 2026-11-20, per explicit request: also every column above the profile basal
        if (showBasalOnBgGraph) basalLabelPoints(basalData.actualBasal, basalData.profileBasal, nowTimestamp) else emptyList()
    }
    val basalPeakLabel = remember(basalPeaks, minTimestamp, smbText, basalColor, basalData) {
        val basalTop = basalScaleTop(basalData)
        basalPeaks.takeIf { it.isNotEmpty() }?.let { peaks ->
            RecentPeakLabels(
                // At the column's own height on the basal scale; the text stays the real rate (2026-11-19)
                peaks.map { PeakMark(timestampToX(it.timestamp, minTimestamp), basalScaleHeight(it.value, basalTop), decimalFormatter.to2Decimal(it.value)) },
                basalColor, smbText, endAxis = true
            )
        }
    }
    // The insulin-total columns' values (2026-11-20, per explicit request): on top of each column, in its colour. They replace
    // the insulin totals row while the columns are on.
    val insulinColumnLabel = remember(insulinColumnData, minTimestamp, smbText, basalColor, acceColor, bgIsfColor, ppColor, duraColor) {
        val (columns, columnTop) = insulinColumnData
        columns.filter { it.units > 0.0 }.sortedByDescending { it.start }.takeIf { it.isNotEmpty() }?.let { shown ->
            RecentPeakLabels(
                shown.map { column ->
                    PeakMark(
                        timestampToX(column.start + (column.end - column.start) / 2, minTimestamp),
                        (column.units / columnTop).coerceAtMost(1.0),
                        insulinTotalText(column.units),
                        if (column.isf == DominantIsf.NONE) basalColor else isfColor(column.isf)
                    )
                },
                basalColor, smbText, endAxis = true, dots = false
            )
        }
    }
    val generalColors = AapsTheme.generalColors
    val lastBgMark = remember(bgReadings, minTimestamp, smbText, profileUtil, generalColors) {
        // The newest reading: the list is newest first, so lastOrNull() was the oldest, off screen on the left (2026-10-28).
        // The value is already in the user's units, so it is not converted from mg/dL again.
        bgReadings.maxByOrNull { it.timestamp }?.let { last ->
            val text = profileUtil.stringInCurrentUnitsDetect(last.value)
            val color = when (last.range) {
                BgRange.HIGH -> generalColors.bgHigh
                BgRange.LOW -> generalColors.bgLow
                else -> generalColors.bgInRange
            }
            LastBgLabelDecoration(
                timestampToX(last.timestamp, minTimestamp), last.value, text,
                color, smbText
            )
        }
    }
    val geometryReporter = rememberChartGeometryReporter(geometryHolder)
    // Upstream's clock change mark (2026-10-27 merge): a dashed line and a clock where the UTC offset of the readings changes
    val clockChanges = rememberClockChangeDecoration(minTimestamp, maxTimestamp, bgReadings, showIcon = true, masterZone = masterZone)
    // Pod and sensor changes: a dashed line and POD or SEN at the top (2026-11-01, per explicit request)
    val deviceChanges = rememberDeviceChangeDecoration(minTimestamp, treatments.therapyEvents)
    val decorations = remember(renderReporter, inRangeBox, nowLine, clockChanges, deviceChanges, smbNumbers, smbArrowMarks, smbBaseArrowMarks, zeroBolusArrowMarks, bolusNumbers, carbNumbers, uamBoostMarks, epsNumbers, tapReporter, basalPeakLabel, insulinColumnLabel, lastBgMark, inspectLine, geometryReporter, topBandLines, doseCheckDots, superClean, tier3ArrowMarks, smbBaseOnlyMarks) {
        // Simple graphs (2026-11-21, per explicit request): the range band, the now line, the clock change, the "B" marks and the
        // last reading's value, with the Tier 3 boost arrows and the bottom SMB arrows
        if (superClean) listOf(inRangeBox, nowLine, clockChanges, smbBaseOnlyMarks, tier3ArrowMarks, uamBoostMarks, tapReporter) +
            listOfNotNull(renderReporter, lastBgMark, inspectLine, geometryReporter)
        else listOf(inRangeBox, nowLine, clockChanges, deviceChanges, smbNumbers, smbArrowMarks, smbBaseArrowMarks, zeroBolusArrowMarks, bolusNumbers, carbNumbers, uamBoostMarks, epsNumbers, doseCheckDots, tapReporter) +
            listOfNotNull(renderReporter, basalPeakLabel, insulinColumnLabel, lastBgMark, inspectLine, geometryReporter, topBandTap.takeIf { topBandLines })
    }
    return decorations
}

/**
 * The point popups, the shown-hours label, the Graph 5 legend and tap dialog and the Graph 5 status lines of [BgGraphCompose],
 * moved out of it unchanged (2026-11-19, per explicit request), for the same reason as [rememberBgGraphLines].
 */
@Composable
private fun BoxScope.BgGraphOverlays(
    selectedBg: BgDataPoint?,
    selectedSmb: BolusGraphPoint?,
    selectedBandTime: Long?,
    onDismissBg: () -> Unit,
    onDismissSmb: () -> Unit,
    onDismissBandTime: () -> Unit,
    seriesRegistry: Map<String, List<BgDataPoint>>,
    visibleTimeRange: Pair<Long, Long>?,
    topBandLines: Boolean,
    topBandLines5: List<TopBandLine>,
    graphDisplay: GraphDisplay,
    autoIsfGraph: AutoIsfGraphData,
    masterZone: TimeZone?,
) {
    val dateUtil = LocalDateUtil.current
    val decimalFormatter = LocalDecimalFormatter.current
    val profileUtil = LocalProfileUtil.current
    val acceColor = AapsTheme.generalColors.acceIsf
    val bgIsfColor = AapsTheme.generalColors.bgIsf
    val ppColor = AapsTheme.generalColors.ppIsf
    val duraColor = AapsTheme.generalColors.duraIsf
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
                    Text(graphTimestamp(dateUtil, reading.timestamp, masterZone, showDate = true))
                }
                TextButton(onClick = { onDismissBg() }) { Text(stringResource(CoreUiStrings.close)) }
            }
        }
    }
    selectedSmb?.let { smb ->
        Surface(modifier = Modifier.align(Alignment.TopStart).padding(8.dp), tonalElevation = 4.dp, shadowElevation = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                Column {
                    Text(stringResource(CoreUiStrings.graph_point_line_value, stringResource(CoreUiStrings.smb_shortname), stringResource(InterfacesStrings.format_insulin_units, smb.amount)))
                    Text(graphTimestamp(dateUtil, smb.timestamp, masterZone, showDate = true, showSeconds = true))
                    if (smb.stepCut >= 0.01) {
                        Text(stringResource(CoreUiStrings.graph_smb_step_cut, stringResource(InterfacesStrings.format_insulin_units, smb.stepCut)), color = STEP_CUT_ARROW_COLOR)
                    }
                }
                TextButton(onClick = { onDismissSmb() }) { Text(stringResource(CoreUiStrings.close)) }
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
        // Tap on the top band (2026-10-29, per explicit request): each line's value and time at the loop nearest the tap,
        // in the line's colour. The real value, not the lane position. A line with no loop within 5 minutes is left out.
        selectedBandTime?.let { time ->
            val bandNames = listOf("SMBdel" to SMB_BAND_COLOR, "aISFwt" to acceColor, "ppISFwt" to ppColor, "profBasal" to PROFILE_BASAL_BAND_COLOR)
            val rows = bandNames.mapIndexedNotNull { lane, (name, color) ->
                topBandLines5[lane].points.minByOrNull { abs(it.first - time) }?.takeIf { abs(it.first - time) <= TOP_BAND_TAP_MAX_MS }?.let { (at, value) ->
                    val shown = if (lane == 3) stringResource(CoreUiStrings.pump_base_basal_rate, value) else decimalFormatter.to2Decimal(value)
                    "$name $shown  ${graphTimestamp(dateUtil, at, masterZone, showDate = true)}" to color
                }
            }
            if (rows.isNotEmpty()) AlertDialog(
                onDismissRequest = { onDismissBandTime() },
                title = { Text(stringResource(CoreUiStrings.graph_number, 5)) },
                text = { Column { rows.forEach { (text, color) -> Text(text, color = color) } } },
                confirmButton = { TextButton(onClick = { onDismissBandTime() }) { Text(stringResource(CoreUiStrings.close)) } },
            )
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
    if (LocalTopBarPillHost.current == null && graphDisplay.showTextLines && topBandLines && (autoIsfGraph.statusIsf != null || autoIsfGraph.statusSteps != null)) {
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

/**
 * The hypoprediction line and the insulin totals row under the main graph, moved out of [BgGraphCompose] unchanged
 * (2026-11-19, per explicit request), for the same reason as [rememberBgGraphLines].
 */
@Composable
private fun BgGraphBottomStrip(
    topBandLines: Boolean,
    insulinColumns: Boolean,
    visibleTimeRange: Pair<Long, Long>?,
    nowTimestamp: Long,
    rawBasalData: BasalGraphData,
    treatments: TreatmentGraphData,
    stripStyle: TextStyle,
) {
    // The insulin totals row (2026-11-19, per explicit request), just below the hypoprediction line: 12 equal slices of the shown
    // span, each the insulin delivered in it, as in the 3426 app. Settings "Insulin totals" (also List 1 and SMS) turns it on and off.
    val showInsulinTotals by LocalPreferences.current.observe(BooleanKey.ApsAutoIsfShowInsulinTotals).collectAsStateWithLifecycle()
    // Not while the insulin-total columns are on: their values sit on top of the columns (2026-11-20, per explicit request).
    val insulinTotals = remember(showInsulinTotals, insulinColumns, visibleTimeRange, rawBasalData, treatments, nowTimestamp / 60_000L) {
        val range = visibleTimeRange
        if (!showInsulinTotals || insulinColumns || range == null) emptyList()
        else insulinIntervalTotals(range.first, range.second, nowTimestamp, rawBasalData.actualBasal, treatments.boluses, treatments.extendedBoluses)
    }
    // The IOB graph below is pulled 8dp up under this strip, hence the extra bottom padding. The hypoprediction line moved to
    // G1's − + row (2026-11-20, per explicit request), see hypoLineText.
    if (!topBandLines && insulinTotals.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp)) {
            if (insulinTotals.isNotEmpty()) {
                // Lined up with the plot area (past the BG axis, the same start as the shown-hours label), one slice per cell
                Row(modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 2.dp)) {
                    insulinTotals.forEach { units ->
                        Text(
                            text = insulinTotalText(units),
                            color = Color.White,
                            style = stripStyle,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The hypoprediction line, at the left of G1's − + row (2026-11-20, per explicit request; was its own line under the main
 * graph). While the insulin-total columns are on, it also says so ("hypopred=6.0 insulin tot cols"). Null when there is nothing to show.
 */
internal fun hypoLineText(hypoPrediction: Double?, insulinColumns: Boolean): String? = when {
    insulinColumns          -> "hypopred=${hypoPrediction?.let { oneDecimalText(it) } ?: "--"} insulin tot cols"
    hypoPrediction != null  -> "hypoprediction= ${oneDecimalText(hypoPrediction)}"
    else                    -> null
}

internal fun oneDecimalText(value: Double): String {
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
 * A line that draws no line at all, only a marker in [color] at every point. Used for the
 * treatment markers on the BG graph, one line per size.
 *
 * The point box is [size] grown by [MARKER_TIP_GAP] on both sides, and [shapeOf] gets the gap as
 * a share of that box, so the triangle keeps the proportions of [size] and its tip stops
 * [MARKER_TIP_GAP] away from the reading at the box centre.
 */
private fun createMarkerLine(color: Color, shapeOf: (tipInset: Float) -> Shape, size: Dp): LineCartesianLayer.Line {
    val boxSize = size + MARKER_TIP_GAP * 2
    val shape = shapeOf(MARKER_TIP_GAP / boxSize)
    return LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
        areaFill = null,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(component = ShapeComponent(fill = Fill(color), shape = shape), size = boxSize)
        )
    )
}

/**
 * The in-range belt between [low] and [high] on the BG axis, drawn under the layers. Vico's
 * `HorizontalBox` draws over them, and its translucent green then tinted every reading and marker
 * inside the belt, so a bolus looked one colour in range and another out of it.
 */
private data class InRangeBand(private val low: Double, private val high: Double, private val color: Color) : Decoration {

    override fun drawUnderLayers(context: CartesianDrawingContext) {
        with(context) {
            val yRange = ranges.getYRange(Axis.Position.Vertical.Start)
            if (yRange.length == 0.0) return
            fun canvasY(value: Double): Float =
                (layerBounds.bottom - ((value - yRange.minY) / yRange.length).toFloat() * layerBounds.height)
                    .coerceIn(layerBounds.top, layerBounds.bottom)
            val top = canvasY(high)
            val bottom = canvasY(low)
            if (bottom <= top) return
            with(mutableDrawScope) {
                drawRect(
                    color = this@InRangeBand.color,
                    topLeft = Offset(layerBounds.left, top),
                    size = Size(layerBounds.width, bottom - top)
                )
            }
        }
    }
}
