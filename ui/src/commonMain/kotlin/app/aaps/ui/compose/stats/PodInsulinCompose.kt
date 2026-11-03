package app.aaps.ui.compose.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.isLandscape
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlin.math.ceil

/**
 * The Pod tab (2026-11-01, per explicit request): the insulin of each pod, oldest to newest. Bars are the days of each
 * pod, the pod's total under them, and a row per pod below with its days and total. Drawn on a canvas like the
 * Sensor tab, with the same chart height and the same offset in whole pods.
 *
 * @param data null when there is no pod change at [offset]
 * @param podsAvailable pod changes found, which caps the offset
 * @param formatDate formats a pod change for its row
 */
@Composable
fun PodInsulinCompose(
    data: PodInsulinData?,
    podsAvailable: Int,
    offset: Int,
    onOffsetChange: (Int) -> Unit,
    formatDate: (Long) -> String,
    modifier: Modifier = Modifier,
    summary: List<PodInsulin> = emptyList(),
    formatSummaryDate: (Long) -> String = formatDate
) {
    Column(modifier = modifier) {
        if (summary.isNotEmpty()) {
            val decimalFormatter = LocalDecimalFormatter.current
            Text(
                text = summary.joinToString("  ·  ") { pod ->
                    val units = if (pod.days.any { it.units != null }) "${decimalFormatter.to1Decimal(pod.totalUnits)} U" else "—"
                    "${formatSummaryDate(pod.startTimestamp)} $units"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            )
        }
        if (data == null) {
            Text(
                text = stringResource(UiStrings.pod_insulin_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            PodBars(data)
            PodRows(data, formatDate)
        }

        // Offset in whole pods: 1 shows the pods before the newest, and so on
        StatsOffsetStepper(
            label = UiStrings.pod_insulin_offset,
            value = offset,
            max = (podsAvailable - 1).coerceAtLeast(1),
            onValueChange = onOffsetChange
        )
    }
}

@Composable
private fun PodBars(data: PodInsulinData) {
    val decimalFormatter = LocalDecimalFormatter.current
    val colors = AapsTheme.generalColors
    val barColor = MaterialTheme.colorScheme.primary
    val inUseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    val averageColor = colors.cycleAverage
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val ticks = podAxisTicks(data.pods.flatMap { it.days }.maxOfOrNull { it.units ?: 0.0 } ?: 0.0)
    val top = ticks.last()
    // One slot per day, and one empty slot between pods
    val slots = data.pods.sumOf { it.days.size } + data.pods.size - 1
    val chartHeight = AapsSpacing.bgCircleSize * 2 * if (isLandscape()) 2f / 3f else 1f
    val chartDescription = stringResource(UiStrings.pod_insulin_chart_description)

    Text(
        text = stringResource(UiStrings.pod_insulin_found, data.pods.size, data.podsAvailable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = stringResource(
            UiStrings.pod_insulin_averages,
            data.averageTotalUnits?.let { decimalFormatter.to1Decimal(it) } ?: "-",
            data.averageUnitsPerDay?.let { decimalFormatter.to1Decimal(it) } ?: "-"
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = stringResource(CoreUiStrings.insulin_unit_shortname),
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
                .semantics { contentDescription = chartDescription }
        ) {
            val slotWidth = size.width / slots.coerceAtLeast(1)
            fun y(units: Double): Float = size.height * (1f - (units / top).toFloat())

            ticks.forEach { tick -> drawLine(gridColor, Offset(0f, y(tick)), Offset(size.width, y(tick)), strokeWidth = 0.5.dp.toPx()) }
            var slot = 0
            data.pods.forEach { pod ->
                pod.days.forEach { day ->
                    val units = day.units
                    if (units != null && units > 0) {
                        val color = when {
                            day.fromSecondary -> secondaryColor
                            pod.inUse         -> inUseColor
                            else              -> barColor
                        }
                        drawRect(
                            color = color,
                            topLeft = Offset(slot * slotWidth + slotWidth * 0.1f, y(units)),
                            size = Size(slotWidth * 0.8f, size.height - y(units))
                        )
                    }
                    slot++
                }
                slot++ // the gap before the next pod
            }
            data.averageUnitsPerDay?.let { average ->
                drawLine(
                    averageColor, Offset(0f, y(average)), Offset(size.width, y(average)),
                    strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))
                )
            }
        }
        Column(
            modifier = Modifier.width(podAxisWidth()).height(chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            ticks.asReversed().forEach { tick ->
                Text(
                    decimalFormatter.to0Decimal(tick),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Each pod's total under the middle of its bars
    var slot = 0
    val positions = data.pods.map { pod ->
        val middle = (slot + pod.days.size / 2f) / slots.coerceAtLeast(1)
        slot += pod.days.size + 1
        middle
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.weight(1f).height(16.dp)) {
            val labelWidth = 36.dp
            data.pods.zip(positions).forEach { (pod, position) ->
                Text(
                    text = decimalFormatter.to0Decimal(pod.totalUnits),
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
        Spacer(modifier = Modifier.width(podAxisWidth() + AapsSpacing.medium))
    }

    PodLegend(
        buildList {
            add(stringResource(UiStrings.pod_insulin_day_legend) to barColor)
            if (data.pods.any { it.inUse }) add(stringResource(UiStrings.pod_insulin_in_use_legend) to inUseColor)
            if (data.secondaryDays > 0) add(stringResource(UiStrings.pod_insulin_secondary_legend) to secondaryColor)
            add(stringResource(UiStrings.pod_insulin_average_legend) to averageColor)
        }
    )
}

/** A row per pod, oldest first: its change, hours, total and units a day, then its days. */
@Composable
private fun PodRows(data: PodInsulinData, formatDate: (Long) -> String) {
    val decimalFormatter = LocalDecimalFormatter.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = AapsSpacing.small)) {
        data.pods.forEach { pod ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(
                    UiStrings.pod_insulin_row,
                    formatDate(pod.startTimestamp),
                    decimalFormatter.to0Decimal(pod.hours),
                    decimalFormatter.to1Decimal(pod.totalUnits),
                    pod.unitsPerDay?.let { decimalFormatter.to1Decimal(it) } ?: "-"
                ) + if (pod.inUse) " · " + stringResource(UiStrings.pod_insulin_in_use) else "",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = AapsSpacing.extraSmall)
            )
            // A day from the other phone is marked with *
            Text(
                text = pod.days.withIndex().joinToString(" · ") { (index, day) ->
                    "D${index + 1} " + (day.units?.let { decimalFormatter.to1Decimal(it) } ?: "-") + if (day.fromSecondary) "*" else ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AapsSpacing.extraSmall)
            )
        }
    }
}

@Composable
private fun PodLegend(entries: List<Pair<String, Color>>) {
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

private fun podAxisWidth() = AapsSpacing.xxLarge + AapsSpacing.extraLarge

/** 0 up to the highest day, in steps of 10 U, or 20 U when a day passes 60 U, so a phone's axis stays readable. */
internal fun podAxisTicks(highestUnits: Double): List<Double> {
    val step = if (highestUnits > 60.0) 20.0 else 10.0
    val top = (ceil(highestUnits / step) * step).coerceAtLeast(step)
    return generateSequence(0.0) { it + step }.takeWhile { it <= top }.toList()
}
