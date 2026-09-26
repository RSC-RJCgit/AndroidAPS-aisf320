package app.aaps.ui.compose.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * Review of native automations whose names are close to a coded AutoISF name.
 * Every box starts unchecked. OK saves the choices. Cancel saves nothing.
 */
@Composable
fun NativeAutomationReviewDialog(
    titles: List<String>,
    onSave: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit,
) {
    val checked = remember(titles) {
        mutableStateMapOf<String, Boolean>().apply {
            titles.forEach { put(it, false) }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(
                modifier = Modifier.padding(AapsTheme.spacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(AapsTheme.spacing.medium)
            ) {
                Text(text = stringResource(UiStrings.review_native_automations), style = MaterialTheme.typography.titleLarge)
                Text(text = stringResource(UiStrings.review_native_automations_body), style = MaterialTheme.typography.bodyMedium)
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    titles.forEach { title ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked[title] == true,
                                onCheckedChange = { checked[title] = it }
                            )
                            Text(text = title, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) }
                    TextButton(onClick = { onSave(titles.associateWith { checked[it] == true }) }) {
                        Text(stringResource(CoreUiStrings.ok))
                    }
                }
            }
        }
    }
}
