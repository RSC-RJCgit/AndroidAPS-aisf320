package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.data.model.AIV
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.chips.ChipsViewModel

/** iobTH line colour, the same as SeriesColors.iobTh on the graphs. */
private val IOB_TH_COLOR = Color(0xFF00FFFF)

/**
 * The AutoISF split of the newest loop run (2026-11-15, per explicit request, after blaqone's AutoISF chip): the five
 * ISF factors and iobTH as tiles, in the style of blaqone's AutoISF card. Each value is in its own graph line colour
 * (ACCE, BG, PP, DURA and FINAL as on G2/G3), and final and iobTH are repeated in the title. Opened by a tap on AIV in
 * the oAPS row; a long press there opens the AIV table.
 */
@Composable
fun AutoIsfSplitDialog(viewModel: ChipsViewModel) {
    if (!viewModel.autoIsfSplitOpen) return
    val aiv = viewModel.autoIsfSplit
    val dateUtil = LocalDateUtil.current
    AlertDialog(
        onDismissRequest = viewModel::closeAutoIsfSplit,
        title = {
            Column {
                Text(
                    if (aiv == null) stringResource(UiStrings.autoisf_split_title)
                    else stringResource(UiStrings.autoisf_split_title) + "  " + dateUtil.timeString(aiv.timestamp)
                )
                if (aiv != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(CoreUiStrings.final_isf_shortname) + " " + factor(aiv.finalIsf),
                            style = MaterialTheme.typography.titleMedium,
                            color = AapsTheme.generalColors.finalIsf
                        )
                        Text(
                            stringResource(CoreUiStrings.iob_threshold_shortname) + " " + iobTh(aiv),
                            style = MaterialTheme.typography.titleMedium,
                            color = IOB_TH_COLOR
                        )
                    }
                }
            }
        },
        text = {
            if (aiv == null) Text(stringResource(UiStrings.autoisf_split_none))
            else AutoIsfSplitGrid(aiv)
        },
        confirmButton = {
            TextButton(onClick = viewModel::closeAutoIsfSplit) { Text(stringResource(CoreUiStrings.ok)) }
        }
    )
}

@Composable
private fun AutoIsfSplitGrid(aiv: AIV) {
    val colors = AapsTheme.generalColors
    val cells = listOf(
        Triple(stringResource(CoreUiStrings.acce_isf_shortname), factor(aiv.acceIsf), colors.acceIsf),
        Triple(stringResource(CoreUiStrings.bg_isf_shortname), factor(aiv.bgIsf), colors.bgIsf),
        Triple(stringResource(CoreUiStrings.pp_isf_shortname), factor(aiv.ppIsf), colors.ppIsf),
        Triple(stringResource(CoreUiStrings.dura_isf_shortname), factor(aiv.duraIsf), colors.duraIsf),
        Triple(stringResource(CoreUiStrings.final_isf_shortname), factor(aiv.finalIsf), colors.finalIsf),
        Triple(stringResource(CoreUiStrings.iob_threshold_shortname), iobTh(aiv), IOB_TH_COLOR)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value, color) ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
                        }
                    }
                }
            }
        }
        Text(stringResource(UiStrings.autoisf_split_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun iobTh(aiv: AIV): String = if (aiv.iobThEffective > 0.0) units(aiv.iobThEffective) else "-"
private fun factor(value: Double): String = (kotlin.math.round(value * 100) / 100.0).toString()
private fun units(value: Double): String = (kotlin.math.round(value * 100) / 100.0).toString() + " U"
