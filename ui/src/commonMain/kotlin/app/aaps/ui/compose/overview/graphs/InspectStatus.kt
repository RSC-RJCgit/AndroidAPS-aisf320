package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.AutoIsfGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.StepsGraphData
import kotlin.math.abs

/** These rows describe the latest loop, even when the graph is held at an older time. */
internal fun inspectStatusLines(data: AutoIsfGraphData, showSteps: Boolean = true): List<String> =
    listOfNotNull(data.statusIsf, data.statusSteps.takeIf { showSteps }, data.statusRatio, data.statusTarget)
        .filter { it.isNotBlank() }

/** Keep each sample's own time: step counts need not have the same time as glucose. */
internal fun inspectStepSamples(data: StepsGraphData, timestamp: Long): List<Pair<Int, GraphDataPoint>> =
    listOf(data.steps, data.steps15, data.steps30, data.steps60).mapIndexedNotNull { index, points ->
        points.minByOrNull { abs(it.timestamp - timestamp) }
            ?.takeIf { abs(it.timestamp - timestamp) <= 5 * 60_000L }
            ?.let { index to it }
    }

/**
 * The pairs of one status line, e.g. "f=0.70 ac=-- targetOffset= 133.0" to (f, 0.70), (ac, --), (targetOffset, 133.0), for
 * the pill's tiles (2026-11-15, per explicit request). Empty when the line is not made of name=value pairs (such as the
 * iOS heartbeat lines), which then stay plain text.
 */
internal fun statusLinePairs(line: String): List<Pair<String, String>> {
    val pairs = STATUS_PAIR.findAll(line).map { it.groupValues[1] to it.groupValues[2] }.toList()
    val covered = STATUS_PAIR.findAll(line).sumOf { it.value.length }
    return if (pairs.isEmpty() || covered < line.count { !it.isWhitespace() } * 0.8) emptyList() else pairs
}

private val STATUS_PAIR = Regex("""([A-Za-z][A-Za-z0-9]*)=\s*([^\s]+)""")
