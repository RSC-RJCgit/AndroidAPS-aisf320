package app.aaps.ui.compose.overview

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

private val historyColumnWidth = 76.dp

/**
 * Last 6 hours of AutoISF loop rows. A long press on the sensitivity chip opens this.
 * Only columns that this app stores are shown.
 */
@Composable
fun AutoIsfHistoryDialog(
    rows: List<AutoIsfHistoryRow>,
    onDismiss: () -> Unit
) {
    var smbOnly by remember { mutableStateOf(false) }
    val shown = if (smbOnly) rows.filter { it.hasSmb } else rows
    val horizontal = rememberScrollState()
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(
                modifier = Modifier.padding(AapsTheme.spacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(AapsTheme.spacing.medium)
            ) {
                Text(
                    text = stringResource(UiStrings.autoisf_history_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { smbOnly = !smbOnly }) {
                        Text(
                            stringResource(
                                if (smbOnly) UiStrings.autoisf_history_all else UiStrings.autoisf_history_smb_only
                            )
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(CoreUiStrings.close))
                    }
                }
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (rows.isEmpty()) UiStrings.autoisf_history_empty else UiStrings.autoisf_history_no_smb
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant
                    val vertical = rememberScrollState()
                    Column(modifier = Modifier.weight(1f).verticalScroll(vertical)) {
                        Row {
                            HistoryCell(stringResource(UiStrings.autoisf_history_time), headerColor, bold = true)
                            HistoryLine(
                                values = historyHeaders(),
                                colors = List(historyHeaders().size) { headerColor },
                                bold = true,
                                scroll = horizontal
                            )
                        }
                        shown.forEach { row ->
                            val colors = rowColors(row)
                            Row {
                                HistoryCell(row.time, colors.first(), bold = false)
                                HistoryLine(
                                    values = row.scrollingCells(),
                                    colors = colors.drop(1),
                                    bold = false,
                                    scroll = horizontal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun historyHeaders(): List<String> = listOf(
    stringResource(UiStrings.autoisf_history_bgl),
    stringResource(UiStrings.autoisf_history_ukf),
    stringResource(UiStrings.autoisf_history_ukf_delta_5),
    stringResource(UiStrings.autoisf_history_ukf_delta_15),
    stringResource(UiStrings.autoisf_history_final),
    stringResource(UiStrings.autoisf_history_acce),
    stringResource(UiStrings.autoisf_history_bg),
    stringResource(UiStrings.autoisf_history_pp),
    stringResource(UiStrings.autoisf_history_dura),
    stringResource(UiStrings.autoisf_history_accel),
    stringResource(UiStrings.autoisf_history_delta),
    stringResource(UiStrings.autoisf_history_short_delta),
    stringResource(UiStrings.autoisf_history_long_delta),
    stringResource(UiStrings.autoisf_history_iob),
    stringResource(UiStrings.autoisf_history_iob_th),
    stringResource(UiStrings.autoisf_history_smb),
    stringResource(UiStrings.autoisf_history_target),
    stringResource(UiStrings.autoisf_history_uam),
    stringResource(UiStrings.autoisf_history_smb_ratio),
    stringResource(UiStrings.autoisf_history_acce_weight),
    stringResource(UiStrings.autoisf_history_pp_weight),
    stringResource(UiStrings.autoisf_history_slope),
    stringResource(UiStrings.autoisf_history_cob),
    stringResource(UiStrings.autoisf_history_basal),
    stringResource(UiStrings.autoisf_history_note),
    stringResource(UiStrings.autoisf_history_steps_5),
    stringResource(UiStrings.autoisf_history_steps_15),
    stringResource(UiStrings.autoisf_history_steps_30),
    stringResource(UiStrings.autoisf_history_steps_60),
    stringResource(UiStrings.autoisf_history_steps_180)
)

private fun AutoIsfHistoryRow.scrollingCells(): List<String> = listOf(
    glucose, ukf, ukfDelta5, ukfDelta15, finalIsf, acceIsf, bgIsf, ppIsf, duraIsf,
    acceleration, delta, shortDelta, longDelta, iob, iobTh, smb,
    target, uam, smbRatio, acceWeight, ppWeight, slope, cob, basal, note,
    steps5, steps15, steps30, steps60, steps180
)

@Composable
private fun rowColors(row: AutoIsfHistoryRow): List<Color> {
    val glucose = AapsTheme.generalColors.bgInRange
    val insulin = AapsTheme.generalColors.activeInsulinText
    val time = MaterialTheme.colorScheme.onSurface
    return listOf(
        time,
        glucose,
        glucose,
        glucose,
        glucose,
        factorColor(row.finalFactor, AapsTheme.generalColors.finalIsf),
        AapsTheme.generalColors.acceIsf,
        AapsTheme.generalColors.bgIsf,
        AapsTheme.generalColors.ppIsf,
        AapsTheme.generalColors.duraIsf,
        glucose,
        glucose,
        glucose,
        glucose,
        insulin,
        insulin,
        factorColor(row.smbFactor, insulin),
        glucose,
        insulin,
        insulin,
        insulin,
        insulin,
        insulin,
        insulin,
        insulin,
        time,
        time,
        time,
        time,
        time,
        time
    )
}

@Composable
private fun factorColor(factor: AutoIsfFactor, fallback: Color): Color = when (factor) {
    AutoIsfFactor.ACCE -> AapsTheme.generalColors.acceIsf
    AutoIsfFactor.BG -> AapsTheme.generalColors.bgIsf
    AutoIsfFactor.PP -> AapsTheme.generalColors.ppIsf
    AutoIsfFactor.DURA -> AapsTheme.generalColors.duraIsf
    AutoIsfFactor.NONE -> fallback
}

@Composable
private fun HistoryCell(value: String, color: Color, bold: Boolean) {
    Text(
        text = value,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .width(historyColumnWidth)
            .padding(vertical = AapsTheme.spacing.small)
    )
}

@Composable
private fun HistoryLine(
    values: List<String>,
    colors: List<Color>,
    bold: Boolean,
    scroll: ScrollState
) {
    Row(modifier = Modifier.horizontalScroll(scroll)) {
        values.forEachIndexed { index, value ->
            Text(
                text = value,
                color = colors[index],
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .width(historyColumnWidth)
                    .padding(vertical = AapsTheme.spacing.small)
            )
        }
    }
}
