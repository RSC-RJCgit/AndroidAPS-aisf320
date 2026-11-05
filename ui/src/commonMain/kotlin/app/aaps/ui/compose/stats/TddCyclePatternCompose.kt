package app.aaps.ui.compose.stats

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LeftToRight
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

@Immutable
data class TddCyclePatternData(
    val rawCycles: List<CycleSeries>,
    val cleanedCycles: List<CycleSeries>,
    val rawAverage: List<Double>,
    val cleanedAverage: List<Double>,
    val cycleCount: Int,
    val totalDaysAvailable: Int
)

@Immutable
data class CycleSeries(
    val dayValues: List<Double>,
    val cycleIndex: Int,
    val startDate: String
)

/** One cycle day across the shown cycles, for the ambulatory view (2026-11-01, per explicit request). */
@Immutable
data class CycleDayBand(
    val day: Int,
    val p10: Double,
    val p25: Double,
    val median: Double,
    val p75: Double,
    val p90: Double
)

/**
 * The cycles' values for each cycle day as percentiles (2026-11-01, per explicit request), as the Sensor tab's ambulatory
 * view does: one odd cycle widens the band instead of drawing its own peak. Days no cycle has are left out.
 */
internal fun cycleDayBands(cycles: List<CycleSeries>): List<CycleDayBand> {
    val days = cycles.maxOfOrNull { it.dayValues.size } ?: 0
    return (0 until days).mapNotNull { day ->
        val sorted = cycles.mapNotNull { it.dayValues.getOrNull(day) }.sorted()
        if (sorted.isEmpty()) return@mapNotNull null
        CycleDayBand(day, percentile(sorted, 0.10), percentile(sorted, 0.25), percentile(sorted, 0.50), percentile(sorted, 0.75), percentile(sorted, 0.90))
    }
}

/**
 * The average daily total of each week of the cycle - days 1-7, 8-14, 15-21, 22-28 - over all shown cycles (2026-11-01,
 * per explicit request), for the flat quarter lines. Null for a week no cycle has a day of.
 */
internal fun cycleWeekAverages(cycles: List<CycleSeries>, weeks: Int = 4, daysPerWeek: Int = 7): List<Double?> =
    (0 until weeks).map { week ->
        val values = cycles.flatMap { cycle -> (week * daysPerWeek until (week + 1) * daysPerWeek).mapNotNull { cycle.dayValues.getOrNull(it) } }
        values.takeIf { it.isNotEmpty() }?.average()
    }

/** Linear interpolation between ranks, as the AGP and the Sensor tab do */
private fun percentile(sorted: List<Double>, fraction: Double): Double {
    val position = fraction * sorted.lastIndex
    val lower = position.toInt()
    val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
}

@Composable
fun TddCyclePatternCompose(
    data: TddCyclePatternData,
    offset: Int,
    onOffsetChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCleaned by remember { mutableStateOf(false) }
    // Lines or shaded bands, and flat week averages, as on the Sensor tab (2026-11-01, per explicit request)
    var ambulatory by remember { mutableStateOf(true) }
    var quarters by remember { mutableStateOf(true) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(UiStrings.cycles_found, data.cycleCount, data.totalDaysAvailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            FilterChip(
                selected = !showCleaned,
                onClick = { showCleaned = false },
                label = { Text(stringResource(UiStrings.raw_tdd)) },
                modifier = Modifier.padding(end = 8.dp)
            )
            FilterChip(
                selected = showCleaned,
                onClick = { showCleaned = true },
                label = { Text(stringResource(UiStrings.cleaned_tdd)) }
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            FilterChip(
                selected = !ambulatory,
                onClick = { ambulatory = false },
                label = { Text(stringResource(UiStrings.sensor_cycle_whole)) },
                modifier = Modifier.padding(end = 8.dp)
            )
            FilterChip(
                selected = ambulatory,
                onClick = { ambulatory = true },
                label = { Text(stringResource(UiStrings.sensor_cycle_ambulatory)) },
                modifier = Modifier.padding(end = 8.dp)
            )
            FilterChip(
                selected = quarters,
                onClick = { quarters = !quarters },
                label = { Text(stringResource(UiStrings.sensor_quarter_averages)) }
            )
        }

        if (showCleaned) {
            Text(
                text = stringResource(UiStrings.cleaned_tdd_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Show Day 1 date from newest cycle
        val cycles = if (showCleaned) data.cleanedCycles else data.rawCycles
        if (cycles.isNotEmpty()) {
            Text(
                text = stringResource(UiStrings.cycle_day1_date, cycles.first().startDate),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Crossfade(targetState = showCleaned, label = "tdd_cycle_view") { cleaned ->
            val shownCycles = if (cleaned) data.cleanedCycles else data.rawCycles
            val average = if (cleaned) data.cleanedAverage else data.rawAverage
            val baseColor = if (cleaned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            // Full height in landscape too (2026-11-01, per explicit request): at 2/3 it was too small to read
            val cycleChartHeight = 200.dp
            val weekAverages = if (quarters) cycleWeekAverages(shownCycles) else emptyList()
            if (ambulatory)
                TddCycleBandChart(
                    bands = cycleDayBands(shownCycles),
                    weekAverages = weekAverages,
                    baseColor = baseColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cycleChartHeight)
                )
            else
                TddCycleChart(
                    cycles = shownCycles,
                    average = average,
                    weekAverages = weekAverages,
                    baseColor = baseColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cycleChartHeight)
                )
        }

        if (quarters) {
            val decimalFormatter = LocalDecimalFormatter.current
            val shown = if (showCleaned) data.cleanedCycles else data.rawCycles
            Row(modifier = Modifier.fillMaxWidth().padding(top = AapsSpacing.small)) {
                cycleWeekAverages(shown).forEachIndexed { index, average ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(UiStrings.sensor_quarter_label, index + 1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(average?.let { decimalFormatter.to1Decimal(it) + " U" } ?: "-", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }

        // Legend
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        ) {
            val legendBaseColor = if (showCleaned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(legendBaseColor.copy(alpha = 0.5f), CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(UiStrings.cycle_lines_legend),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(AapsTheme.generalColors.cycleAverage, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(CoreUiStrings.average),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Offset slider
        NumberInputRow(
            labelRef = UiStrings.cycle_offset,
            value = offset.toDouble(),
            onValueChange = { onOffsetChange(it.toInt()) },
            valueRange = 0.0..27.0,
            step = 1.0,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TddCycleChart(
    cycles: List<CycleSeries>,
    average: List<Double>,
    weekAverages: List<Double?>,
    baseColor: Color,
    modifier: Modifier = Modifier
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val averageColor = AapsTheme.generalColors.cycleAverage
    val quarterColor = MaterialTheme.colorScheme.tertiary
    val cycleCount = cycles.size
    // Each week with a value is a flat two-point series from its first day to its last
    val shownWeeks = weekAverages.withIndex().filter { it.value != null }

    // x values 0..27, formatter shows day 1..28
    val xValues = (0..27).map { it.toDouble() }

    LaunchedEffect(cycles, average, weekAverages) {
        if (cycles.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel {
                for (cycle in cycles) {
                    series(x = xValues, y = cycle.dayValues)
                }
                series(x = xValues, y = average)
                for ((week, value) in shownWeeks) series(x = listOf(week * 7.0, week * 7.0 + 6.0), y = listOf(value!!, value))
            }
        }
    }

    // Build lines list matching series order
    // Cycle lines: thin and semi-transparent so average stands out
    // Newest cycle = most visible, oldest = faintest
    val lines = remember(cycleCount, baseColor, averageColor, shownWeeks.size, quarterColor) {
        buildList {
            for (i in 0 until cycleCount) {
                val alpha = 0.5f - i * 0.35f / cycleCount.coerceAtLeast(1)
                add(
                    LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(baseColor.copy(alpha = alpha.coerceAtLeast(0.1f)))),
                        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp)
                    )
                )
            }
            // Average line: bold, dashed, distinct green color
            add(
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(averageColor)),
                    stroke = LineCartesianLayer.LineStroke.Dashed(
                        thickness = 3.dp,
                        cap = StrokeCap.Round,
                        dashLength = 10.dp,
                        gapLength = 6.dp
                    )
                )
            )
            repeat(shownWeeks.size) {
                add(
                    LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(quarterColor)),
                        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp)
                    )
                )
            }
        }
    }

    val dayFormatter = remember {
        CartesianValueFormatter { _, value, _ -> "${value.toInt() + 1}" }
    }

    val axisLabelComponent = rememberTextComponent(style = TextStyle(color = MaterialTheme.colorScheme.onSurface))

    LeftToRight {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(lines)
                ),
                startAxis = VerticalAxis.rememberStart(
                    label = axisLabelComponent
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = dayFormatter,
                    label = axisLabelComponent
                )
            ),
            modelProducer = modelProducer,
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            modifier = modifier
        )
    }
}

/**
 * The cycles as shaded bands (2026-11-01, per explicit request): 10-90% light, 25-75% darker and the median, by cycle
 * day, with the flat week averages when asked for. Drawn on a canvas like the Sensor tab's ambulatory view, because the
 * line chart cannot fill between two lines. The TDD axis is on the left, in units.
 */
@Composable
private fun TddCycleBandChart(
    bands: List<CycleDayBand>,
    weekAverages: List<Double?>,
    baseColor: Color,
    modifier: Modifier = Modifier
) {
    val wideColor = baseColor.copy(alpha = 0.22f)
    val narrowColor = baseColor.copy(alpha = 0.42f)
    val quarterColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val top = (bands.maxOfOrNull { it.p90 } ?: 1.0).coerceAtLeast(1.0) * 1.1
    Canvas(modifier = modifier) {
        if (bands.isEmpty()) return@Canvas
        val axisWidth = 32.dp.toPx()
        val bottomLabels = 16.dp.toPx()
        val width = size.width - axisWidth
        val height = size.height - bottomLabels
        fun x(day: Int): Float = axisWidth + width * (day + 0.5f) / 28f
        fun y(value: Double): Float = height - (height * (value / top)).toFloat()

        // Grid and the axis labels, in four steps
        for (step in 0..4) {
            val value = top * step / 4
            val yy = y(value)
            drawLine(gridColor, Offset(axisWidth, yy), Offset(size.width, yy), strokeWidth = 0.5.dp.toPx())
            val layout = textMeasurer.measure(value.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(axisWidth - layout.size.width - 4.dp.toPx(), yy - layout.size.height / 2f))
        }
        for (day in listOf(0, 6, 13, 20, 27)) {
            val layout = textMeasurer.measure((day + 1).toString(), labelStyle)
            drawText(layout, topLeft = Offset(x(day) - layout.size.width / 2f, height + 2.dp.toPx()))
        }
        fun band(lower: (CycleDayBand) -> Double, upper: (CycleDayBand) -> Double, color: Color) {
            if (bands.size < 2) return
            val path = Path()
            bands.forEachIndexed { index, b -> if (index == 0) path.moveTo(x(b.day), y(upper(b))) else path.lineTo(x(b.day), y(upper(b))) }
            bands.asReversed().forEach { b -> path.lineTo(x(b.day), y(lower(b))) }
            path.close()
            drawPath(path, color)
        }
        band(CycleDayBand::p10, CycleDayBand::p90, wideColor)
        band(CycleDayBand::p25, CycleDayBand::p75, narrowColor)
        bands.zipWithNext().forEach { (a, b) ->
            drawLine(baseColor, Offset(x(a.day), y(a.median)), Offset(x(b.day), y(b.median)), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        weekAverages.forEachIndexed { week, value ->
            value ?: return@forEachIndexed
            drawLine(quarterColor, Offset(x(week * 7), y(value)), Offset(x(week * 7 + 6), y(value)), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
