package app.aaps.ui.compose.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.chips.SensitivityChip
import app.aaps.ui.compose.overview.chips.SensitivityUiState
import kotlinx.coroutines.launch

@Composable
fun SensitivityChipBlock(
    state: SensitivityUiState,
    onLoadAutoIsfHistory: suspend () -> List<AutoIsfHistoryRow> = { emptyList() },
    modifier: Modifier = Modifier
) {
    if (state.asText.isEmpty() && state.isfFrom.isEmpty()) return

    var showSensitivityDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var historyRows by remember { mutableStateOf<List<AutoIsfHistoryRow>>(emptyList()) }
    val scope = rememberCoroutineScope()
    SensitivityChip(
        state = state,
        onClick = { if (state.dialogText.isNotEmpty()) showSensitivityDialog = true },
        onLongClick = if (state.autoIsfHistory) {
            {
                scope.launch {
                    historyRows = onLoadAutoIsfHistory()
                    showHistory = true
                }
            }
        } else {
            null
        },
        modifier = modifier
    )
    if (showHistory) {
        AutoIsfHistoryDialog(
            rows = historyRows,
            onDismiss = { showHistory = false }
        )
    }
    if (showSensitivityDialog) {
        OkCancelDialog(
            title = stringResource(CoreUiStrings.sensitivity),
            message = state.dialogText,
            onConfirm = { showSensitivityDialog = false },
            onDismiss = { showSensitivityDialog = false }
        )
    }
}
