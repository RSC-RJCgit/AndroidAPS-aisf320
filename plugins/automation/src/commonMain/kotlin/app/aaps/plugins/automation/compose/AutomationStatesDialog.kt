package app.aaps.plugins.automation.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.automation.AutomationStateEntry
import app.aaps.plugins.automation.AutomationStrings

/** What the States dialog may change. Null makes the dialog read only (a client). */
class AutomationStatesEditor(
    val setCurrent: (name: String, value: String) -> Unit,
    val setValues: (name: String, values: List<String>) -> Unit,
    val delete: (name: String) -> Unit
)

/**
 * The stored automation states. Each state shows its name and its values; the current value is selected.
 *
 * With an [editor] (2026-10-19, per explicit request, the loop phone): tap a value to make it the current one, tap the
 * name to change its values or delete it, and "Add" declares a new state. Without one it is read only, as before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationStatesDialog(
    rows: List<AutomationStateEntry>,
    editor: AutomationStatesEditor?,
    onClose: () -> Unit
) {
    var editing by remember { mutableStateOf<AutomationStateEntry?>(null) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(AutomationStrings.automation_states)) },
        text = {
            if (rows.isEmpty()) {
                Text(stringResource(AutomationStrings.automation_states_empty))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
                ) {
                    for (row in rows) {
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = if (editor != null) Modifier.fillMaxWidth().clickable { editing = row } else Modifier.fillMaxWidth()
                        )
                        if (editor == null) {
                            Text(if (row.current.isEmpty()) "--" else row.current)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                                for (value in row.values) {
                                    val selected = value == row.current
                                    FilterChip(
                                        selected = selected,
                                        onClick = { editor.setCurrent(row.name, value) },
                                        label = { Text(value, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                                        // As in 3.2.1 (2026-10-20, per explicit request): the current value solid green, the
                                        // others plain grey. The default chip only tinted the selected one.
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            selectedContainerColor = AapsTheme.generalColors.statusNormal,
                                            // Black on the green, the same as the green badges in the navigation bar
                                            selectedLabelColor = Color.Black,
                                            selectedLeadingIconColor = Color.Black
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(CoreUiStrings.close))
            }
        },
        dismissButton = if (editor != null) {
            { TextButton(onClick = { adding = true }) { Text(stringResource(AutomationStrings.add_automation_state)) } }
        } else null
    )

    if (editor == null) return

    if (adding) {
        AddStateDialog(
            existing = rows.map { it.name },
            onDismiss = { adding = false },
            onConfirm = { name ->
                adding = false
                editing = AutomationStateEntry(name, "", emptyList())
            }
        )
    }

    editing?.let { entry ->
        StateValuesDialog(
            entry = entry,
            isNew = rows.none { it.name == entry.name },
            onDismiss = { editing = null },
            onSave = { values ->
                editor.setValues(entry.name, values)
                editing = null
            },
            onDelete = {
                editor.delete(entry.name)
                editing = null
            }
        )
    }
}

@Composable
private fun AddStateDialog(existing: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AutomationStrings.add_automation_state)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(AutomationStrings.state_name_label)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty() && trimmed !in existing) {
                Text(stringResource(CoreUiStrings.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } }
    )
}

@Composable
private fun StateValuesDialog(
    entry: AutomationStateEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onDelete: () -> Unit
) {
    val values = remember(entry.name) { mutableStateListOf<String>().apply { addAll(entry.values) } }
    var newValue by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AutomationStrings.automation_state_values_title, entry.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                values.forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(value, modifier = Modifier.weight(1f))
                        IconButton(onClick = { values.removeAt(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(CoreUiStrings.remove))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text(stringResource(AutomationStrings.enter_state_value)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val v = newValue.trim()
                            if (v.isNotEmpty() && v !in values) values.add(v)
                            newValue = ""
                        },
                        enabled = newValue.isNotBlank()
                    ) { Text(stringResource(CoreUiStrings.add)) }
                }
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(AutomationStrings.delete_automation_state), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(values.toList()) }, enabled = values.isNotEmpty()) {
                Text(stringResource(CoreUiStrings.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } }
    )
}
