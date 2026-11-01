package app.aaps.ui.compose.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.isLandscape
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlin.math.roundToInt

/**
 * The Sensor tab: glucose over the life of each CGM sensor, or around each sensor change. Drawn on a canvas like
 * the AGP rather than with Vico, because sensors end at different times and the changeover view needs bands.
 *
 * @param data null when there is no sensor change at [offset]
 * @param sensorsAvailable sensor changes found, which caps the offset
 * @param formatDate formats a sensor start for the header
 */
@Composable
fun SensorCycleCompose(
    data: SensorCycleData?,
    sensorsAvailable: Int,
    offset: Int,
    onOffsetChange: (Int) -> Unit,
    formatDate: (Long) -> String,
    modifier: Modifier = Modifier
) {
    var view by remember { mutableStateOf(SensorCycleView.WHOLE_SENSOR) }
    // Flat average lines over each quarter of the graph, as iPhone Health shows averages (2026-11-01, per explicit request)
    var quarters by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            FilterChip(
                selected = view == SensorCycleView.WHOLE_SENSOR,
                onClick = { view = SensorCycleView.WHOLE_SENSOR },
                label = { Text(stringResource(UiStrings.sensor_cycle_whole)) },
                modifier = Modifier.padding(end = 8.dp)
            )
            FilterChip(
                selected = view == SensorCycleView.AMBULATORY,
                onClick = { view = SensorCycleView.AMBULATORY },
                label = { Text(stringResource(UiStrings.sensor_cycle_ambulatory)) },
                modifier = Modifier.padding(end = 8.dp)
            )
            FilterChip(
                selected = view == SensorCycleView.CHANGEOVER,
                onClick = { view = SensorCycleView.CHANGEOVER },
                label = { Text(stringResource(UiStrings.sensor_cycle_changeover)) }
            )
        }
        FilterChip(
            selected = quarters,
            onClick = { quarters = !quarters },
            label = { Text(stringResource(UiStrings.sensor_quarter_averages)) },
            modifier = Modifier.padding(bottom = 8.dp)
        )

        when {
            data == null                         -> NoData(stringResource(UiStrings.sensor_cycle_no_data))
            view == SensorCycleView.WHOLE_SENSOR -> WholeSensorView(data, formatDate, quarters)
            view == SensorCycleView.AMBULATORY   -> AmbulatoryView(data, formatDate, quarters)
            data.changeover == null              -> NoData(stringResource(UiStrings.sensor_changeover_no_data))
            else                                 -> ChangeoverView(data, data.changeover, formatDate, quarters)
        }

        // Offset in whole sensors: 1 shows the three sensors before the newest, and so on
        NumberInputRow(
            labelRef = UiStrings.sensor_cycle_offset,
            value = offset.toDouble(),
            onValueChange = { onOffsetChange(it.toInt()) },
            valueRange = 0.0..(sensorsAvailable - 1).coerceAtLeast(1).toDouble(),
            step = 1.0,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun NoData(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun WholeSensorView(data: SensorCycleData, formatDate: (Long) -> String, quarters: Boolean) {
    val profileUtil = LocalProfileUtil.current
    val colors = AapsTheme.generalColors
    val baseColor = MaterialTheme.colorScheme.primary
    val averageColor = colors.cycleAverage
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val highColor = MaterialTheme.colorScheme.tertiary
    val quarterColor = MaterialTheme.colorScheme.secondary
    val highestValue = maxOf(data.highMgdl, data.sensors.flatMap { it.binMeansMgdl }.filterNotNull().maxOrNull() ?: data.highMgdl)
    val yAxis = calculateAgpYAxis(highestValue, profileUtil.units)
    val binCount = data.averageMgdl.size
    val days = (data.windowHours + 23) / 24
    val chartDescription = stringResource(UiStrings.sensor_cycle_chart_description)

    Text(
        text = stringResource(UiStrings.sensor_cycle_found, data.sensors.size, data.sensorsAvailable, formatDays(data.windowHours)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = stringResource(UiStrings.sensor_cycle_newest_start, formatDate(data.sensors.first().startTimestamp)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface
    )

    ChartWithAxis(yAxis = yAxis, description = chartDescription) {
        val strokeWidth = 1.dp.toPx()
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(AapsSpacing.medium.toPx(), AapsSpacing.small.toPx()))
        fun x(bin: Int): Float = size.width * (bin + 0.5f) / binCount
        fun y(valueMgdl: Double): Float = yPosition(valueMgdl, yAxis)

        drawGrid(gridColor, yAxis)
        for (day in 0..days) {
            val dayX = size.width * day * 24f / (binCount * data.binHours)
            drawLine(gridColor, Offset(dayX, 0f), Offset(dayX, size.height), strokeWidth = strokeWidth / 2f, pathEffect = dashEffect)
        }
        drawThreshold(data.lowMgdl, colors.bgLow, yAxis, dashEffect)
        drawThreshold(data.highMgdl, highColor, yAxis, dashEffect)

        // Newest sensor = most visible, oldest = faintest, as in the Cycle tab
        data.sensors.forEachIndexed { index, sensor ->
            val alpha = (0.6f - index * 0.4f / data.sensors.size.coerceAtLeast(1)).coerceAtLeast(0.15f)
            drawGappedLine(sensor.binMeansMgdl, ::x, ::y, baseColor.copy(alpha = alpha), Stroke(width = strokeWidth * 1.5f, cap = StrokeCap.Round))
        }
        drawGappedLine(
            data.averageMgdl, ::x, ::y, averageColor,
            Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())))
        )
        if (quarters) drawQuarterAverages(data.quarterAveragesMgdl, data.windowHours.toFloat() / (binCount * data.binHours), ::y, quarterColor)
    }

    // Day labels under the chart: every second day, so 15 days fit a phone
    XLabels(labels = (0..days step 2).map { it.toString() }, positions = (0..days step 2).map { it * 24f / (binCount * data.binHours) })
    if (quarters) QuarterValues(data.quarterAveragesMgdl, quarterColor)

    Legend(
        listOf(
            stringResource(UiStrings.sensor_cycle_lines_legend) to baseColor.copy(alpha = 0.5f),
            stringResource(CoreUiStrings.average) to averageColor,
            profileUtil.fromMgdlToStringWithUnits(data.lowMgdl) to colors.bgLow,
            profileUtil.fromMgdlToStringWithUnits(data.highMgdl) to highColor
        )
    )
}

/**
 * The whole life of the shown sensors as shaded bands (2026-11-01, per explicit request): 10-90% light, 25-75% darker
 * and the median, from every reading of those sensors in each bin, as the AGP draws a day. Smooth where the lines view
 * is spiky, because one odd sensor widens the band instead of drawing its own peak.
 */
@Composable
private fun AmbulatoryView(data: SensorCycleData, formatDate: (Long) -> String, quarters: Boolean) {
    val profileUtil = LocalProfileUtil.current
    val colors = AapsTheme.generalColors
    val wideBandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val narrowBandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
    val medianColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val highColor = MaterialTheme.colorScheme.tertiary
    val quarterColor = MaterialTheme.colorScheme.secondary
    val bands = data.bands
    val highestValue = maxOf(data.highMgdl, bands.maxOfOrNull { it.p90Mgdl } ?: data.highMgdl)
    val yAxis = calculateAgpYAxis(highestValue, profileUtil.units)
    val binCount = data.averageMgdl.size
    val days = (data.windowHours + 23) / 24
    val chartDescription = stringResource(UiStrings.sensor_ambulatory_chart_description)

    Text(
        text = stringResource(UiStrings.sensor_cycle_found, data.sensors.size, data.sensorsAvailable, formatDays(data.windowHours)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = stringResource(UiStrings.sensor_cycle_newest_start, formatDate(data.sensors.first().startTimestamp)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface
    )

    ChartWithAxis(yAxis = yAxis, description = chartDescription) {
        val strokeWidth = AapsSpacing.extraSmall.toPx()
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(AapsSpacing.medium.toPx(), AapsSpacing.small.toPx()))
        fun x(bin: Int): Float = size.width * (bin + 0.5f) / binCount
        fun y(valueMgdl: Double): Float = yPosition(valueMgdl, yAxis)

        drawGrid(gridColor, yAxis)
        for (day in 0..days) {
            val dayX = size.width * day * 24f / (binCount * data.binHours)
            drawLine(gridColor, Offset(dayX, 0f), Offset(dayX, size.height), strokeWidth = strokeWidth / 2f, pathEffect = dashEffect)
        }
        drawThreshold(data.lowMgdl, colors.bgLow, yAxis, dashEffect)
        drawThreshold(data.highMgdl, highColor, yAxis, dashEffect)

        fun drawBand(lower: (SensorBinPercentile) -> Double, upper: (SensorBinPercentile) -> Double, color: Color) {
            if (bands.size < 2) return
            val path = Path()
            bands.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(point.bin), y(upper(point))) else path.lineTo(x(point.bin), y(upper(point)))
            }
            bands.asReversed().forEach { point -> path.lineTo(x(point.bin), y(lower(point))) }
            path.close()
            drawPath(path, color)
        }
        drawBand(SensorBinPercentile::p10Mgdl, SensorBinPercentile::p90Mgdl, wideBandColor)
        drawBand(SensorBinPercentile::p25Mgdl, SensorBinPercentile::p75Mgdl, narrowBandColor)
        drawGappedLine(
            values = bands.associate { it.bin to it.medianMgdl }.let { byBin -> (0 until binCount).map { byBin[it] } },
            x = ::x,
            y = ::y,
            color = medianColor,
            stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        if (quarters) drawQuarterAverages(data.quarterAveragesMgdl, data.windowHours.toFloat() / (binCount * data.binHours), ::y, quarterColor)
    }

    XLabels(labels = (0..days step 2).map { it.toString() }, positions = (0..days step 2).map { it * 24f / (binCount * data.binHours) })
    if (quarters) QuarterValues(data.quarterAveragesMgdl, quarterColor)

    Legend(
        listOf(
            stringResource(UiStrings.agp_percentile_10_90) to wideBandColor,
            stringResource(UiStrings.agp_percentile_25_75) to narrowBandColor,
            stringResource(UiStrings.agp_median) to medianColor,
            profileUtil.fromMgdlToStringWithUnits(data.lowMgdl) to colors.bgLow,
            profileUtil.fromMgdlToStringWithUnits(data.highMgdl) to highColor
        )
    )
}

@Composable
private fun ChangeoverView(data: SensorCycleData, changeover: SensorChangeoverData, formatDate: (Long) -> String, quarters: Boolean) {
    val profileUtil = LocalProfileUtil.current
    val colors = AapsTheme.generalColors
    val wideBandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val narrowBandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
    val medianColor = MaterialTheme.colorScheme.primary
    val changeColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val highColor = MaterialTheme.colorScheme.tertiary
    val quarterColor = MaterialTheme.colorScheme.secondary
    val percentiles = changeover.percentiles
    val hours = changeover.hoursEachSide
    val daysEachSide = hours / 24
    val highestValue = maxOf(data.highMgdl, percentiles.maxOfOrNull { it.p90Mgdl } ?: data.highMgdl)
    val yAxis = calculateAgpYAxis(highestValue, profileUtil.units)
    val chartDescription = stringResource(UiStrings.sensor_changeover_chart_description)

    Text(
        text = stringResource(UiStrings.sensor_changeover_explanation, daysEachSide, changeover.changeTimestamps.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    // Which changes are pooled, newest first, as the lines view names its newest start (2026-11-01, per explicit request)
    Text(
        text = stringResource(UiStrings.sensor_changeover_dates, changeover.changeTimestamps.joinToString(", ") { formatDate(it) }),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface
    )

    ChartWithAxis(yAxis = yAxis, description = chartDescription) {
        val strokeWidth = AapsSpacing.extraSmall.toPx()
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(AapsSpacing.medium.toPx(), AapsSpacing.small.toPx()))
        fun x(hour: Int): Float = size.width * (hour + hours + 0.5f) / (2f * hours)
        fun y(valueMgdl: Double): Float = yPosition(valueMgdl, yAxis)

        drawGrid(gridColor, yAxis)
        for (day in -daysEachSide..daysEachSide) {
            val dayX = size.width * (day + daysEachSide) / (2f * daysEachSide)
            drawLine(gridColor, Offset(dayX, 0f), Offset(dayX, size.height), strokeWidth = strokeWidth / 2f, pathEffect = dashEffect)
        }
        drawThreshold(data.lowMgdl, colors.bgLow, yAxis, dashEffect)
        drawThreshold(data.highMgdl, highColor, yAxis, dashEffect)

        fun drawBand(lower: (ChangeoverPercentile) -> Double, upper: (ChangeoverPercentile) -> Double, color: Color) {
            if (percentiles.size < 2) return
            val path = Path()
            percentiles.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(point.hourFromChange), y(upper(point))) else path.lineTo(x(point.hourFromChange), y(upper(point)))
            }
            percentiles.asReversed().forEach { point -> path.lineTo(x(point.hourFromChange), y(lower(point))) }
            path.close()
            drawPath(path, color)
        }
        drawBand(ChangeoverPercentile::p10Mgdl, ChangeoverPercentile::p90Mgdl, wideBandColor)
        drawBand(ChangeoverPercentile::p25Mgdl, ChangeoverPercentile::p75Mgdl, narrowBandColor)
        drawGappedLine(
            values = percentiles.associate { it.hourFromChange to it.medianMgdl }.let { byHour -> (-hours until hours).map { byHour[it] } },
            x = { index -> x(index - hours) },
            y = ::y,
            color = medianColor,
            stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        // The change itself
        drawLine(changeColor, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = strokeWidth)
        if (quarters) drawQuarterAverages(changeover.quarterAveragesMgdl, 1f, ::y, quarterColor)
    }

    val dayLabels = (-daysEachSide..daysEachSide).toList()
    XLabels(
        labels = dayLabels.map { if (it > 0) "+$it" else it.toString() },
        positions = dayLabels.map { (it + daysEachSide) / (2f * daysEachSide) }
    )
    if (quarters) QuarterValues(changeover.quarterAveragesMgdl, quarterColor)

    Legend(
        listOf(
            stringResource(UiStrings.agp_percentile_10_90) to wideBandColor,
            stringResource(UiStrings.agp_percentile_25_75) to narrowBandColor,
            stringResource(UiStrings.agp_median) to medianColor,
            profileUtil.fromMgdlToStringWithUnits(data.lowMgdl) to colors.bgLow,
            profileUtil.fromMgdlToStringWithUnits(data.highMgdl) to highColor
        )
    )

    // Before and after, as in Nocturne's site change report
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AapsSpacing.small, bottom = AapsSpacing.extraSmall)
    ) {
        Figure(stringResource(UiStrings.sensor_avg_before), changeover.averageBeforeMgdl?.let { profileUtil.fromMgdlToStringWithUnits(it) })
        Figure(stringResource(UiStrings.sensor_avg_after), changeover.averageAfterMgdl?.let { profileUtil.fromMgdlToStringWithUnits(it) })
        Figure(stringResource(UiStrings.sensor_tir_before), changeover.tirBeforePercent?.let { "${it.roundToInt()}%" })
        Figure(stringResource(UiStrings.sensor_tir_after), changeover.tirAfterPercent?.let { "${it.roundToInt()}%" })
    }
}

/** The quarter averages written out under the chart, one per quarter, in the colour of their lines */
@Composable
private fun QuarterValues(averages: List<Double?>, color: Color) {
    val profileUtil = LocalProfileUtil.current
    Row(modifier = Modifier.fillMaxWidth().padding(top = AapsSpacing.small)) {
        averages.forEachIndexed { index, average ->
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(UiStrings.sensor_quarter_label, index + 1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(average?.let { profileUtil.fromMgdlToStringWithUnits(it) } ?: "-", style = MaterialTheme.typography.titleSmall, color = color)
            }
        }
    }
}

@Composable
private fun RowScope.Figure(label: String, value: String?) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "-", style = MaterialTheme.typography.titleSmall)
    }
}

/** The chart canvas with the glucose axis on its right, as in the AGP. */
@Composable
private fun ChartWithAxis(yAxis: AgpYAxis, description: String, onDraw: DrawScope.() -> Unit) {
    val profileUtil = LocalProfileUtil.current
    // Taller than the AGP's (2026-11-01, per explicit request): a sensor's span is days, not one day, and the lines and
    // bands need the room. The spacing round the chart is trimmed instead of the screen growing much.
    // 2/3 in landscape, as the overview graphs (2026-11-01, per explicit request)
    val chartHeight = AapsSpacing.bgCircleSize * 2 * if (isLandscape()) 2f / 3f else 1f
    Text(
        text = profileUtil.units.displayLabel,
        modifier = Modifier.fillMaxWidth().padding(top = AapsSpacing.extraSmall),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(chartHeight)
                .semantics { contentDescription = description },
            onDraw = onDraw
        )
        Column(
            modifier = Modifier.width(axisWidth()).height(chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            yAxis.ticksMgdl.forEach { value ->
                Text(
                    profileUtil.fromMgdlToStringInUnits(value),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Labels under the chart at [positions], each a fraction of the chart width. */
@Composable
private fun XLabels(labels: List<String>, positions: List<Float>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.weight(1f).height(16.dp)) {
            val labelWidth = 28.dp
            labels.zip(positions).forEach { (label, position) ->
                Text(
                    text = label,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(x = maxWidth * position - labelWidth / 2)
                        .width(labelWidth)
                )
            }
        }
        Spacer(modifier = Modifier.width(axisWidth() + AapsSpacing.medium))
    }
}

@Composable
private fun Legend(entries: List<Pair<String, Color>>) {
    FlowRow(
        modifier = Modifier.padding(top = AapsSpacing.small, bottom = AapsSpacing.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
    ) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(AapsSpacing.large), shape = CircleShape, color = color) {}
                Spacer(modifier = Modifier.width(AapsSpacing.small))
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun axisWidth() = AapsSpacing.xxLarge + AapsSpacing.extraLarge

private fun DrawScope.yPosition(valueMgdl: Double, yAxis: AgpYAxis): Float =
    size.height * (1f - ((valueMgdl - yAxis.minimumMgdl) / (yAxis.maximumMgdl - yAxis.minimumMgdl)).toFloat())

private fun DrawScope.drawGrid(color: Color, yAxis: AgpYAxis) {
    repeat(yAxis.ticksMgdl.size) { index ->
        val y = size.height * index / (yAxis.ticksMgdl.size - 1f)
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
    }
}

private fun DrawScope.drawThreshold(valueMgdl: Double, color: Color, yAxis: AgpYAxis, dashEffect: PathEffect) {
    if (valueMgdl !in yAxis.minimumMgdl..yAxis.maximumMgdl) return
    val y = yPosition(valueMgdl, yAxis)
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dashEffect)
}

/** A line through [values] by index, broken where a value is missing, so a gap in the readings shows as a gap. */
/**
 * A flat line at each quarter's average across its quarter of the graph. [spanFraction] is the share of the width the
 * quarters cover: the sensor life does not always fill the last bin.
 */
private fun DrawScope.drawQuarterAverages(averages: List<Double?>, spanFraction: Float, y: (Double) -> Float, color: Color) {
    val quarterWidth = size.width * spanFraction / averages.size.coerceAtLeast(1)
    averages.forEachIndexed { index, average ->
        average ?: return@forEachIndexed
        val level = y(average)
        drawLine(color, Offset(index * quarterWidth, level), Offset((index + 1) * quarterWidth, level), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawGappedLine(values: List<Double?>, x: (Int) -> Float, y: (Double) -> Float, color: Color, stroke: Stroke) {
    val path = Path()
    var penDown = false
    values.forEachIndexed { index, value ->
        if (value == null) {
            penDown = false
        } else {
            if (penDown) path.lineTo(x(index), y(value)) else path.moveTo(x(index), y(value))
            penDown = true
        }
    }
    drawPath(path, color, style = stroke)
}

/** Days per sensor for the header: "10", or "10.5" for the G7. */
private fun formatDays(windowHours: Int): String =
    if (windowHours % 24 == 0) (windowHours / 24).toString() else ((windowHours * 10 / 24) / 10.0).toString()
