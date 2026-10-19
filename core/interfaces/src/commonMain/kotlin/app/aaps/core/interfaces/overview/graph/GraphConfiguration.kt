package app.aaps.core.interfaces.overview.graph

import app.aaps.core.interfaces.overview.graph.GraphConfig.Companion.MAX_GRAPH_HEIGHT_DP
import kotlinx.coroutines.flow.StateFlow

/**
 * Series types available for all graphs (BG primary and secondary).
 * Each type maps to a data flow in OverviewDataCache.
 * IOB implicitly includes bolus markers, COB implicitly includes carbs markers.
 *
 * Not all types are allowed on all graphs — the UI enforces allowed sets per graph type.
 * BG graph: only BASAL, ACTIVITY.
 * Secondary graphs: all types.
 */
enum class SeriesType {

    IOB,
    ABS_IOB,
    COB,
    BGI,
    DEVIATIONS,
    SENSITIVITY,
    VAR_SENSITIVITY,
    DEV_SLOPE,
    HEART_RATE,
    STEPS,
    ACTIVITY,
    PREDICTIONS,

    /** AutoISF 3.2.1: the effective IOB threshold, a dashed overlay on the IOB graph only. */
    IOB_TH,

    /** AutoISF 3.2.1: the fitted BG parabola and its next 20 minutes, an overlay on the BG graph only. */
    BG_PARAB,

    /** AutoISF 3.2.1 ISF factors (1.0 = no change), one point per AutoISF loop. See [AUTO_ISF_SERIES]. */
    FIN_ISF,
    ACC_ISF,
    BG_ISF,
    PP_ISF,
    DUR_ISF
}

/**
 * The AutoISF 3.2.1 ISF factors. In a secondary graph they share one axis centred on 1.0 and do not count toward
 * the limit of 2 series, so one graph can show all of them, as in 3.2.1, plus 1 other series on the right axis.
 * They are also overlays on the IOB graph.
 */
val AUTO_ISF_SERIES: List<SeriesType> = listOf(SeriesType.FIN_ISF, SeriesType.ACC_ISF, SeriesType.BG_ISF, SeriesType.PP_ISF, SeriesType.DUR_ISF)

/**
 * Secondary graph entry: series list + per-graph height (dp).
 * Max 2 series per graph, not counting the [AUTO_ISF_SERIES]. Height is per-graph user-adjustable.
 */
data class SecondaryGraph(
    val series: List<SeriesType>,
    val height: Int = GraphConfig.DEFAULT_GRAPH_HEIGHT_DP
)

/**
 * Configuration for the overview graphs.
 *
 * Fixed graphs (not removable):
 * - BG graph: blood glucose with optional activity overlay (toggled via [bgOverlays]).
 * - IOB graph: IOB line with bolus markers + flipped basal overlay, with optional activity
 *   overlay (toggled via [iobOverlays]).
 *
 * @param bgOverlays       Overlay toggles for the BG graph (currently only [SeriesType.ACTIVITY]).
 * @param iobOverlays      Overlay toggles for the fixed IOB graph ([SeriesType.ACTIVITY], [SeriesType.IOB_TH] and the
 *   [AUTO_ISF_SERIES]), plus at most one other series drawn on its right axis (then basal is not drawn).
 * @param secondaryGraphs  Ordered list of user-configurable secondary graph configurations.
 *   IOB cannot appear here (it has a dedicated fixed slot). Each graph is a List (not Set) to
 *   preserve selection order:
 *   - list[0] = left axis (start), list[1] = right axis (end).
 *   Max 2 series per graph. FIFO: adding a 3rd deselects the oldest. The [AUTO_ISF_SERIES] do not count; with
 *   them in a graph, 1 other series is allowed.
 */
data class GraphConfig(
    val bgOverlays: List<SeriesType> = listOf(SeriesType.ACTIVITY, SeriesType.PREDICTIONS),
    val iobOverlays: List<SeriesType> = listOf(SeriesType.ACTIVITY),
    val bgHeight: Int = DEFAULT_GRAPH_HEIGHT_DP,
    val iobHeight: Int = DEFAULT_GRAPH_HEIGHT_DP,
    val secondaryGraphs: List<SecondaryGraph> = listOf(
        SecondaryGraph(listOf(SeriesType.COB))
    )
) {

    companion object {

        /** Maximum number of secondary graphs allowed */
        const val MAX_SECONDARY_GRAPHS = 5

        /** Default graph height in dp (minimum value — user-adjustable up to [MAX_GRAPH_HEIGHT_DP]) */
        const val DEFAULT_GRAPH_HEIGHT_DP = 100

        /** Maximum graph height in dp (2.5x default) */
        const val MAX_GRAPH_HEIGHT_DP = 250
    }
}

/**
 * Repository for graph configuration persistence.
 * Loads/saves GraphConfig and exposes it as a reactive StateFlow.
 */
interface GraphConfigRepository {

    /** Current graph configuration, updated reactively on changes */
    val graphConfigFlow: StateFlow<GraphConfig>

    /** Update and persist graph configuration */
    fun update(config: GraphConfig)
}
