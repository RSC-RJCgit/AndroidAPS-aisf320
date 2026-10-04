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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.SecondaryGraph
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.blockSystemEdgeGesture
import app.aaps.core.ui.compose.isLandscape
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce

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
    fitWholeWindow: Boolean = false
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
    val (bgScrollState, bgZoomState) = key(bgViewportResetTrigger) {
        rememberSafeScrollState(
            scrollEnabled = true,
            initialScroll = Scroll.Absolute.End
        ) to rememberSafeZoomState(
            zoomEnabled = true,
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
    val sec0zoom = rememberSafeZoomState(zoomEnabled = true, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
    val sec1scroll = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val sec1zoom = rememberSafeZoomState(zoomEnabled = true, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
    val sec2scroll = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val sec2zoom = rememberSafeZoomState(zoomEnabled = true, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
    val sec3scroll = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val sec3zoom = rememberSafeZoomState(zoomEnabled = true, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
    val sec4scroll = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val sec4zoom = rememberSafeZoomState(zoomEnabled = true, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)

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
    val iobZoomState = rememberSafeZoomState(zoomEnabled = true, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)

    // Graph 5 is a second copy of the main glucose graph. A drag here moves the other lower graphs.
    val g5ScrollState = rememberSafeScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End)
    val g5ZoomState = rememberSafeZoomState(zoomEnabled = true, initialZoom = startZoom, minZoom = bgMinZoom, maxZoom = bgMaxZoom)
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

    // Set just before this code moves the main graph, so that move is not counted as a finger drag.
    val skipInteractionUntilMs = remember { longArrayOf(0L) }
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
        var initialValue = true
        var lastZoom = Float.NaN
        var lastSentScroll = Float.NaN
        snapshotFlow { bgScrollState.value to bgZoomState.value }
            .conflate()
            .collect { (scroll, zoom) ->
                val userMovedMain = if (initialValue) {
                    initialValue = false
                    false
                } else if (placedOnNow && dateUtil.now() >= skipInteractionUntilMs[0]) {
                    // Startup and our own move to the current time also change the scroll.
                    // Only a later move is the user dragging the main graph.
                    graphViewModel.onGraphInteraction()
                    followNow = false
                    true
                } else {
                    false
                }
                // A finger on the main graph moves every chart, including one that was just driving.
                val skip = if (userMovedMain) {
                    drivingScroll[0] = null
                    null
                } else {
                    drivingScroll[0]
                }
                val count = activeCount
                // NaN is not equal to NaN, so an unready zoom would be copied on every tick.
                // A zoom that is not a real number is left alone. Multiplying it keeps the NaN,
                // and the chart crashes.
                if (zoom.isFinite() && zoom > 0f && zoom != lastZoom) {
                    lastZoom = zoom
                    appliedZoom[0] = zoom
                    if (beltZoomState.value.isFinite()) beltZoomState.copyFactorIfDifferent(zoom)
                    if (iobZoomState.value.isFinite()) iobZoomState.copyFactorIfDifferent(zoom)
                    if (showGraph5Now.value && g5ZoomState.value.isFinite()) g5ZoomState.copyFactorIfDifferent(zoom)
                    for (i in 0 until count) {
                        if (secZoomStates[i].value.isFinite()) secZoomStates[i].copyFactorIfDifferent(zoom)
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
                    beltScrollState.copyPixelsIfDifferent(scroll, skip)
                    iobScrollState.copyPixelsIfDifferent(scroll, skip)
                    if (showGraph5Now.value) g5ScrollState.copyPixelsIfDifferent(scroll, skip)
                    for (i in 0 until count) secScrollStates[i].copyPixelsIfDifferent(scroll, skip)
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
    LaunchedEffect(bgScrollState, bgZoomState) {
        snapshotFlow {
            val count = activeCount
            Triple(
                latestTimeRange.value,
                latestNow.value,
                buildList {
                    add(iobScrollState.value to iobZoomState.value)
                    for (i in 0 until count) {
                        add(secScrollStates[i].value to secZoomStates[i].value)
                    }
                    if (showGraph5Now.value) add(g5ScrollState.value to g5ZoomState.value)
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
                lastMainMax = bgMax
                if (rebuiltToStart) {
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
                if (lastMainScroll.isNaN()) lastMainScroll = bgScroll
                if (bgMax <= 1f) return@collect
                // The main graph moved. Its own effect already copies that place to the other graphs.
                // A lower graph that is still on the old place is not a finger, so it must not pull the main graph back.
                if (abs(bgScroll - lastMainScroll) > 1f) {
                    lastMainScroll = bgScroll
                    pendingScroll[0] = Float.NaN
                    appliedScroll[0] = bgScroll
                    if (zoomOk && (lastMainZoom.isNaN() || abs(bgZoom - lastMainZoom) > 0.001f)) {
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
                    pendingScroll[0] = place
                    appliedScroll[0] = place
                    drivingScroll[0] = driver
                    followNow = false
                    graphViewModel.onGraphInteraction()
                    skipInteractionUntilMs[0] = dateUtil.now() + 1000L
                    bgScrollState.copyPixelsIfDifferent(place, driver)
                    // Our copy moved the main graph. Record it now so the next tick does not
                    // treat that move as a finger on the main graph and drop the rest of the drag.
                    lastMainScroll = bgScrollState.value
                    beltScrollState.copyPixelsIfDifferent(place, driver)
                    iobScrollState.copyPixelsIfDifferent(place, driver)
                    if (showGraph5Now.value) g5ScrollState.copyPixelsIfDifferent(place, driver)
                    val count = activeCount
                    for (i in 0 until count) secScrollStates[i].copyPixelsIfDifferent(place, driver)
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
                        lastMainZoom = bgZoom
                        appliedZoom[0] = bgZoom
                    } else {
                        var zoomFinger: Float? = null
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
                                break
                            }
                        }
                        if (zoomFinger != null && zoomFinger.isFinite() && zoomFinger > 0f) {
                            followNow = false
                            graphViewModel.onGraphInteraction()
                            skipInteractionUntilMs[0] = dateUtil.now() + 1000L
                            bgZoomState.copyFactorIfDifferent(zoomFinger)
                            if (beltZoomState.value.isFinite()) beltZoomState.copyFactorIfDifferent(zoomFinger)
                            if (iobZoomState.value.isFinite()) iobZoomState.copyFactorIfDifferent(zoomFinger)
                            if (showGraph5Now.value && g5ZoomState.value.isFinite()) g5ZoomState.copyFactorIfDifferent(zoomFinger)
                            val zoomCount = activeCount
                            for (i in 0 until zoomCount) {
                                if (secZoomStates[i].value.isFinite()) secZoomStates[i].copyFactorIfDifferent(zoomFinger)
                            }
                            lastMainZoom = zoomFinger
                            appliedZoom[0] = zoomFinger
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
                                    if (beltZoomState.value.isFinite()) beltZoomState.copyFactorIfDifferent(sharedZoom)
                                    if (iobZoomState.value.isFinite()) iobZoomState.copyFactorIfDifferent(sharedZoom)
                                    if (showGraph5Now.value && g5ZoomState.value.isFinite()) g5ZoomState.copyFactorIfDifferent(sharedZoom)
                                    val zoomCount = activeCount
                                    for (i in 0 until zoomCount) {
                                        if (secZoomStates[i].value.isFinite()) secZoomStates[i].copyFactorIfDifferent(sharedZoom)
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


    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (isLandscape()) Modifier.blockSystemEdgeGesture() else Modifier)
    ) {
        // BG Graph - primary interactive graph
        var editingBgOverlays by remember { mutableStateOf(false) }
        Box {
            BgGraphCompose(
                viewModel = graphViewModel,
                bgOverlays = graphConfig.bgOverlays,
                scrollState = bgScrollState,
                zoomState = bgZoomState,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = nowTimestamp,
                visibleTimeRange = bgVisibleTimeRange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(graphConfig.bgHeight.dp)
            )
            if (!isSimpleMode) {
                GraphEditButton(
                    onClick = { editingBgOverlays = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp, top = 2.dp)
                )
            }
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
        if (showGraph5) {
            BgGraphCompose(
                viewModel = graphViewModel,
                bgOverlays = graphConfig.bgOverlays,
                scrollState = g5ScrollState,
                zoomState = g5ZoomState,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = nowTimestamp,
                visibleTimeRange = bgVisibleTimeRange,
                topBandLines = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(graphConfig.iobHeight.dp)
            )
        }
        val autoIsfStatus = graphViewModel.autoIsfGraphFlow.collectAsStateWithLifecycle().value
        // Fixed IOB graph (Graph 1) with optional Activity overlay
        var editingIobOverlays by remember { mutableStateOf(false) }
        Box(modifier = Modifier.offset(y = (-8).dp)) {
            SecondaryGraphCompose(
                viewModel = graphViewModel,
                seriesTypes = listOf(SeriesType.IOB),
                cobOverlay = SeriesType.COB in graphConfig.iobOverlays,
                scrollState = iobScrollState,
                zoomState = iobZoomState,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = nowTimestamp,
                activityOverlay = SeriesType.ACTIVITY in graphConfig.iobOverlays,
                onVisibleRangeChanged = { iobVisibleRange = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(graphConfig.iobHeight.dp)
            )
            autoIsfStatus.hypoPrediction?.let { hypo ->
                GraphCornerLine(
                    text = "hypoprediction= ${oneDecimalText(hypo)}",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
            Text(
                text = buildString {
                    append(stringResource(CoreUiStrings.iob))
                    append(" / ")
                    append(stringResource(CoreUiStrings.basal_shortname))
                    if (SeriesType.ACTIVITY in graphConfig.iobOverlays) {
                        append(" / ")
                        append(stringResource(CoreUiStrings.activity_shortname))
                    }
                    if (SeriesType.COB in graphConfig.iobOverlays) {
                        append(" / ")
                        append(stringResource(CoreUiStrings.cob))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 36.dp, top = if (autoIsfStatus.hypoPrediction != null) 14.dp else 2.dp)
            )
            if (!isSimpleMode) {
                GraphEditButton(
                    onClick = { editingIobOverlays = true },
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
            Box(modifier = Modifier.offset(y = (-8).dp)) {
                SecondaryGraphCompose(
                    viewModel = graphViewModel,
                    seriesTypes = secondary.series,
                    showSmbDoseLabels = i == 0,
                    secondaryMarks = when (i) {
                        1 -> SecondaryMarks.SMB_TOTALS
                        2 -> SecondaryMarks.NOTES
                        else -> SecondaryMarks.NONE
                    },
                    scrollState = secScrollStates[i],
                    zoomState = secZoomStates[i],
                    derivedTimeRange = derivedTimeRange,
                    nowTimestamp = nowTimestamp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(secondary.height.dp)
                )
                val corner = when (i) {
                    0 -> autoIsfStatus.statusTarget
                    1 -> autoIsfStatus.statusRatio
                    else -> null
                }
                corner?.let { line ->
                    GraphCornerLine(text = line, modifier = Modifier.align(Alignment.TopStart))
                }
                Text(
                    text = seriesListLabel(secondary.series),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 36.dp, top = if (corner != null) 14.dp else 2.dp)
                )
                if (!isSimpleMode) {
                    GraphEditButton(
                        onClick = { editingGraphIndex = i },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 4.dp, top = 2.dp)
                    )
                }
            }
        }
        if (editingGraphIndex >= 0 && editingGraphIndex < activeCount) {
            val editing = graphConfig.secondaryGraphs[editingGraphIndex]
            GraphSeriesBottomSheet(
                title = stringResource(CoreUiStrings.graph_number, editingGraphIndex + 2),
                selectedSeries = editing.series,
                availableSeries = CONFIGURABLE_SERIES,
                height = editing.height,
                onHeightChange = { h ->
                    val graphs = graphConfig.secondaryGraphs.toMutableList()
                    graphs[editingGraphIndex] = graphs[editingGraphIndex].copy(height = h)
                    graphViewModel.updateGraphConfig(graphConfig.copy(secondaryGraphs = graphs))
                },
                onToggle = { type ->
                    val graphs = graphConfig.secondaryGraphs.toMutableList()
                    val current = graphs[editingGraphIndex].series.toMutableList()
                    if (type in current) {
                        current.remove(type)
                    } else {
                        current.add(type)
                        if (current.size > 3) current.removeAt(0) // FIFO: drop oldest
                    }
                    if (current.isEmpty()) {
                        // Auto-remove graph when all series deselected
                        graphs.removeAt(editingGraphIndex)
                        editingGraphIndex = -1
                    } else {
                        graphs[editingGraphIndex] = graphs[editingGraphIndex].copy(series = current)
                    }
                    graphViewModel.updateGraphConfig(graphConfig.copy(secondaryGraphs = graphs))
                },
                onRemoveGraph = {
                    val graphs = graphConfig.secondaryGraphs.toMutableList()
                    graphs.removeAt(editingGraphIndex)
                    editingGraphIndex = -1
                    graphViewModel.updateGraphConfig(graphConfig.copy(secondaryGraphs = graphs))
                },
                onDismiss = { editingGraphIndex = -1 }
            )
        }
        // Add graph button (hidden in simple mode)
        if (!isSimpleMode && activeCount < GraphConfig.MAX_SECONDARY_GRAPHS) {
            var showAddSheet by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showAddSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(CoreUiStrings.graph_add), style = MaterialTheme.typography.labelMedium)
            }
            if (showAddSheet) {
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
        }
        // Spacer so the last graph / Add button isn't covered by QuickLaunch toolbar
        Spacer(Modifier.height(48.dp))
    }
}

// =========================================================================
// Graph label generation
// =========================================================================

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

/** Small pencil icon button overlaid on a graph */
@Composable
private fun GraphEditButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(28.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = null,
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

@Composable
private fun GraphCornerLine(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AapsTheme.generalColors.duraIsf,
) {
    Text(
        text = text,
        color = color,
        style = TextStyle(fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold),
        modifier = modifier.padding(start = 8.dp, top = 1.dp)
    )
}

private fun oneDecimalText(value: Double): String {
    val scaled = round(value * 10.0).toInt()
    val sign = if (scaled < 0) "-" else ""
    val whole = abs(scaled)
    return "$sign${whole / 10}.${whole % 10}"
}
