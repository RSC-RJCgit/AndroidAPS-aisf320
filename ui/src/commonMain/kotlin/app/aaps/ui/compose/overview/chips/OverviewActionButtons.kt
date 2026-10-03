package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

data class OverviewAction(
    val title: String,
    val mmol: Double,
)

@Composable
fun OverviewActionButtons(
    actions: List<OverviewAction>,
    onAction: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<OverviewAction?>(null) }
    actions.forEach { action ->
        TextButton(
            onClick = { pending = action },
            modifier = modifier.fillMaxWidth(),
        ) {
            Text(action.title)
        }
    }
    val asked = pending
    if (asked != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(asked.title) },
            text = { Text(stringResource(UiStrings.overview_action_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onAction(asked.mmol)
                }) { Text(stringResource(CoreUiStrings.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(CoreUiStrings.cancel)) }
            },
        )
    }
}
