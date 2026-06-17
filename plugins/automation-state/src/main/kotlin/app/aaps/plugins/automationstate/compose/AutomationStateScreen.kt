package app.aaps.plugins.automationstate.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.ToolbarConfig

@Composable
fun AutomationStateScreen(
    viewModel: AutomationStateViewModel,
    setToolbarConfig: (ToolbarConfig) -> Unit,
    onNavigateBack: () -> Unit
) {
    var showAddStateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        setToolbarConfig(ToolbarConfig(
            title = "Automation States",
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { showAddStateDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add State")
                }
            }
        ))
    }

    val states by viewModel.states.collectAsStateWithLifecycle()

    if (showAddStateDialog) {
        AddStateDialog(
            onDismiss = { showAddStateDialog = false },
            onConfirm = { name, values ->
                viewModel.addState(name, values)
                showAddStateDialog = false
            }
        )
    }

    if (states.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No automation states defined.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { showAddStateDialog = true }) {
                    Text("Add State")
                }
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(states) { (name, current) ->
            Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = name, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { viewModel.deleteState(name) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        viewModel.getValues(name).forEach { value ->
                            FilterChip(
                                selected = value == current,
                                onClick = { viewModel.setState(name, value) },
                                label = { Text(value) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddStateDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit
) {
    var stateName by remember { mutableStateOf("") }
    var valuesText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Automation State") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = stateName,
                    onValueChange = { stateName = it; error = "" },
                    label = { Text("State name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = valuesText,
                    onValueChange = { valuesText = it; error = "" },
                    label = { Text("Values (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Example: state name = 'Exercise', values = 'High,Low,Rest'",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val name = stateName.trim()
                val values = valuesText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                when {
                    name.isEmpty() -> error = "State name required"
                    values.isEmpty() -> error = "At least one value required"
                    else -> onConfirm(name, values)
                }
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
