package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.AutoIsfGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.StepsGraphData
import kotlin.math.abs

/** These rows describe the latest loop, even when the graph is held at an older time. */
internal fun inspectStatusLines(data: AutoIsfGraphData): List<String> =
    listOfNotNull(data.statusIsf, data.statusSteps, data.statusRatio, data.statusTarget)
        .filter { it.isNotBlank() }

/** Keep each sample's own time: step counts need not have the same time as glucose. */
internal fun inspectStepSamples(data: StepsGraphData, timestamp: Long): List<Pair<Int, GraphDataPoint>> =
    listOf(data.steps, data.steps15, data.steps30, data.steps60).mapIndexedNotNull { index, points ->
        points.minByOrNull { abs(it.timestamp - timestamp) }
            ?.takeIf { abs(it.timestamp - timestamp) <= 5 * 60_000L }
            ?.let { index to it }
    }
