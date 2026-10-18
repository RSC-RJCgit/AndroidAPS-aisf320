package app.aaps.plugins.automation.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.automation.AutomationStrings

/**
 * AutoISF 3.2.1 automation states: each state with its values. Tap a value to make it the current one. Tap the
 * state name to change its values or delete it. [addRequested] opens the "add state" dialog (the toolbar "+").
 * [storedStates] is the stored JSON of the current values: when an automation sets a state, it changes and the list
 * is drawn again, so the selected chip follows at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationStatesScreen(
    automationState: AutomationStateInterface,
    storedStates: String,
    addRequested: Boolean,
    onAddHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The service is not observable, so a counter redraws the list after each change made here
    var tick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_EXPRESSION") tick
    @Suppress("UNUSED_EXPRESSION") storedStates
    val states = automationState.getAllStates()

    if (states.isEmpty()) {
        Text(
            text = stringResource(AutomationStrings.no_automation_states_screen),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp)
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(states, key = { it.first }) { (name, current) ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().clickable { editing = name }
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            automationState.getStateValues(name).forEach { value ->
                                FilterChip(
                                    selected = value == current,
                                    onClick = {
                                        automationState.setState(name, value)
                                        tick++
                                    },
                                    label = { Text(value) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (addRequested) {
        AddStateDialog(
            existing = states.map { it.first },
            onDismiss = onAddHandled,
            onConfirm = { name ->
                onAddHandled()
                editing = name
            }
        )
    }

    editing?.let { name ->
        StateValuesDialog(
            name = name,
            initialValues = automationState.getStateValues(name),
            isNew = !automationState.hasStateValues(name),
            onDismiss = { editing = null },
            onSave = { values ->
                automationState.setStateValues(name, values)
                editing = null
                tick++
            },
            onDelete = {
                automationState.deleteState(name)
                editing = null
                tick++
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
    name: String,
    initialValues: List<String>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onDelete: () -> Unit
) {
    val values = remember(name) { mutableStateListOf<String>().apply { addAll(initialValues) } }
    var newValue by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(AutomationStrings.automation_state_values_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
