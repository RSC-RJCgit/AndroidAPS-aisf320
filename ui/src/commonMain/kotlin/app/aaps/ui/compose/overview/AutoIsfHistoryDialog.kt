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
                    HistoryLine(
                        values = listOf(
                            stringResource(UiStrings.autoisf_history_time),
                            stringResource(UiStrings.autoisf_history_bgl),
                            stringResource(UiStrings.autoisf_history_ukf),
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
                            stringResource(UiStrings.autoisf_history_smb)
                        ),
                        colors = List(14) { MaterialTheme.colorScheme.onSurfaceVariant },
                        bold = true,
                        scroll = horizontal
                    )
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        shown.forEach { row ->
                            HistoryLine(
                                values = listOf(
                                    row.time,
                                    row.glucose,
                                    row.ukf,
                                    row.finalIsf,
                                    row.acceIsf,
                                    row.bgIsf,
                                    row.ppIsf,
                                    row.duraIsf,
                                    row.acceleration,
                                    row.delta,
                                    row.shortDelta,
                                    row.longDelta,
                                    row.iob,
                                    row.smb
                                ),
                                colors = rowColors(row),
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

@Composable
private fun rowColors(row: AutoIsfHistoryRow): List<Color> {
    val glucose = AapsTheme.generalColors.bgInRange
    val insulin = AapsTheme.generalColors.activeInsulinText
    val time = MaterialTheme.colorScheme.onSurface
    return listOf(
        time,
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
        factorColor(row.smbFactor, insulin)
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
