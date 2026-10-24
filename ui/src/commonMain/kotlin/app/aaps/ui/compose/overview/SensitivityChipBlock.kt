package app.aaps.ui.compose.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.overview.chips.SensitivityChip
import app.aaps.ui.compose.overview.chips.SensitivityUiState

@Composable
fun SensitivityChipBlock(
    state: SensitivityUiState,
    onOpenAutoIsfHistory: () -> Unit = {},
    onDoubleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (state.asText.isEmpty() && state.isfFrom.isEmpty() && !state.autoIsfHistory) return

    var showSensitivityDialog by remember { mutableStateOf(false) }
    val showSensitivityText = { if (state.dialogText.isNotEmpty()) showSensitivityDialog = true }
    // 2026-10-24, per explicit request, on every phone and the same as v3: a tap opens the AutoISF table (which always also
    // exports and uploads, see openAutoIsfHistory), a long press shows the sensitivity text. Without AutoISF rows there is no
    // table, and a tap shows the sensitivity text as before.
    SensitivityChip(
        state = state,
        onClick = if (state.autoIsfHistory) {
            { onOpenAutoIsfHistory() }
        } else showSensitivityText,
        onLongClick = if (state.autoIsfHistory) showSensitivityText else null,
        onDoubleClick = onDoubleClick,
        modifier = modifier
    )
    if (showSensitivityDialog) {
        OkCancelDialog(
            title = stringResource(CoreUiStrings.sensitivity),
            message = state.dialogText,
            onConfirm = { showSensitivityDialog = false },
            onDismiss = { showSensitivityDialog = false }
        )
    }
}
