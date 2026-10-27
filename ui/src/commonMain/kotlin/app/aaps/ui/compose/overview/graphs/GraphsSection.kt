package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.SecondaryGraph
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.ui.UiStrings
import app.aaps.core.ui.compose.blockSystemEdgeGesture
import app.aaps.core.ui.compose.isLandscape
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.rowAction
import app.aaps.core.ui.compose.stringResource
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlin.math.abs

/**
 * Overview graphs section using Vico charts.
 *
 * Pattern: Observe Primary + Sync to Secondary
 * - Each graph has its OWN VicoScrollState and VicoZoomState
 * - A drag on any graph moves every graph, including the main one.
 * - A pinch on any graph sets the same width on every graph.
 *
 * Secondary graphs are config-driven via [GraphConfig.secondaryGraphs].
 * Scroll/Zoom states are pre-allocated (up to [GraphConfig.MAX_SECONDARY_GRAPHS])
 * to avoid dynamic composable state issues with Vico's remember-based states.
 */
/** Fixed graph layout used in simple mode: BG (no overlays), IOB+BAS (no overlays), COB */
private val SIMPLE_MODE_CONFIG = GraphConfig(
    bgOverlays = emptyList(),
    iobOverlays = emptyList(),
    secondaryGraphs = listOf(SecondaryGraph(listOf(SeriesType.COB)))
)

/**
 * How many recent readings the spoken description of the BG graph lists.
 *
 * Five is about twenty-five minutes, which is enough to hear where the glucose is heading without
 * turning the description into a list nobody will sit through.
 */
private const val RECENT_VALUES_SPOKEN = 5

/** Moves the pill up over the treatment belt (48 dp, of which 16 dp overlap the BG graph). */
private val INSPECT_PILL_OFFSET = (-32).dp

/** Size of the edit button (pencil) on a graph. The icon in it is 16 dp. */
private val GRAPH_EDIT_BUTTON_SIZE = 28.dp

/** Which way a predicted curve is travelling, which decides the sentence spoken for it. */
internal enum class PredictionDirection { UP, DOWN, FLAT }

/**
 * The point of a predicted curve worth saying out loud, and which way it lies from [current].
 *
 * The furthest excursion from the current glucose, not the last point: a curve that dips to 3.2
 * before settling at 6.0 matters for the dip, and "heading down to" is how a user describes it.
 * A curve that only rises therefore reports its peak, one that only falls reports its trough, and
 * one that goes nowhere reports the value it holds.
 *
 * [values] must already be in the user's units. Returns null for an empty curve.
 */
internal fun predictionExtreme(values: List<Double>, current: Double): Pair<Double, PredictionDirection>? {
    if (values.isEmpty()) return null
    val highest = values.max()
    val lowest = values.min()
    val up = highest - current
    val down = current - lowest
    return when {
        up > down -> highest to PredictionDirection.UP
        down > up -> lowest to PredictionDirection.DOWN
        else      -> current to PredictionDirection.FLAT
    }
}

/**
 * The predicted curves in the order they are spoken, with the name each is spoken by.
 *
 * Carbs first because a meal is what a user is most often checking on, then insulin, then the two
 * that only appear in some setups. Absorbed COB exists on AMA only and is simply absent otherwise.
 */
private val PREDICTION_SPOKEN_ORDER = listOf(
    BgType.COB_PREDICTION to UiStrings.a11y_prediction_name_cob,
    BgType.IOB_PREDICTION to UiStrings.a11y_prediction_name_iob,
    BgType.A_COB_PREDICTION to UiStrings.a11y_prediction_name_acob,
    BgType.UAM_PREDICTION to UiStrings.a11y_prediction_name_uam,
    BgType.ZT_PREDICTION to UiStrings.a11y_prediction_name_zt
)

/** Series types available as BG graph overlays */
private val BG_OVERLAY_SERIES = listOf(SeriesType.ACTIVITY, SeriesType.PREDICTIONS, SeriesType.RAW_BG, SeriesType.UKF_BG)

/** Series types available for user-configurable secondary graphs (IOB + UI-only overlays excluded) */
private val CONFIGURABLE_SERIES = SeriesType.entries.filter {
    it != SeriesType.IOB && it != SeriesType.PREDICTIONS && it != SeriesType.RAW_BG && it != SeriesType.UKF_BG
}

@OptIn(FlowPreview::class)
@Composable
fun GraphsSection(
    graphViewModel: GraphViewModel,
    isSimpleMode: Boolean,
    modifier: Modifier = Modifier,
    fitWholeWindow: Boolean = false,
    /**
     * Insulin and carbs on board, already formatted, for the spoken description of the BG graph.
     * The overview passes them; the history browser must NOT, because these are CURRENT values and
     * announcing them beside a past day would state something untrue about insulin.
     */
    iobText: String? = null,
    cobText: String? = null,
    /** Height of the bars drawn over the end of this section (the overview's preview mode), added to the space at its end. */
    overlaidBarsHeight: Dp = 0.dp
) {
    val dateUtil = LocalDateUtil.current
    val savedGraphConfig by graphViewModel.graphConfigFlow.collectAsStateWithLifecycle()
    // In simple mode: fixed layout (BG, IOB+BAS, COB — no overlays, no editing)
    val graphConfig = if (isSimpleMode) SIMPLE_MODE_CONFIG else savedGraphConfig

    // Zoom.x(...) allocates a fresh (non-equal, unmemoized) lambda instance on every call.
    // rememberVicoZoomState's underlying rememberSaveable is keyed on initialZoom/minZoom/maxZoom
    // by reference, so passing a freshly-allocated Zoom on every recomposition of this composable
    // tears down and recreates the VicoZoomState — resetting the zoom level back to initialZoom.
    // Memoize these once so all zoom states below stay stable across recompositions.
    val defaultZoom = remember { Zoom.x(DEFAULT_GRAPH_ZOOM_MINUTES) }
    val bgMinZoom = remember { Zoom.x(Constants.GRAPH_TIME_RANGE_HOURS * 60.0) }
    val bgMaxZoom = remember { Zoom.x(MIN_GRAPH_ZOOM_MINUTES) }

    // Where the graphs start. The overview is a live view, so the last six hours anchored at the
    // right edge is what the user wants. The history browser shows one chosen day, and its window
    // ends at that day's midnight - the same six hours would be the late evening only, and for
    // today it would be hours that have not happened yet, which reads as an empty graph. There it
    // starts fully zoomed out, which is exactly the day, because the view model spans the axis over
    // the whole window in that mode. Both pick one of the zoom objects above rather than allocating
    // a new one - see the note above on why a fresh Zoom instance tears the state down.
    val startZoom = if (fitWholeWindow) bgMinZoom else defaultZoom

    // BG graph - primary interactive.
    // Wrapped in key(bgViewportResetTrigger) so bumping the trigger tears down and recreates both
    // states from scratch, snapping them back to their initial values (Scroll.Absolute.End /
    // startZoom). This is deliberate: VicoZoomState.zoom(Zoom) is pinch-gesture-oriented
    // (it applies a ratio anchored on the current canvas center via an async pendingScroll flow)
    // and produced a wrong end state when used to "reset to a fixed default" — recreating the
    // state objects is simpler and matches the exact positioning Vico itself already uses on first
    // composition. Only reset on real inactivity (rare, never mid-gesture), so this doesn't
    // reintroduce the "churn during an active gesture" issues found elsewhere in this file.
    // IMPORTANT: every effect below that reads bgScrollState/bgZoomState.value MUST be keyed on
    // them (not just Unit) — otherwise it keeps a stale reference to the abandoned pre-reset
    // objects forever, comparing live secondary-graph state against a frozen stale value and
    // firing wrong corrections (this caused the secondary graphs to visibly "dance"/flash on the
    // first attempt at this).
    var bgViewportResetTrigger by remember { mutableIntStateOf(0) }
    // Vico zooms on every mouse wheel event by itself, about the pointer, even when the event was already
    // consumed. On desktop that added a second zoom to each Ctrl + wheel step (too much per notch) and moved
    // the time at the right edge. Desktop zoom goes only through zoomStep below; Vico's direct zoom calls
    // still work with this off. Phones and tablets keep Vico's pinch zoom.
    val vicoZoomEnabled = LocalConfig.current.platform != AppPlatform.Desktop
    val (bgScrollState, bgZoomState) = key(bgViewportResetTrigger) {
        rememberSafeScrollState(
            scrollEnabled = true,
            initialScroll = Scroll.Absolute.End
        ) to rememberSafeZoomState(
            zoomEnabled = vicoZoomEnabled,
            initialZoom = startZoom,
            minZoom = bgMinZoom,
            maxZoom = bgMaxZoom
        )
    }

    // Pre-allocate secondary graph scroll/zoom states (up to MAX_SECONDARY_GRAPHS)
    // These are always created to keep Compose's remember slots stable.
    // A drag here moves the other lower graphs. A pinch uses the same width limits as the main graph,
    // so the copied width is not clamped back.
    val sec0scroll = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val sec0zoom = rememberSafeZoomState(zoomEnabled = vicoZoomEnabled, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
    val sec1scroll = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val sec1zoom = rememberSafeZoomState(zoomEnabled = vicoZoomEnabled, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
    val sec2scroll = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val sec2zoom = rememberSafeZoomState(zoomEnabled = vicoZoomEnabled, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
    val sec3scroll = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val sec3zoom = rememberSafeZoomState(zoomEnabled = vicoZoomEnabled, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
    val sec4scroll = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val sec4zoom = rememberSafeZoomState(zoomEnabled = vicoZoomEnabled, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)

    // Collect nowTimestamp ONCE so all graphs use the same value (avoids separate recompositions every 30s)
    val nowTimestamp by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()

    // Collect time range ONCE so all graphs use the exact same values in the same frame.
    // Without this, each graph independently collects derivedTimeRange via
    // collectAsStateWithLifecycle(), which can recompose in different frames —
    // causing minTimestamp divergence and scroll misalignment (pixel position
    // maps to different time when x-axis ranges differ).
    val derivedTimeRange by graphViewModel.derivedTimeRange.collectAsStateWithLifecycle()

    // BG's own visible window, sourced from the fixed IOB graph's already-computed visible range
    // (its scroll/zoom are synced to BG's, see the sync LaunchedEffect below) — not from a
    // decoration on BG's own chart, since that was tried and found to break BG's pinch-zoom
    // gesture handling.
    var iobVisibleRange by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val visibleMinutes = iobVisibleRange?.let { (start, end) -> end - start }
    val isDesktop = LocalConfig.current.platform == AppPlatform.Desktop
    val windowSize = LocalWindowInfo.current.containerSize
    val windowWidthDp = with(LocalDensity.current) { windowSize.width.toDp().value.toDouble() }
    // Phones: the SMBs in the visible window count too (see useCleanGraphForPhone)
    val treatments by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val smbsInView = if (isDesktop) 0 else derivedTimeRange?.first?.let { minTs ->
        iobVisibleRange?.let { (startX, endX) ->
            val from = minTs + (startX * 60_000).toLong()
            val to = minTs + (endX * 60_000).toLong()
            treatments.boluses.count { it.bolusType == BolusType.SMB && it.isValid && it.timestamp in from..to }
        }
    } ?: 0
    val cleanGraphForWindow =
        if (isDesktop) useCleanGraphForDesktopWindow(visibleMinutes, DEFAULT_GRAPH_ZOOM_MINUTES, windowWidthDp)
        else useCleanGraphForPhone(visibleMinutes, smbsInView, landscape = windowSize.width > windowSize.height)
    // Desktop: a long press on the IOB or basal icon ends the clean graph until the zoom changes.
    val cleanGraphOverride by graphViewModel.cleanGraphOverride.collectAsStateWithLifecycle()
    SideEffect { graphViewModel.cleanGraphCanBeOverridden = isDesktop && cleanGraphForWindow }
    var overrideStartMinutes by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(cleanGraphOverride) { overrideStartMinutes = if (cleanGraphOverride) visibleMinutes else null }
    LaunchedEffect(visibleMinutes) {
        val start = overrideStartMinutes
        // Scrolling keeps the width, so only a real zoom (more than 1 minute) ends the override
        if (start != null && visibleMinutes != null && abs(visibleMinutes - start) > 1.0) graphViewModel.clearCleanGraphOverride()
    }
    val automaticCleanGraph = cleanGraphForWindow && !cleanGraphOverride
    // The setting decides only whether the automatic clean graph also hides the text lines (2026-10-21, per explicit
    // request). The SMB labels and the arrows are hidden in any case.
    val cleanGraphHidesTextLines by LocalPreferences.current.observe(BooleanKey.ApsAutoIsfAutoCleanGraph).collectAsStateWithLifecycle()

    // Settle further before feeding into BG specifically: unlike the secondary graphs (which are
    // non-interactive and unaffected by frequent updates), BG has live pinch-zoom. Updating its
    // axis range on every ~80ms tick during an active gesture kept changing the Y-axis label
    // width/layerDimensions mid-gesture and broke pinch-zoom. Only update once the window has
    // been stable for a bit, i.e. after the gesture ends.
    var iobVisibleRangeSettled by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { iobVisibleRange }
            .debounce(400)
            .collect { iobVisibleRangeSettled = it }
    }

    val bgVisibleTimeRange = derivedTimeRange?.first?.let { minTs ->
        iobVisibleRangeSettled?.let { (minXv, maxXv) ->
            (minTs + (minXv * 60_000).toLong()) to (minTs + (maxXv * 60_000).toLong())
        }
    }

    // Treatment belt graph - non-interactive, synced from BG
    val beltScrollState = rememberSafeScrollState(
        scrollEnabled = false,
        initialScroll = Scroll.Absolute.End
    )
    val beltZoomState = rememberSafeZoomState(
        zoomEnabled = false,
        initialZoom = startZoom,
        minZoom = bgMinZoom,
        maxZoom = bgMaxZoom
    )

    // Fixed IOB graph. A drag here moves the other lower graphs, not the main graph.
    val iobScrollState = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val iobZoomState = rememberSafeZoomState(zoomEnabled = vicoZoomEnabled, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)

    // Graph 5 is a second copy of the main glucose graph. A drag here moves the other lower graphs.
    val g5ScrollState = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val g5ZoomState = rememberSafeZoomState(zoomEnabled = vicoZoomEnabled, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
    val showGraph5 by graphViewModel.showGraph5.collectAsStateWithLifecycle()
    val showGraph5Now = rememberUpdatedState(showGraph5)

    // Active graph count — rememberUpdatedState so coroutines always read the latest value
    // without writing to state during composition. Unattached states are no-ops for
    // .zoom()/.scroll(), but we skip them to avoid redundant calls.
    val activeCount by rememberUpdatedState(
        graphConfig.secondaryGraphs.size.coerceAtMost(GraphConfig.MAX_SECONDARY_GRAPHS)
    )

    // All secondary scroll/zoom states in arrays for indexed access (keyed to rebuild if state identity changes)
    val secScrollStates = remember(sec0scroll, sec1scroll, sec2scroll, sec3scroll, sec4scroll) {
        arrayOf(sec0scroll, sec1scroll, sec2scroll, sec3scroll, sec4scroll)
    }
    val secZoomStates = remember(sec0zoom, sec1zoom, sec2zoom, sec3zoom, sec4zoom) {
        arrayOf(sec0zoom, sec1zoom, sec2zoom, sec3zoom, sec4zoom)
    }

    // 2026-10-24, per explicit request: each graph's layout, so scroll and zoom are copied as times (see ChartGeometry). The BG
    // graph's big markers give it other pixel steps than the lower graphs, so the same pixels or zoom factor meant another time.
    // The sync below works in main (BG) graph units: a lower graph's values are turned into them when read, and back into
    // that graph's own units when written. A graph without a layout yet (or the belt, which is not drawn) is left as it is.
    val bgGeometry = remember { ChartGeometryHolder() }
    val iobGeometry = remember { ChartGeometryHolder() }
    val g5Geometry = remember { ChartGeometryHolder() }
    val secGeometry = remember { Array(secScrollStates.size) { ChartGeometryHolder() } }
    fun geometryOf(scroll: VicoScrollState): ChartGeometry? = when {
        scroll === bgScrollState  -> bgGeometry.value
        scroll === iobScrollState -> iobGeometry.value
        scroll === g5ScrollState  -> g5Geometry.value
        else                      -> secScrollStates.indexOfFirst { it === scroll }.takeIf { it >= 0 }?.let { secGeometry[it].value }
    }
    fun geometryOfZoom(zoom: VicoZoomState): ChartGeometry? = when {
        zoom === bgZoomState  -> bgGeometry.value
        zoom === iobZoomState -> iobGeometry.value
        zoom === g5ZoomState  -> g5Geometry.value
        else                  -> secZoomStates.indexOfFirst { it === zoom }.takeIf { it >= 0 }?.let { secGeometry[it].value }
    }
    fun zoomFor(scroll: VicoScrollState): Float = when {
        scroll === bgScrollState  -> bgZoomState.value
        scroll === iobScrollState -> iobZoomState.value
        scroll === g5ScrollState  -> g5ZoomState.value
        else                      -> secScrollStates.indexOfFirst { it === scroll }.takeIf { it >= 0 }?.let { secZoomStates[it].value } ?: 0f
    }
    /** A main graph scroll as the scroll that shows the same time on [target]. */
    fun scrollForGraph(mainScroll: Float, target: VicoScrollState): Float =
        convertScroll(mainScroll, bgGeometry.value, bgZoomState.value, geometryOf(target), zoomFor(target))
    /** [scroll] of [source] in main graph units. */
    fun scrollInMain(scroll: Float, source: VicoScrollState): Float =
        convertScroll(scroll, geometryOf(source), zoomFor(source), bgGeometry.value, bgZoomState.value)
    fun zoomForGraph(mainZoom: Float, target: VicoZoomState): Float = convertZoom(mainZoom, bgGeometry.value, geometryOfZoom(target))
    fun zoomInMain(zoom: Float, source: VicoZoomState): Float = convertZoom(zoom, geometryOfZoom(source), bgGeometry.value)

    // Set just before this code moves the main graph, so that move is not counted as a finger drag.
    val skipInteractionUntilMs = remember { longArrayOf(0L) }
    // Only horizontal drags and pinches permit a lower graph to drive the shared timeline.
    // A page scroll must not turn a lower graph's layout change into time navigation.
    val lastTouchMs = remember { longArrayOf(0L) }
    val pageScrolling = remember { booleanArrayOf(false) }
    // When a move was last copied to the other graphs. A fling keeps going after the finger lifts, on iOS for more
    // than GRAPH_TOUCH_WINDOW_MS. It then counted as "moved by itself" and the other graphs were pulled back while the
    // flung one kept going (2026-10-20). Each copy keeps the move counted as the user's for GRAPH_FLING_CHAIN_MS more.
    val lastShareMs = remember { longArrayOf(0L) }
    fun recentTouch(): Boolean {
        if (pageScrolling[0]) return false
        val now = dateUtil.now()
        return now - lastTouchMs[0] < GRAPH_TOUCH_WINDOW_MS ||
            (lastTouchMs[0] != 0L && now - lastShareMs[0] < GRAPH_FLING_CHAIN_MS)
    }
    // The live overview stays on the current time until the user drags the main graph.
    var followNow by remember { mutableStateOf(!fitWholeWindow) }
    var placedOnNow by remember(bgScrollState) { mutableStateOf(false) }
    val latestTimeRange = rememberUpdatedState(derivedTimeRange)
    val latestNow = rememberUpdatedState(nowTimestamp)

    // A drag on any graph is copied to every graph, including the main one. Until that lands,
    // do not treat the old position as a new drag.
    val pendingScroll = remember { floatArrayOf(Float.NaN) }
    // The scroll and zoom this code last wrote. A graph moving toward that value is catching up.
    // A graph moving away from it is the finger.
    val appliedScroll = remember { floatArrayOf(Float.NaN) }
    val appliedZoom = remember { floatArrayOf(Float.NaN) }
    // The chart under the finger. scroll() on that chart waits until the finger lifts, and that
    // wait blocks the copy to every chart listed after it. Leave this one alone.
    val drivingScroll = remember { arrayOfNulls<VicoScrollState>(1) }

    // Observe BG graph scroll/zoom and sync to belt + active secondary graphs
    // Keys include ALL state objects — identical pattern to the original working sync
    LaunchedEffect(
        bgScrollState, bgZoomState, beltScrollState, beltZoomState,
        iobScrollState, iobZoomState,
        sec0scroll, sec0zoom, sec1scroll, sec1zoom,
        sec2scroll, sec2zoom, sec3scroll, sec3zoom,
        sec4scroll, sec4zoom
    ) {
        var lastZoom = Float.NaN
        var lastSentScroll = Float.NaN
        // Do not report a user interaction from here: the scroll/zoom values also change without the user
        // (viewport reset on a new BG, the prediction nudge, Vico's initial scroll to the end). Real gestures
        // are reported by onScrollOrZoomGesture on the BG chart.
        snapshotFlow { bgScrollState.value to bgZoomState.value }
            .conflate()
            .collect { (scroll, zoom) ->
                val skip = drivingScroll[0]
                val count = activeCount
                // NaN is not equal to NaN, so an unready zoom would be copied on every tick.
                // A zoom that is not a real number is left alone. Multiplying it keeps the NaN,
                // and the chart crashes.
                if (zoom.isFinite() && zoom > 0f && zoom != lastZoom) {
                    lastZoom = zoom
                    appliedZoom[0] = zoom
                    if (beltZoomState.value.isFinite()) beltZoomState.copyFactorIfDifferent(zoomForGraph(zoom, beltZoomState))
                    if (iobZoomState.value.isFinite()) iobZoomState.copyFactorIfDifferent(zoomForGraph(zoom, iobZoomState))
                    if (showGraph5Now.value && g5ZoomState.value.isFinite()) g5ZoomState.copyFactorIfDifferent(zoomForGraph(zoom, g5ZoomState))
                    for (i in 0 until count) {
                        if (secZoomStates[i].value.isFinite()) secZoomStates[i].copyFactorIfDifferent(zoomForGraph(zoom, secZoomStates[i]))
                    }
                }
                // Before the chart has a width, the scroll value is 0. Copying that 0 replaces
                // the initial "recent time" position on the other graphs.
                // A zoom-only tick must not copy the scroll.
                if (scroll.isFinite() && bgScrollState.maxValue > 1f &&
                    (lastSentScroll.isNaN() || abs(scroll - lastSentScroll) > 1f)
                ) {
                    lastSentScroll = scroll
                    pendingScroll[0] = Float.NaN
                    appliedScroll[0] = scroll
                    beltScrollState.copyPixelsIfDifferent(scrollForGraph(scroll, beltScrollState), skip)
                    iobScrollState.copyPixelsIfDifferent(scrollForGraph(scroll, iobScrollState), skip)
                    if (showGraph5Now.value) g5ScrollState.copyPixelsIfDifferent(scrollForGraph(scroll, g5ScrollState), skip)
                    for (i in 0 until count) secScrollStates[i].copyPixelsIfDifferent(scrollForGraph(scroll, secScrollStates[i]), skip)
                }
            }
    }

    // Startup guard (2026-10-07): a cold start sometimes left every graph on the OLDEST hours, with the current time off
    // the right edge, and neither the placement below nor the next reading moved it back. For the first
    // STARTUP_GUARD_MS, while the user has not touched a graph (followNow) and the visible window ends well before
    // now, put the current time on the right edge again; the sync above copies it to the other graphs. The println
    // lines (tag "GraphStart", visible in logcat) record what the placement and sync steps see, to find the real cause.
    LaunchedEffect(bgScrollState) {
        if (fitWholeWindow) return@LaunchedEffect
        val startedAt = dateUtil.now()
        while (dateUtil.now() - startedAt < STARTUP_GUARD_MS) {
            // Fast at first (the lower graphs come up on stale positions in the first seconds), then relaxed.
            delay(if (dateUtil.now() - startedAt < 30_000L) 1_000L else STARTUP_GUARD_INTERVAL_MS)
            val range = latestTimeRange.value
            val visible = iobVisibleRange
            val nowMs = dateUtil.now()
            val sinceInteraction = nowMs - graphViewModel.lastInteractionMs
            println(
                "GraphStart guard: followNow=$followNow placedOnNow=$placedOnNow range=${range?.first} " +
                    "visible=$visible bgScroll=${bgScrollState.value} bgMax=${bgScrollState.maxValue} sinceInteractionMs=$sinceInteraction"
            )
            // Safety net (2026-10-08): followNow can only be cleared by a finger. If nothing has ever touched the graphs since
            // start, it was cleared by a misread startup move, so put it back.
            if (!followNow && lastTouchMs[0] == 0L) {
                graphViewModel.graphSyncLog("guard", "followNow was false with no touch since start - following the current time again")
                followNow = true
            }
            if (!followNow || range == null || sinceInteraction < INTERACTION_GRACE_MS) continue
            // The lower graphs (IOB, the secondary graphs, graph 5, the belt) start from their own first-layout positions,
            // such as 2043 px = about 14 hours back, and nothing copies the main graph's place onto them until the main
            // graph moves. While nothing has been touched, put every one of them on the main graph's place.
            val mainScroll = bgScrollState.value
            if (placedOnNow && mainScroll.isFinite() && bgScrollState.maxValue > 24f) {
                beltScrollState.copyPixelsIfDifferent(scrollForGraph(mainScroll, beltScrollState), null)
                iobScrollState.copyPixelsIfDifferent(scrollForGraph(mainScroll, iobScrollState), null)
                if (showGraph5Now.value) g5ScrollState.copyPixelsIfDifferent(scrollForGraph(mainScroll, g5ScrollState), null)
                for (i in 0 until activeCount) secScrollStates[i].copyPixelsIfDifferent(scrollForGraph(mainScroll, secScrollStates[i]), null)
            }
            if (visible == null) continue
            val nowX = timestampToX(nowMs, range.first)
            if (visible.second < nowX - STARTUP_GUARD_GAP_MINUTES) {
                graphViewModel.graphSyncLog("guard", "window ends at ${visible.second} min, now is at $nowX min - putting now on the right edge")
                skipInteractionUntilMs[0] = nowMs + 1000L
                bgScrollState.scroll(scrollSoNowIsAtEnd(range.first, nowMs))
            }
        }
    }

    // Auto-scroll when new BG value arrives
    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    var lastBgTimestamp by remember { mutableLongStateOf(0L) }

    LaunchedEffect(bgInfoState.bgInfo?.timestamp) {
        val newTimestamp = bgInfoState.bgInfo?.timestamp ?: return@LaunchedEffect
        if (lastBgTimestamp != 0L && newTimestamp > lastBgTimestamp) {
            // Skip auto-reset while user is interacting with the graph
            val sinceInteraction = dateUtil.now() - graphViewModel.lastInteractionMs
            if (sinceInteraction < INTERACTION_GRACE_MS) {
                lastBgTimestamp = newTimestamp
                return@LaunchedEffect
            }
            // Keep the hours the user is already looking at. Slide the window so the current
            // time is the right edge. Recreating the zoom state here used to snap back to 6 hours.
            val timeRange = derivedTimeRange
            graphViewModel.graphSyncLog("newReading", "timeRangeFirst=${timeRange?.first} bgScroll=${bgScrollState.value} bgMax=${bgScrollState.maxValue}")
            if (fitWholeWindow || timeRange == null) {
                bgScrollState.scroll(Scroll.Absolute.End)
            } else {
                followNow = true
                skipInteractionUntilMs[0] = dateUtil.now() + 1000L
                bgScrollState.scroll(scrollSoNowIsAtEnd(timeRange.first, dateUtil.now()))
            }
        }
        lastBgTimestamp = newTimestamp
    }

    // Opening puts the current time on the right edge. A drag on any graph moves every graph,
    // including the main one. A pinch on any graph sets the same width on every graph.
    // A rebuild that jumps a graph back to the start is not a drag.
    // Keyed on bgScrollState/bgZoomState (not Unit) — MUST restart when they're recreated by a
    // reset, otherwise this keeps comparing secondary graphs against a stale, abandoned pre-reset
    // reference forever and fires wrong corrections.
    var lastMainScroll by remember { mutableFloatStateOf(Float.NaN) }
    var lastMainMax by remember { mutableFloatStateOf(Float.NaN) }
    var lastMainZoom by remember { mutableFloatStateOf(Float.NaN) }
    val lastFollowerScroll = remember { FloatArray(8) { Float.NaN } }
    val lastFollowerZoom = remember { FloatArray(8) { Float.NaN } }
    // 2026-10-20: why the main graph was set up again. A whole new graph area ("sectionShown") is a new screen; a
    // "stateNew" alone means the main graph's position and zoom were made again inside the same screen.
    DisposableEffect(Unit) {
        graphViewModel.graphSyncLog("sectionShown", "graph area shown")
        onDispose { graphViewModel.graphSyncLog("sectionGone", "graph area removed") }
    }
    LaunchedEffect(bgScrollState, bgZoomState) {
        graphViewModel.graphSyncLog(
            "stateNew",
            "main graph state made: scroll=${bgScrollState.value} max=${bgScrollState.maxValue} zoom=${bgZoomState.value} followNow=$followNow"
        )
    }

    LaunchedEffect(bgScrollState, bgZoomState) {
        snapshotFlow {
            val count = activeCount
            Triple(
                latestTimeRange.value,
                latestNow.value,
                // In main graph units (2026-10-24), so every comparison below is between equal units
                buildList {
                    add(scrollInMain(iobScrollState.value, iobScrollState) to zoomInMain(iobZoomState.value, iobZoomState))
                    for (i in 0 until count) {
                        add(scrollInMain(secScrollStates[i].value, secScrollStates[i]) to zoomInMain(secZoomStates[i].value, secZoomStates[i]))
                    }
                    if (showGraph5Now.value) add(scrollInMain(g5ScrollState.value, g5ScrollState) to zoomInMain(g5ZoomState.value, g5ZoomState))
                }
            )
        }
            .conflate()
            .collect { (range, now, states) ->
                val bgScroll = bgScrollState.value
                val bgMax = bgScrollState.maxValue
                // The chart reports NaN until it has a size. Copying that crashes the point draw.
                if (!bgScroll.isFinite() || !bgMax.isFinite()) return@collect
                val bgZoom = bgZoomState.value
                val zoomOk = bgZoom.isFinite() && bgZoom > 0f
                // The first layout often stores the start as 0, or at the axis end, which can be
                // the next hour. Once there is a real width, put the current time on the right edge.
                if (!placedOnNow && bgMax > 24f && (fitWholeWindow || range != null)) {
                    graphViewModel.graphSyncLog("place", "followNow=$followNow bgScroll=$bgScroll bgMax=$bgMax rangeFirst=${range?.first} now=$now")
                    lastMainMax = bgMax
                    if (!fitWholeWindow && followNow && range != null) {
                        skipInteractionUntilMs[0] = dateUtil.now() + 1000L
                        bgScrollState.scroll(scrollSoNowIsAtEnd(range.first, now))
                        // The first value is often still 0, the oldest hour. Keep asking until
                        // the current time is actually on the right, then a later drag can move it.
                        if (bgScroll > 24f) placedOnNow = true
                    } else {
                        if (bgScroll < 1f) bgScrollState.scroll(Scroll.Absolute.End)
                        placedOnNow = true
                    }
                    lastMainScroll = bgScrollState.value
                    for (i in lastFollowerScroll.indices) lastFollowerScroll[i] = Float.NaN
                    for (i in lastFollowerZoom.indices) lastFollowerZoom[i] = Float.NaN
                    pendingScroll[0] = Float.NaN
                    return@collect
                }
                // A model rebuild can clamp the scroll to the oldest time. That is not a finger drag.
                val wasAtEnd = !lastMainScroll.isNaN() && !lastMainMax.isNaN() && abs(lastMainScroll - lastMainMax) < 48f
                val rebuiltToStart = bgMax > 24f && bgScroll < 1f && lastMainScroll > 24f &&
                    !lastMainMax.isNaN() && abs(bgMax - lastMainMax) > 24f
                val axisGrew = !lastMainMax.isNaN() && bgMax - lastMainMax > 24f
                lastMainMax = bgMax
                if (rebuiltToStart) {
                    graphViewModel.graphSyncLog("rebuiltToStart", "followNow=$followNow bgScroll=$bgScroll bgMax=$bgMax lastMainScroll=$lastMainScroll")
                    if (followNow && !fitWholeWindow && range != null) {
                        skipInteractionUntilMs[0] = dateUtil.now() + 1000L
                        bgScrollState.scroll(scrollSoNowIsAtEnd(range.first, now))
                    } else if (wasAtEnd) {
                        bgScrollState.scroll(Scroll.Absolute.End)
                    } else {
                        bgScrollState.scroll(Scroll.Absolute.pixels(lastMainScroll.coerceAtMost(bgMax)))
                    }
                    lastMainScroll = bgScrollState.value
                    for (i in lastFollowerScroll.indices) lastFollowerScroll[i] = Float.NaN
                    for (i in lastFollowerZoom.indices) lastFollowerZoom[i] = Float.NaN
                    pendingScroll[0] = Float.NaN
                    return@collect
                }
                // 2026-10-22, per explicit request: new data made the axis longer. A full sync or a new install loads the
                // data in pieces, oldest first, and the scroll is kept in pixels, so the window stayed on the end of the data
                // loaded at that moment (02:50 last night instead of now). The startup guard stops after 3 minutes, before a
                // full sync ends. While following the current time, put it on the right edge again.
                if (axisGrew && followNow && !fitWholeWindow && range != null) {
                    graphViewModel.graphSyncLog("axisGrew", "bgScroll=$bgScroll bgMax=$bgMax rangeFirst=${range.first} now=$now")
                    skipInteractionUntilMs[0] = dateUtil.now() + 1000L
                    bgScrollState.scroll(scrollSoNowIsAtEnd(range.first, now))
                    lastMainScroll = bgScrollState.value
                    for (i in lastFollowerScroll.indices) lastFollowerScroll[i] = Float.NaN
                    for (i in lastFollowerZoom.indices) lastFollowerZoom[i] = Float.NaN
                    pendingScroll[0] = Float.NaN
                    return@collect
                }
                if (lastMainScroll.isNaN()) lastMainScroll = bgScroll
                if (bgMax <= 1f) return@collect
                // The main graph moved. Its own effect already copies that place to the other graphs.
                // A lower graph that is still on the old place is not a finger, so it must not pull the main graph back.
                if (abs(bgScroll - lastMainScroll) > 1f) {
                    val scrollBefore = lastMainScroll
                    lastMainScroll = bgScroll
                    pendingScroll[0] = Float.NaN
                    appliedScroll[0] = bgScroll
                    if (zoomOk && (lastMainZoom.isNaN() || abs(bgZoom - lastMainZoom) > 0.001f)) {
                        graphViewModel.graphSyncLog(
                            "mainZoom",
                            "zoom $lastMainZoom -> $bgZoom scroll $scrollBefore -> $bgScroll max=$bgMax followNow=$followNow"
                        )
                        // 2026-10-21, per explicit request: a pinch on the main graph is the user's too. It kept "follow now" on, so the next
                        // reading moved the window back to the current time. A zoom made by code (the -/+ buttons) is not.
                        if (recentTouch() && dateUtil.now() > skipInteractionUntilMs[0]) {
                            followNow = false
                            graphViewModel.onGraphInteraction()
                        }
                        lastMainZoom = bgZoom
                        appliedZoom[0] = bgZoom
                    }
                    for (i in states.indices) {
                        if (i < lastFollowerScroll.size && states[i].first.isFinite()) lastFollowerScroll[i] = states[i].first
                        if (i < lastFollowerZoom.size && states[i].second.isFinite()) lastFollowerZoom[i] = states[i].second
                    }
                    return@collect
                }
                // A drag on a lower graph moves every other graph, including the main one.
                // scroll() on the chart under the finger waits until the finger lifts. That wait
                // used to stop the copy, so the other charts never moved during the drag.
                // A jump back to the start while the main graph is still showing hours is a rebuild.
                fun driverAt(index: Int): VicoScrollState {
                    if (index <= 0) return iobScrollState
                    val secondaryIndex = index - 1
                    val count = activeCount
                    return if (secondaryIndex < count) secScrollStates[secondaryIndex] else g5ScrollState
                }
                suspend fun shareScroll(place: Float, driver: VicoScrollState?) {
                    if (!recentTouch()) {
                        // No finger on the graphs: a lower graph moved by itself (startup position, rebuild). It must not
                        // pull the main graph away from the current time. Put the lower graphs back on the main graph's place.
                        graphViewModel.graphSyncLog("shareScroll ignored (no touch)", "place=$place bgScroll=$bgScroll bgMax=$bgMax")
                        beltScrollState.copyPixelsIfDifferent(scrollForGraph(bgScroll, beltScrollState), null)
                        iobScrollState.copyPixelsIfDifferent(scrollForGraph(bgScroll, iobScrollState), null)
                        if (showGraph5Now.value) g5ScrollState.copyPixelsIfDifferent(scrollForGraph(bgScroll, g5ScrollState), null)
                        for (i in 0 until activeCount) secScrollStates[i].copyPixelsIfDifferent(scrollForGraph(bgScroll, secScrollStates[i]), null)
                        return
                    }
                    graphViewModel.graphSyncLog("shareScroll", "place=$place bgScroll=$bgScroll bgMax=$bgMax placedOnNow=$placedOnNow followNowWas=$followNow")
                    pendingScroll[0] = place
                    appliedScroll[0] = place
                    drivingScroll[0] = driver
                    lastShareMs[0] = dateUtil.now()
                    followNow = false
                    graphViewModel.onGraphInteraction()
                    skipInteractionUntilMs[0] = dateUtil.now() + 1000L
                    bgScrollState.copyPixelsIfDifferent(place, driver)
                    // Our copy moved the main graph. Record it now so the next tick does not
                    // treat that move as a finger on the main graph and drop the rest of the drag.
                    lastMainScroll = bgScrollState.value
                    beltScrollState.copyPixelsIfDifferent(scrollForGraph(place, beltScrollState), driver)
                    iobScrollState.copyPixelsIfDifferent(scrollForGraph(place, iobScrollState), driver)
                    if (showGraph5Now.value) g5ScrollState.copyPixelsIfDifferent(scrollForGraph(place, g5ScrollState), driver)
                    val count = activeCount
                    for (i in 0 until count) secScrollStates[i].copyPixelsIfDifferent(scrollForGraph(place, secScrollStates[i]), driver)
                }
                if (!pendingScroll[0].isNaN()) {
                    var newer: Float? = null
                    var newerIndex = -1
                    for (i in states.indices) {
                        if (i >= lastFollowerScroll.size) break
                        val scroll = states[i].first
                        val prev = lastFollowerScroll[i]
                        if (!scroll.isFinite() || scroll < 1f || prev.isNaN()) continue
                        val moved = abs(scroll - prev) > 1f
                        val toward = abs(scroll - pendingScroll[0]) < abs(prev - pendingScroll[0])
                        if (moved && !toward) {
                            newer = scroll
                            newerIndex = i
                            break
                        }
                    }
                    if (newer != null) {
                        shareScroll(newer, driverAt(newerIndex))
                    } else {
                        val place = pendingScroll[0]
                        shareScroll(place, drivingScroll[0])
                        pendingScroll[0] = Float.NaN
                    }
                } else {
                    var finger: Float? = null
                    var fingerIndex = -1
                    for (i in states.indices) {
                        if (i >= lastFollowerScroll.size) break
                        val scroll = states[i].first
                        if (!scroll.isFinite()) continue
                        val prev = lastFollowerScroll[i]
                        val rebuild = scroll < 1f && bgScroll > 24f
                        val moved = !prev.isNaN() && abs(scroll - prev) > 1f
                        val towardApplied = !appliedScroll[0].isNaN() &&
                            abs(scroll - appliedScroll[0]) < abs(prev - appliedScroll[0])
                        if (moved && !rebuild && !towardApplied) {
                            finger = scroll
                            fingerIndex = i
                            break
                        }
                    }
                    if (finger != null && finger.isFinite()) {
                        shareScroll(finger, driverAt(fingerIndex))
                    }
                }
                // A pinch on any graph sets the same width on every graph, including the main one.
                // A small change counts. The old check waited for a big jump, so a normal pinch was
                // written back and the other graphs never changed width.
                if (zoomOk) {
                    val mainZoomMoved = !lastMainZoom.isNaN() && abs(bgZoom - lastMainZoom) > 0.001f
                    if (mainZoomMoved) {
                        // 2026-10-20: a pinch on the main graph, with where it was before and after
                        graphViewModel.graphSyncLog(
                            "mainZoom",
                            "zoom $lastMainZoom -> $bgZoom scroll $lastMainScroll -> $bgScroll max=$bgMax followNow=$followNow"
                        )
                        if (recentTouch() && dateUtil.now() > skipInteractionUntilMs[0]) {
                            followNow = false
                            graphViewModel.onGraphInteraction()
                        }
                        lastMainZoom = bgZoom
                        appliedZoom[0] = bgZoom
                    } else {
                        var zoomFinger: Float? = null
                        var zoomFingerIndex = -1
                        for (i in states.indices) {
                            if (i >= lastFollowerZoom.size) break
                            val zoom = states[i].second
                            val prev = lastFollowerZoom[i]
                            if (!zoom.isFinite() || zoom <= 0f || prev.isNaN()) continue
                            val moved = abs(zoom - prev) > 0.001f
                            val towardApplied = !appliedZoom[0].isNaN() &&
                                abs(zoom - appliedZoom[0]) < abs(prev - appliedZoom[0])
                            if (moved && !towardApplied) {
                                zoomFinger = zoom
                                zoomFingerIndex = i
                                break
                            }
                        }
                        // 2026-10-08: only a pinch with a finger on the graphs counts. 8 Oct 17:09:02 the lower graphs' zoom
                        // settling after the first layout was taken for a pinch, cleared followNow and disabled the startup
                        // guard, leaving the graphs on old hours for 3 minutes.
                        if (zoomFinger != null && zoomFinger.isFinite() && zoomFinger > 0f && recentTouch()) {
                            followNow = false
                            graphViewModel.onGraphInteraction()
                            skipInteractionUntilMs[0] = dateUtil.now() + 1000L
                            bgZoomState.copyFactorIfDifferent(zoomFinger)
                            if (beltZoomState.value.isFinite()) beltZoomState.copyFactorIfDifferent(zoomForGraph(zoomFinger, beltZoomState))
                            if (iobZoomState.value.isFinite()) iobZoomState.copyFactorIfDifferent(zoomForGraph(zoomFinger, iobZoomState))
                            if (showGraph5Now.value && g5ZoomState.value.isFinite()) g5ZoomState.copyFactorIfDifferent(zoomForGraph(zoomFinger, g5ZoomState))
                            val zoomCount = activeCount
                            for (i in 0 until zoomCount) {
                                if (secZoomStates[i].value.isFinite()) secZoomStates[i].copyFactorIfDifferent(zoomForGraph(zoomFinger, secZoomStates[i]))
                            }
                            lastMainZoom = zoomFinger
                            appliedZoom[0] = zoomFinger
                            // 2026-10-20: the place too, not only the width. A pinch zooms around the fingers, so each
                            // graph ended the same width but on a different time, until a drag lined them up again.
                            val pinchedPlace = states.getOrNull(zoomFingerIndex)?.first
                            graphViewModel.graphSyncLog("pinch", "graph=$zoomFingerIndex zoom=$zoomFinger place=$pinchedPlace bgScroll=$bgScroll")
                            if (pinchedPlace != null && pinchedPlace.isFinite()) shareScroll(pinchedPlace, driverAt(zoomFingerIndex))
                        } else if (lastMainZoom.isNaN()) {
                            lastMainZoom = bgZoom
                            appliedZoom[0] = bgZoom
                        } else {
                            val sharedZoom = appliedZoom[0]
                            if (!sharedZoom.isNaN() && sharedZoom > 0f) {
                                var needsCopy = false
                                for (i in states.indices) {
                                    val zoom = states[i].second
                                    if (!zoom.isFinite() || abs(zoom - sharedZoom) <= 0.001f) continue
                                    val prev = if (i < lastFollowerZoom.size) lastFollowerZoom[i] else Float.NaN
                                    val movingAway = !prev.isNaN() && abs(zoom - sharedZoom) > abs(prev - sharedZoom)
                                    if (!movingAway) needsCopy = true
                                }
                                if (needsCopy || (bgZoom.isFinite() && abs(bgZoom - sharedZoom) > 0.001f)) {
                                    if (abs(bgZoom - sharedZoom) > 0.001f) bgZoomState.copyFactorIfDifferent(sharedZoom)
                                    if (beltZoomState.value.isFinite()) beltZoomState.copyFactorIfDifferent(zoomForGraph(sharedZoom, beltZoomState))
                                    if (iobZoomState.value.isFinite()) iobZoomState.copyFactorIfDifferent(zoomForGraph(sharedZoom, iobZoomState))
                                    if (showGraph5Now.value && g5ZoomState.value.isFinite()) g5ZoomState.copyFactorIfDifferent(zoomForGraph(sharedZoom, g5ZoomState))
                                    val zoomCount = activeCount
                                    for (i in 0 until zoomCount) {
                                        if (secZoomStates[i].value.isFinite()) secZoomStates[i].copyFactorIfDifferent(zoomForGraph(sharedZoom, secZoomStates[i]))
                                    }
                                }
                            }
                        }
                    }
                }
                for (i in states.indices) {
                    if (i < lastFollowerScroll.size && states[i].first.isFinite()) lastFollowerScroll[i] = states[i].first
                    if (i < lastFollowerZoom.size && states[i].second.isFinite()) lastFollowerZoom[i] = states[i].second
                }
                if (zoomOk && lastMainZoom.isNaN()) lastMainZoom = bgZoom
            }
    }


    // Wheel zoom keeps the time already at the right edge, including a historical time.
    val zoomScope = rememberCoroutineScope()
    val wheelZoomMutex = remember(bgZoomState) { Mutex() }
    val zoomStep: (Int) -> Unit = { direction ->
        graphViewModel.onGraphInteraction()
        zoomScope.launch {
            wheelZoomMutex.withLock {
                val current = bgZoomState.value
                if (current.isFinite() && current > 0f) {
                    val factor = if (direction > 0) current * ZOOM_STEP else current / ZOOM_STEP
                    // 2026-10-18: a zoom that starts with "now" at the right edge ends there too. On Windows the window
                    // used to move left after a zoom step, so the current time went off the right edge.
                    val atNow = bgScrollState.value >= bgScrollState.maxValue - 2f
                    skipInteractionUntilMs[0] = dateUtil.now() + 1000L
                    // 2026-10-21: around the middle of the window, so the time in the centre stays; at "now" the right edge stays
                    bgZoomState.animateZoom(Zoom.fixed(factor), animationSpec = tween(0), bias = if (atNow) 1f else 0.5f)
                    if (atNow) bgScrollState.scroll(Scroll.Absolute.End)
                }
            }
        }
    }
    val panStep: (Float) -> Unit = { px ->
        followNow = false
        zoomScope.launch {
            val target = (bgScrollState.value + px).coerceIn(0f, bgScrollState.maxValue)
            bgScrollState.scroll(Scroll.Absolute.pixels(target))
        }
    }

    // 2026-10-18, per explicit request: on phones and tablets too (was desktop only), next to pinch zoom.
    val showZoomButtons = !LocalInspectionMode.current
    // Scale only the displayed height; keep the saved portrait heights for the graph editor.
    val graphHeightScale = if (isLandscape()) 2f / 3f else 1f
    val zoomEndPadding = 32.dp + if (isLandscape()) AapsTheme.spacing.extraLarge else 0.dp
    // Set by the BG graph, read by every graph
    val inspect = remember { GraphInspectState() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (isLandscape()) Modifier.blockSystemEdgeGesture() else Modifier)
            .graphZoomControls(zoomStep, panStep)
            .onScrollOrZoomGesture(onVerticalScroll = {
                // Cancel any recent horizontal drag/fling permission as soon as a page scroll starts.
                pageScrolling[0] = true
            }) {
                pageScrolling[0] = false
                lastTouchMs[0] = dateUtil.now()
            }
            // Upstream (2026-10-27 port): a touch anywhere on the graphs closes the inspect pill
            .closesInspectPillOnTouch(inspect)
    ) {
        if (showZoomButtons) {
            GraphZoomButtons(zoomStep, Modifier.align(Alignment.End))
        }
        // BG Graph - primary interactive graph
        var editingBgOverlays by remember { mutableStateOf(false) }

        // The graph is drawn, so a screen reader gets nothing from it at all - a chart contributes
        // no semantics node of its own. Describe it instead: the recent readings newest first,
        // because the current value is what matters most and a listener may stop there, plus what
        // is on board. The description goes on the graph itself rather than the Box, so the edit
        // button beside it stays a separate control.
        val bgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
        val profileUtil = LocalProfileUtil.current
        val decimalFormatter = LocalDecimalFormatter.current
        val recentValues = remember(bgReadings, profileUtil.units) {
            // BgDataPoint.value is ALREADY in the user's units, so it must not be converted again -
            // only formatted. The unit decides the decimals; the "detect" helpers guess that from
            // the magnitude and would read a very high mmol/l reading as mg/dl.
            newestReadings(bgReadings, RECENT_VALUES_SPOKEN)
                .joinToString(", ") {
                    if (profileUtil.units == GlucoseUnit.MGDL) decimalFormatter.to0Decimal(it.value)
                    else decimalFormatter.to1Decimal(it.value)
                }
        }
        val graphDescription = when {
            recentValues.isEmpty()                 -> null
            iobText != null && cobText != null     -> stringResource(
                UiStrings.a11y_bg_graph_summary,
                recentValues,
                stringResource(InterfacesStrings.confirmation_line, stringResource(CoreUiStrings.iob), iobText),
                stringResource(InterfacesStrings.confirmation_line, stringResource(CoreUiStrings.cob), cobText)
            )

            else                                   -> stringResource(UiStrings.a11y_bg_graph_summary_values_only, recentValues)
        }

        // The predicted curves are drawn and nothing else on the screen states them, so they were
        // out of reach entirely. Each curve gets one sentence naming where it is heading, which is
        // what a listener can actually hold in their head - reading a whole series of points back
        // would be unusable. Reported as the biggest excursion from the current glucose rather than
        // the final point, because a curve that dips to 3.2 before settling at 6.0 matters for the
        // dip, and that is the reading of "heading up to" or "down to" a user means.
        val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
        val currentValue = remember(bgReadings) { newestReadings(bgReadings, 1).firstOrNull()?.value }
        val unitLabel = if (profileUtil.units == GlucoseUnit.MMOL) stringResource(CoreUiStrings.mmol) else stringResource(CoreUiStrings.mgdl)
        // Nothing is said about curves the user has switched off the graph.
        val predictionsVisible = SeriesType.PREDICTIONS in graphConfig.bgOverlays
        val predictionExtremes = remember(predictions, currentValue, predictionsVisible) {
            if (!predictionsVisible || currentValue == null) emptyList()
            else PREDICTION_SPOKEN_ORDER.mapNotNull { (type, name) ->
                predictionExtreme(predictions.filter { it.type == type }.map { it.value }, currentValue)
                    ?.let { (value, direction) -> Triple(name, value, direction) }
            }
        }
        // map, not joinToString: the latter is not inline, so a @Composable call cannot sit in its
        // lambda. The finished sentences are joined afterwards, as the graph summary joins its values.
        val predictionSentences = predictionExtremes.map { (name, value, direction) ->
            // BgDataPoint.value is ALREADY in the user's units, as above: format, never convert.
            val shown = stringResource(
                CoreUiStrings.value_with_unit,
                if (profileUtil.units == GlucoseUnit.MGDL) decimalFormatter.to0Decimal(value) else decimalFormatter.to1Decimal(value),
                unitLabel
            )
            when (direction) {
                PredictionDirection.UP   -> stringResource(UiStrings.a11y_prediction_rises, stringResource(name), shown)
                PredictionDirection.DOWN -> stringResource(UiStrings.a11y_prediction_falls, stringResource(name), shown)
                PredictionDirection.FLAT -> stringResource(UiStrings.a11y_prediction_flat, stringResource(name), shown)
            }
        }
        val predictionDescription =
            if (predictionSentences.isEmpty()) null
            else stringResource(UiStrings.a11y_predictions, predictionSentences.joinToString(" "))

        // Upstream's touch-to-inspect (2026-10-16 merge), without its 16 dp shift up: that shift sat the graph under the
        // treatment belt strip, which this fork removed on 2026-09-26.
        Box(modifier = Modifier.graphInspectTouch(inspect)) {
            BgGraphCompose(
                viewModel = graphViewModel,
                automaticCleanGraph = automaticCleanGraph,
                cleanGraphHidesTextLines = cleanGraphHidesTextLines,
                bgOverlays = graphConfig.bgOverlays,
                scrollState = bgScrollState,
                zoomState = bgZoomState,
                geometryHolder = bgGeometry,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = nowTimestamp,
                visibleTimeRange = bgVisibleTimeRange,
                inspect = inspect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(graphConfig.bgHeight.dp * graphHeightScale)
                    .onScrollOrZoomGesture {
                        graphViewModel.onGraphInteraction()
                        followNow = false
                        drivingScroll[0] = null
                    }
                    .then(
                        // Merging, not a bare semantics block: a described node is reached by linear
                        // screen-reader navigation only when it is a leaf or a merging parent. This one
                        // has a child (the chart's own node, which carries no text), so unmerged it was
                        // skipped in both directions and could be reached only as the first step from
                        // the glucose circle or at the wrap point after the last tab - measured with
                        // TalkBack on a phone. Merging absorbs that empty child, so the words do not
                        // change and no action comes with it; the edit button is a clickable sibling and
                        // stays its own stop.
                        if (graphDescription != null) Modifier.semantics(mergeDescendants = true) { contentDescription = graphDescription }
                        else Modifier
                    )
            )
            if (!isSimpleMode) {
                bgReadings.lastOrNull()?.let { last ->
                    val profileUtil = LocalProfileUtil.current
                    val text = profileUtil.fromMgdlToStringWithUnits(last.value)
                    val color = when (last.range) {
                        BgRange.HIGH -> AapsTheme.generalColors.bgHigh
                        BgRange.LOW -> AapsTheme.generalColors.bgLow
                        else -> AapsTheme.generalColors.bgInRange
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 8.dp, top = 4.dp)
                    )
                }
                GraphEditButton(
                    onClick = { editingBgOverlays = true },
                    graphName = stringResource(CoreUiStrings.glucose),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp, top = 2.dp)
                )
            }
            // Own composable, so a finger move does not recompose this section. On the main screen it is drawn over the
            // top bar (TopBarPillHost, 2026-10-27, per explicit request); elsewhere, as before, over the top of BG.
            val pillHost = LocalTopBarPillHost.current
            if (pillHost == null) {
                GraphInspectOverlay(
                    inspect = inspect,
                    graphViewModel = graphViewModel,
                    bgReadings = bgReadings,
                    minTimestamp = derivedTimeRange?.first,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = INSPECT_PILL_OFFSET)
                        .padding(top = 2.dp, start = 36.dp, end = 36.dp)
                )
            } else {
                // Read through states, so the pill drawn by the host follows the newest readings and time range
                val latestBgReadings by rememberUpdatedState(bgReadings)
                val latestMinTimestamp by rememberUpdatedState(derivedTimeRange?.first)
                DisposableEffect(pillHost, inspect, graphViewModel) {
                    val pill: @Composable () -> Unit = {
                        GraphInspectOverlay(
                            inspect = inspect,
                            graphViewModel = graphViewModel,
                            bgReadings = latestBgReadings,
                            minTimestamp = latestMinTimestamp
                        )
                    }
                    pillHost.content = pill
                    onDispose { if (pillHost.content === pill) pillHost.content = null }
                }
            }
        }
        // A node of its own rather than more text on the graph above, so a listener reaches the
        // readings first and swipes once to decide whether she wants the forecast, instead of having
        // to sit through it every time. It needs real bounds to be reachable at all, hence a 1 dp
        // strip: invisible on screen, present in the accessibility tree.
        predictionDescription?.let { description ->
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .semantics { contentDescription = description }
            )
        }
        if (editingBgOverlays) {
            GraphSeriesBottomSheet(
                title = stringResource(CoreUiStrings.graph_bg),
                selectedSeries = graphConfig.bgOverlays,
                availableSeries = BG_OVERLAY_SERIES,
                height = graphConfig.bgHeight,
                maxHeight = GraphConfig.MAX_BG_GRAPH_HEIGHT_DP,
                onHeightChange = { h ->
                    graphViewModel.updateGraphConfig(graphConfig.copy(bgHeight = h))
                },
                onToggle = { type ->
                    val current = graphConfig.bgOverlays.toMutableList()
                    if (type in current) current.remove(type) else current.add(type)
                    graphViewModel.updateGraphConfig(graphConfig.copy(bgOverlays = current))
                },
                onDismiss = { editingBgOverlays = false }
            )
        }
        // Fixed IOB graph (Graph 1) with optional Activity overlay
        var editingIobOverlays by remember { mutableStateOf(false) }
        // Fork (restored after the 2026-10-16 merge): the graphs below BG keep their time labels and full height,
        // each drawn 8 dp higher as before. Upstream's pull-up covered the BG graph's own time labels.
        Box(modifier = Modifier.offset(y = (-8).dp).graphInspectTouch(inspect)) {
            SecondaryGraphCompose(
                viewModel = graphViewModel,
                seriesTypes = listOf(SeriesType.IOB),
                cobOverlay = SeriesType.COB in graphConfig.iobOverlays,
                scrollState = iobScrollState,
                zoomState = iobZoomState,
                geometryHolder = iobGeometry,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = nowTimestamp,
                activityOverlay = SeriesType.ACTIVITY in graphConfig.iobOverlays,
                onVisibleRangeChanged = { iobVisibleRange = it },
                inspect = inspect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(graphConfig.iobHeight.dp * graphHeightScale)
            )
            // 2026-10-24, per explicit request: a label again, like the other graphs ("G1 IOB COB Basal Max rates").
            // It sits at the bottom left, because the hourly basal peak numbers use the top line of this graph.
            GraphLabel(
                number = 1,
                names = graphConfig.iobOverlays.let { overlays ->
                    listOf(stringResource(CoreUiStrings.iob)) + overlays.map { stringResource(seriesShortNameId(it)) } +
                        listOf(stringResource(UiStrings.graph_label_basal), stringResource(UiStrings.graph_label_max_rates))
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 36.dp, bottom = 18.dp)
            )
            if (showZoomButtons) GraphZoomButtons(zoomStep, Modifier.align(Alignment.TopEnd).padding(end = zoomEndPadding))
            if (!isSimpleMode) {
                GraphEditButton(
                    onClick = { editingIobOverlays = true },
                    graphName = stringResource(CoreUiStrings.iob),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp, top = 2.dp)
                )
            }
        }
        if (editingIobOverlays) {
            GraphSeriesBottomSheet(
                title = stringResource(CoreUiStrings.iob) + " / " + stringResource(CoreUiStrings.basal_shortname),
                selectedSeries = graphConfig.iobOverlays,
                availableSeries = listOf(SeriesType.ACTIVITY, SeriesType.COB),
                height = graphConfig.iobHeight,
                onHeightChange = { h ->
                    graphViewModel.updateGraphConfig(graphConfig.copy(iobHeight = h))
                },
                onToggle = { type ->
                    val current = graphConfig.iobOverlays.toMutableList()
                    if (type in current) current.remove(type) else current.add(type)
                    graphViewModel.updateGraphConfig(graphConfig.copy(iobOverlays = current))
                },
                onDismiss = { editingIobOverlays = false }
            )
        }

        // Secondary graphs — config-driven (labels start at "Graph 2")
        var editingGraphIndex by remember { mutableIntStateOf(-1) }
        for (i in 0 until activeCount) {
            val secondary = graphConfig.secondaryGraphs[i]
            Box(modifier = Modifier.offset(y = (-8).dp).graphInspectTouch(inspect)) {
                SecondaryGraphCompose(
                    viewModel = graphViewModel,
                    inspect = inspect,
                    seriesTypes = secondary.series,
                    showSmbDoseLabels = i == 0,
                    secondaryMarks = when (i) {
                        1 -> SecondaryMarks.SMB_TOTALS
                        2 -> SecondaryMarks.NOTES
                        else -> SecondaryMarks.NONE
                    },
                    scrollState = secScrollStates[i],
                    zoomState = secZoomStates[i],
                    geometryHolder = secGeometry[i],
                    derivedTimeRange = derivedTimeRange,
                    nowTimestamp = nowTimestamp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(secondary.height.dp * graphHeightScale)
                )
                // Name at the top left and the pencil at the top right, as before the merge: the bottom is the time labels.
                // 2026-10-24, per explicit request: numbered (G2 is the first of these), full strength instead of 60%, and
                // with what the graph draws besides its curves.
                GraphLabel(
                    number = i + 2,
                    // The same graphs as showSmbDoseLabels and secondaryMarks above
                    names = secondary.series.map { stringResource(seriesShortNameId(it)) } + when (i) {
                        0    -> listOf(stringResource(UiStrings.graph_label_smb))
                        1    -> listOf(stringResource(UiStrings.graph_label_stack_totals))
                        2    -> listOf(stringResource(UiStrings.graph_label_notes))
                        else -> emptyList()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 36.dp, top = 2.dp)
                )
                if (showZoomButtons) GraphZoomButtons(zoomStep, Modifier.align(Alignment.TopEnd).padding(end = zoomEndPadding))
                if (!isSimpleMode) {
                    GraphEditButton(
                        onClick = { editingGraphIndex = i },
                        graphName = seriesListLabel(secondary.series),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 4.dp, top = 2.dp)
                    )
                }
            }
        }
        // Graph 5 (second copy of the glucose graph with the SMB / acce weight / pp weight / profile basal lines in its
        // top band) is the LAST graph, below every secondary graph, as on 3426. Its scroll/zoom is already synced as the
        // last entry of the shared list above.
        if (showGraph5) {
            var editingGraph5 by remember { mutableStateOf(false) }
            Box {
                BgGraphCompose(
                    viewModel = graphViewModel,
                    automaticCleanGraph = automaticCleanGraph,
                    cleanGraphHidesTextLines = cleanGraphHidesTextLines,
                    bgOverlays = graphConfig.bgOverlays,
                    scrollState = g5ScrollState,
                    zoomState = g5ZoomState,
                    geometryHolder = g5Geometry,
                    derivedTimeRange = derivedTimeRange,
                    nowTimestamp = nowTimestamp,
                    visibleTimeRange = bgVisibleTimeRange,
                    topBandLines = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(graphConfig.graph5Height.dp * graphHeightScale)
                )
                if (showZoomButtons) GraphZoomButtons(zoomStep, Modifier.align(Alignment.TopEnd).padding(end = zoomEndPadding))
                if (!isSimpleMode) {
                    GraphEditButton(
                        onClick = { editingGraph5 = true },
                        graphName = stringResource(CoreUiStrings.graph_number, 5),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 4.dp, top = 2.dp)
                    )
                }
            }
            if (editingGraph5) {
                // Height only: graph 5's lines are fixed (it shows what the main graph's overlays show, plus the four top band lines).
                GraphSeriesBottomSheet(
                    title = stringResource(CoreUiStrings.graph_number, 5),
                    selectedSeries = emptyList(),
                    availableSeries = emptyList(),
                    height = graphConfig.graph5Height,
                    maxHeight = GraphConfig.MAX_BG_GRAPH_HEIGHT_DP,
                    onHeightChange = { h -> graphViewModel.updateGraphConfig(graphConfig.copy(graph5Height = h)) },
                    onToggle = { },
                    onDismiss = { editingGraph5 = false }
                )
            }
        }
        if (editingGraphIndex >= 0 && editingGraphIndex < activeCount) {
            // Read once. The lambdas below can run after the sheet has closed and the index is
            // already -1: NumberInputRow saves a focused field when it leaves the screen.
            val index = editingGraphIndex
            val editing = graphConfig.secondaryGraphs[index]
            GraphSeriesBottomSheet(
                title = stringResource(CoreUiStrings.graph_number, index + 2),
                selectedSeries = editing.series,
                availableSeries = CONFIGURABLE_SERIES,
                height = editing.height,
                onHeightChange = { h ->
                    // The current config, not the captured one: after "Remove graph" the captured
                    // config still holds the removed graph, and writing it back would restore it.
                    withSecondaryGraphHeight(graphViewModel.graphConfigFlow.value, index, editing.series, h)
                        ?.let { graphViewModel.updateGraphConfig(it) }
                },
                onToggle = { type ->
                    val graphs = graphConfig.secondaryGraphs.toMutableList()
                    val current = graphs[index].series.toMutableList()
                    if (type in current) {
                        current.remove(type)
                    } else {
                        current.add(type)
                        if (current.size > 3) current.removeAt(0) // FIFO: drop oldest
                    }
                    if (current.isEmpty()) {
                        // Auto-remove graph when all series deselected
                        graphs.removeAt(index)
                        editingGraphIndex = -1
                    } else {
                        graphs[index] = graphs[index].copy(series = current)
                    }
                    graphViewModel.updateGraphConfig(graphConfig.copy(secondaryGraphs = graphs))
                },
                onRemoveGraph = {
                    val graphs = graphConfig.secondaryGraphs.toMutableList()
                    graphs.removeAt(index)
                    editingGraphIndex = -1
                    graphViewModel.updateGraphConfig(graphConfig.copy(secondaryGraphs = graphs))
                },
                onDismiss = { editingGraphIndex = -1 }
            )
        }
        // Add graph button. In simple mode, or with all graphs added, it stays as an invisible placeholder: its height
        // is part of the space below the last graph, and the spacer alone is lower than the QuickLaunch toolbar.
        val canAddGraph = !isSimpleMode && activeCount < GraphConfig.MAX_SECONDARY_GRAPHS
        var showAddSheet by remember { mutableStateOf(false) }
        TextButton(
            onClick = { showAddSheet = true },
            enabled = canAddGraph,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (canAddGraph) Modifier else Modifier.alpha(0f).clearAndSetSemantics {})
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(CoreUiStrings.graph_add), style = MaterialTheme.typography.labelMedium)
        }
        if (canAddGraph && showAddSheet) {
            var newGraphSeries by remember { mutableStateOf(emptyList<SeriesType>()) }
            var newGraphHeight by remember { mutableIntStateOf(GraphConfig.DEFAULT_GRAPH_HEIGHT_DP) }
            GraphSeriesBottomSheet(
                title = stringResource(CoreUiStrings.graph_new),
                selectedSeries = newGraphSeries,
                availableSeries = CONFIGURABLE_SERIES,
                height = newGraphHeight,
                onHeightChange = { newGraphHeight = it },
                onToggle = { type ->
                    val current = newGraphSeries.toMutableList()
                    if (type in current) {
                        current.remove(type)
                    } else {
                        current.add(type)
                        if (current.size > 3) current.removeAt(0)
                    }
                    newGraphSeries = current
                },
                onDismiss = {
                    if (newGraphSeries.isNotEmpty()) {
                        val graphs = graphConfig.secondaryGraphs.toMutableList()
                        graphs.add(SecondaryGraph(newGraphSeries, newGraphHeight))
                        graphViewModel.updateGraphConfig(graphConfig.copy(secondaryGraphs = graphs))
                    }
                    newGraphSeries = emptyList()
                    newGraphHeight = GraphConfig.DEFAULT_GRAPH_HEIGHT_DP
                    showAddSheet = false
                }
            )
        }
        // Spacer so the last graph / Add button isn't covered by QuickLaunch toolbar
        Spacer(Modifier.height(48.dp + overlaidBarsHeight))
    }
}

/**
 * The [count] newest readings, newest first.
 *
 * Sorted here rather than trusted: the graph data comes newest first, and reading it as oldest
 * first made the screen reader speak the readings from 24 hours ago instead of the current ones.
 */
internal fun newestReadings(readings: List<BgDataPoint>, count: Int): List<BgDataPoint> =
    readings.sortedByDescending { it.timestamp }.take(count)

/**
 * [config] with the height of secondary graph [index] set to [height], or null when that graph no
 * longer shows [expectedSeries].
 *
 * The height field saves its text when the settings sheet closes, so this can run after the graph
 * was removed. Then [index] points to another graph, or to nothing, and nothing may be written.
 * Graphs have no id, so the graph is recognised by its series. The height is not compared: quick
 * taps on the +/- buttons can arrive before the screen has caught up with the previous one.
 * With two graphs showing the same series, the other one could get the height. That is harmless.
 */
internal fun withSecondaryGraphHeight(config: GraphConfig, index: Int, expectedSeries: List<SeriesType>, height: Int): GraphConfig? {
    val graph = config.secondaryGraphs.getOrNull(index) ?: return null
    if (graph.series != expectedSeries) return null
    val graphs = config.secondaryGraphs.toMutableList()
    graphs[index] = graph.copy(height = height)
    return config.copy(secondaryGraphs = graphs)
}

// =========================================================================
// Graph label generation
// =========================================================================

/**
 * The label on a graph below the BG graph (2026-10-24, per explicit request): "G2 Acce PP SMB". The graph number, then what
 * it shows. In the theme's text colour at full strength, which is white in the dark theme.
 */
@Composable
private fun GraphLabel(number: Int, names: List<String>, modifier: Modifier = Modifier) {
    Text(
        text = (listOf(stringResource(UiStrings.graph_short_number, number)) + names).joinToString("  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
}

/** Generate a short label from the series types in a graph (e.g., "IOB", "COB", "BGI / DEV") */
@Composable
private fun seriesListLabel(seriesList: List<SeriesType>): String {
    val names = seriesList.map { stringResource(seriesShortNameId(it)) }
    return names.joinToString(" / ")
}

/** String resource ID for the short name of a series type */
private fun seriesShortNameId(type: SeriesType): TextRef = when (type) {
    SeriesType.IOB             -> CoreUiStrings.iob
    SeriesType.ABS_IOB         -> CoreUiStrings.abs_insulin_shortname
    SeriesType.COB             -> CoreUiStrings.cob
    SeriesType.BGI             -> CoreUiStrings.bgi_shortname
    SeriesType.DEVIATIONS      -> CoreUiStrings.deviation_shortname
    SeriesType.SENSITIVITY     -> CoreUiStrings.sensitivity_shortname
    SeriesType.VAR_SENSITIVITY -> CoreUiStrings.variable_sensitivity_shortname
    SeriesType.DEV_SLOPE       -> CoreUiStrings.devslope_shortname
    SeriesType.HEART_RATE      -> CoreUiStrings.heartRate_shortname
    SeriesType.STEPS           -> CoreUiStrings.steps_shortname
    SeriesType.ACTIVITY        -> CoreUiStrings.activity_shortname
    SeriesType.PREDICTIONS     -> CoreUiStrings.predictions_shortname
    SeriesType.ACCE_ISF        -> CoreUiStrings.acce_isf_shortname
    SeriesType.BG_ISF          -> CoreUiStrings.bg_isf_shortname
    SeriesType.PP_ISF          -> CoreUiStrings.pp_isf_shortname
    SeriesType.DURA_ISF        -> CoreUiStrings.dura_isf_shortname
    SeriesType.FINAL_ISF       -> CoreUiStrings.final_isf_shortname
    SeriesType.IOB_TH          -> CoreUiStrings.iob_threshold_shortname
    SeriesType.RAW_BG          -> CoreUiStrings.raw_bg_shortname
    SeriesType.UKF_BG          -> CoreUiStrings.ukf_bg_shortname
}

// =========================================================================
// Graph edit button + series picker bottom sheet
// =========================================================================

/**
 * Small − and + on a lower graph (2026-10-20, per explicit request). They zoom every graph together, the same as the
 * buttons above the main graph, and keep the current time at the right edge when it was there. So a lower graph can be
 * zoomed without a pinch, which could put the graphs out of line.
 */
@Composable
private fun GraphZoomButtons(zoomStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    val landscape = isLandscape()
    // Let Material provide full touch targets in landscape instead of squeezing them into 28 dp.
    val buttonModifier = if (landscape) Modifier else Modifier.size(28.dp)
    val iconSize = if (landscape) AapsTheme.spacing.chipIconSize else 18.dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (landscape) AapsTheme.spacing.small else 0.dp)
    ) {
        IconButton(onClick = { zoomStep(-1) }, modifier = buttonModifier) {
            Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(iconSize))
        }
        IconButton(onClick = { zoomStep(1) }, modifier = buttonModifier) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

/** Small pencil icon button overlaid on a graph */
@Composable
private fun GraphEditButton(
    onClick: () -> Unit,
    graphName: String,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(GRAPH_EDIT_BUTTON_SIZE),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Edit,
            // One per graph, so it names its graph: "Edit: COB", not "Edit" four times.
            contentDescription = rowAction(CoreUiStrings.switch_to_edit, graphName),
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Bottom sheet with toggleable FilterChips for series selection + optional remove button */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GraphSeriesBottomSheet(
    title: String,
    selectedSeries: List<SeriesType>,
    availableSeries: List<SeriesType>,
    height: Int,
    maxHeight: Int = GraphConfig.MAX_GRAPH_HEIGHT_DP,
    onHeightChange: (Int) -> Unit,
    onToggle: (SeriesType) -> Unit,
    onDismiss: () -> Unit,
    onRemoveGraph: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                if (onRemoveGraph != null) {
                    TextButton(onClick = onRemoveGraph) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(CoreUiStrings.remove))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            NumberInputRow(
                labelRef = CoreUiStrings.graph_height,
                value = height.toDouble(),
                onValueChange = { onHeightChange(it.toInt()) },
                valueRange = GraphConfig.DEFAULT_GRAPH_HEIGHT_DP.toDouble()..maxHeight.toDouble(),
                step = 10.0,
                formatAsInt = true
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                for (type in availableSeries) {
                    FilterChip(
                        selected = type in selectedSeries,
                        onClick = { onToggle(type) },
                        label = { Text(stringResource(seriesShortNameId(type))) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    }
}

// Vico waits until the finger is off a chart before scroll() runs. Calling it on the chart
// under the finger blocks every chart copied after that one, so the drag never reaches them.
// `skip` is that chart. A chart already at the target is left alone too.
private suspend fun VicoScrollState.copyPixelsIfDifferent(place: Float, skip: VicoScrollState? = null) {
    if (this === skip) return
    val current = value
    if (!current.isFinite() || abs(current - place) <= 1f) return
    scroll(Scroll.Absolute.pixels(place))
}

private suspend fun VicoZoomState.copyFactorIfDifferent(factor: Float) {
    val current = value
    if (!current.isFinite() || current <= 0f || factor <= 0f || abs(current - factor) <= 0.001f) return
    zoom(Zoom.fixed(factor))
}

// Vico saves the zoom factor. A saved NaN is kept, the point position becomes NaN, and the chart crashes.
// This slot is new, so the old NaN is left behind. A bad factor is replaced before it is stored.
private const val SAFE_ZOOM_SLOT = "finite-zoom"

/** One wheel notch, key press or button tap changes the time span by this factor. */
private const val ZOOM_STEP = 1.08f
private const val SAFE_SCROLL_SLOT = "finite-scroll"
private const val VICO_MAX_ZOOM = 10f
private const val MIN_USABLE_ZOOM = 0.0001f

@Composable
private fun rememberSafeZoomState(
    zoomEnabled: Boolean,
    initialZoom: Zoom,
    minZoom: Zoom = Zoom.Content,
    maxZoom: Zoom? = null,
): VicoZoomState {
    val defaultMax = remember { Zoom.max(Zoom.fixed(VICO_MAX_ZOOM), Zoom.Content) }
    val resolvedMax = maxZoom ?: defaultMax
    val safeInitial = remember(initialZoom) { initialZoom.ifNotUsable(1f) }
    val safeMin = remember(minZoom) { minZoom.ifNotUsable(MIN_USABLE_ZOOM) }
    val safeMax = remember(resolvedMax) { resolvedMax.ifNotUsable(VICO_MAX_ZOOM) }
    return key(SAFE_ZOOM_SLOT) {
        rememberVicoZoomState(
            zoomEnabled = zoomEnabled,
            initialZoom = safeInitial,
            minZoom = safeMin,
            maxZoom = safeMax,
        )
    }
}

private fun Zoom.ifNotUsable(fallback: Float): Zoom = Zoom { context, layerDimensions, bounds ->
    val zoom = getValue(context, layerDimensions, bounds)
    if (zoom.isFinite() && zoom > 0f) zoom else fallback
}

/** Scroll that puts the current time on the right edge of the chart. */
private fun scrollSoNowIsAtEnd(minTimestamp: Long, nowTimestamp: Long): Scroll.Absolute {
    val nowX = timestampToX(nowTimestamp, minTimestamp)
    return if (nowX.isFinite()) Scroll.Absolute.x(nowX, bias = 1f) else Scroll.Absolute.End
}

// Vico also saves the scroll. A saved NaN is kept, every point's x becomes NaN, and the same
// circle crash follows. This slot is new, so the old NaN is left behind.
@Composable
private fun rememberSafeScrollState(
    scrollEnabled: Boolean,
    initialScroll: Scroll.Absolute,
): VicoScrollState {
    val safeInitial = remember(initialScroll) { initialScroll.ifNotUsable() }
    return key(SAFE_SCROLL_SLOT) {
        rememberVicoScrollState(
            scrollEnabled = scrollEnabled,
            initialScroll = safeInitial,
        )
    }
}

private fun Scroll.Absolute.ifNotUsable(): Scroll.Absolute =
    Scroll.Absolute { context, layerDimensions, bounds, maxValue ->
        val scroll = getValue(context, layerDimensions, bounds, maxValue)
        if (scroll.isFinite() && scroll >= 0f) scroll else 0f
    }
