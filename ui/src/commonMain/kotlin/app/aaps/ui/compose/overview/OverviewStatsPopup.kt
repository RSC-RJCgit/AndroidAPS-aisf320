package app.aaps.ui.compose.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.time.T
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.graphs.LocalTopBarPillHost
import app.aaps.ui.compose.stats.AgpStatsData

data class OverviewStatsSummary(
    val endTime: Long,
    val days: Int,
    val data: AgpStatsData? = null,
    val failed: Boolean = false
)

/**
 * Installs only this overview's open summary and removes it when the overview leaves the screen.
 *
 * @param onOpenStats a tap on the ring: Statistics on its Glucose tab for these days, as LoopFollow does
 */
@Composable
fun OverviewStatsPopupHost(graphViewModel: GraphViewModel, onOpenStats: (days: Int) -> Unit) {
    val host = LocalTopBarPillHost.current ?: return
    val summary by graphViewModel.statsSummary.collectAsStateWithLifecycle()
    summary?.let { current ->
        DisposableEffect(host, current, graphViewModel) {
            val content: @Composable () -> Unit = {
                OverviewStatsPopup(current, graphViewModel::openStatsSummary, graphViewModel::closeStatsSummary) {
                    graphViewModel.closeStatsSummary()
                    onOpenStats(current.days)
                }
            }
            host.summaryContent = content
            onDispose { if (host.summaryContent === content) host.summaryContent = null }
        }
    }
}

@Composable
private fun OverviewStatsPopup(summary: OverviewStatsSummary, onSelectDays: (Int) -> Unit, onClose: () -> Unit, onOpenStats: () -> Unit) {
    val dateUtil = LocalDateUtil.current
    Surface(
        shape = RoundedCornerShape(AapsSpacing.extraLarge),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = AapsSpacing.extraSmall,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(CoreUiStrings.statistics), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                listOf(7, 30).forEach { days ->
                    TextButton(onClick = { onSelectDays(days) }, enabled = summary.days != days) {
                        Text(stringResource(UiStrings.agp_days, days))
                    }
                }
                TextButton(onClick = onClose) { Text(stringResource(CoreUiStrings.close)) }
            }
            Text(
                dateUtil.dateAndTimeRangeString(summary.endTime - T.days(summary.days.toLong()).msecs(), summary.endTime),
                style = MaterialTheme.typography.labelSmall
            )
            val data = summary.data
            when {
                summary.failed -> Text(stringResource(UiStrings.agp_load_failed), color = MaterialTheme.colorScheme.error)
                data == null -> CircularProgressIndicator(modifier = Modifier.size(AapsSpacing.chipIconSize))
                data.readingCount == 0 -> Text(stringResource(UiStrings.agp_no_data), style = MaterialTheme.typography.bodySmall)
                else -> OverviewStatsValues(data, onOpenStats)
            }
        }
    }
}

@Composable
private fun OverviewStatsValues(data: AgpStatsData, onOpenStats: () -> Unit) {
    val profileUtil = LocalProfileUtil.current
    val colors = AapsTheme.generalColors
    val low = data.tir.veryLow + data.tir.low
    val high = data.tir.high + data.tir.veryHigh
    val unit = stringResource(if (profileUtil.units == GlucoseUnit.MGDL) CoreUiStrings.mgdl else CoreUiStrings.mmol)
    val description = stringResource(UiStrings.agp_tir_chart)
    val openLabel = stringResource(CoreUiStrings.statistics)
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            modifier = Modifier
                .size(AapsSpacing.navigationRailWidth)
                .clip(CircleShape)
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpenStats)
                .semantics { contentDescription = description }
        ) {
            var start = -90f
            listOf(low to colors.bgLow, data.tir.inRange to colors.bgInRange, high to colors.bgHigh).forEach { (percentage, color) ->
                val sweep = (percentage.coerceIn(0.0, 100.0) * 3.6).toFloat()
                drawArc(color = color, startAngle = start, sweepAngle = sweep, useCenter = true)
                start += sweep
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                StatsValue(stringResource(CoreUiStrings.low), stringResource(UiStrings.agp_percent, low), colors.bgLow, Modifier.weight(1f))
                StatsValue(stringResource(CoreUiStrings.in_range), stringResource(UiStrings.agp_percent, data.tir.inRange), colors.bgInRange, Modifier.weight(1f))
                StatsValue(stringResource(CoreUiStrings.high), stringResource(UiStrings.agp_percent, high), colors.bgHigh, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                StatsValue(stringResource(UiStrings.agp_average), profileUtil.fromMgdlToStringInUnits(data.averageMgdl), modifier = Modifier.weight(1f))
                StatsValue(stringResource(UiStrings.agp_gmi), stringResource(UiStrings.agp_percent, data.gmiPercent), modifier = Modifier.weight(1f))
                StatsValue(stringResource(UiStrings.agp_cv), stringResource(UiStrings.agp_percent, data.coefficientOfVariation), modifier = Modifier.weight(1f))
            }
        }
    }
    Text(
        stringResource(UiStrings.agp_range, profileUtil.fromMgdlToStringInUnits(data.lowMgdl), profileUtil.fromMgdlToStringInUnits(data.highMgdl)) + " " + unit,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatsValue(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
