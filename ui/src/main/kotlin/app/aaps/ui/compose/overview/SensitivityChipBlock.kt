package app.aaps.ui.compose.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.navigation.ElementType
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.ui.compose.overview.chips.SensitivityChip
import app.aaps.ui.compose.overview.chips.SensitivityUiState

@Composable
fun SensitivityChipBlock(
    state: SensitivityUiState,
    onNavigate: (NavigationRequest) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (state.asText.isEmpty() && state.isfFrom.isEmpty()) return

    var showSensitivityDialog by remember { mutableStateOf(false) }
    SensitivityChip(
        state = state,
        onClick = { if (state.dialogText.isNotEmpty()) showSensitivityDialog = true },
        // TODO: gate on AutoISF being the active algorithm once it's registered as a selectable
        // APS algorithm on this fork — unconditional for now since there's nothing to gate against yet.
        onLongPress = { onNavigate(NavigationRequest.Element(ElementType.AUTOISF_HISTORY)) },
        modifier = modifier
    )
    if (showSensitivityDialog) {
        OkCancelDialog(
            title = stringResource(app.aaps.core.ui.R.string.sensitivity),
            message = state.dialogText,
            onConfirm = { showSensitivityDialog = false },
            onDismiss = { showSensitivityDialog = false }
        )
    }
}
