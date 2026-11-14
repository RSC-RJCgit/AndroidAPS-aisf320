package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.data.model.AIV
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.chips.ChipsViewModel

/**
 * The AutoISF split of the newest loop run (2026-11-15, per explicit request, after blaqone's AutoISF chip): the five
 * ISF factors and iobTH in a grid. Opened by a tap on AIV in the oAPS row; a long press there opens the AIV table.
 */
@Composable
fun AutoIsfSplitDialog(viewModel: ChipsViewModel) {
    if (!viewModel.autoIsfSplitOpen) return
    val aiv = viewModel.autoIsfSplit
    val dateUtil = LocalDateUtil.current
    AlertDialog(
        onDismissRequest = viewModel::closeAutoIsfSplit,
        title = {
            Text(
                if (aiv == null) stringResource(UiStrings.autoisf_split_title)
                else stringResource(UiStrings.autoisf_split_title) + "  " + dateUtil.timeString(aiv.timestamp)
            )
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
    val cells = listOf(
        stringResource(CoreUiStrings.acce_isf_shortname) to factor(aiv.acceIsf),
        stringResource(CoreUiStrings.bg_isf_shortname) to factor(aiv.bgIsf),
        stringResource(CoreUiStrings.pp_isf_shortname) to factor(aiv.ppIsf),
        stringResource(CoreUiStrings.dura_isf_shortname) to factor(aiv.duraIsf),
        stringResource(CoreUiStrings.final_isf_shortname) to factor(aiv.finalIsf),
        stringResource(CoreUiStrings.iob_threshold_shortname) to (if (aiv.iobThEffective > 0.0) units(aiv.iobThEffective) else "-")
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text(stringResource(UiStrings.autoisf_split_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun factor(value: Double): String = (kotlin.math.round(value * 100) / 100.0).toString()
private fun units(value: Double): String = (kotlin.math.round(value * 100) / 100.0).toString() + " U"
