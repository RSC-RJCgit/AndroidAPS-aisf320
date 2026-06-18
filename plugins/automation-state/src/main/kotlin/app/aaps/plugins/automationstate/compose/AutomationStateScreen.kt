package app.aaps.plugins.automationstate.compose

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
    var editingState by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(viewModel.isEnabled) }

    setToolbarConfig(ToolbarConfig(
        title = "Automation States",
        navigationIcon = @Composable {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = @Composable {
            IconButton(onClick = { showAddStateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add State")
            }
        }
    ))

    val states by viewModel.states.collectAsStateWithLifecycle()
    val refreshTick by viewModel.refreshTick.collectAsStateWithLifecycle()
    if (refreshTick >= 0) Unit // force recomposition

    if (showAddStateDialog) {
        AddStateDialog(
            onDismiss = { showAddStateDialog = false },
            onConfirm = { name, values ->
                viewModel.addState(name, values)
                showAddStateDialog = false
            }
        )
    }

    editingState?.let { stateName ->
        EditStateDialog(
            stateName = stateName,
            currentValues = viewModel.getValues(stateName),
            onDismiss = { editingState = null },
            onConfirm = { values ->
                viewModel.updateStateValues(stateName, values)
                editingState = null
            },
            onDelete = {
                viewModel.deleteState(stateName)
                editingState = null
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (enabled)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (enabled) "Automation States: ENABLED" else "Automation States: DISABLED",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (!enabled) {
                        Text(
                            text = "States are inactive — automations will not trigger",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        viewModel.setEnabled(it)
                    }
                )
            }
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
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp).background(Color(0xFFF5F5F5))) {
                items(states) { (name, current) ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        onClick = { editingState = name },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFD0D0D0))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF212121)
                                )
                                IconButton(onClick = { viewModel.deleteState(name) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
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
                                            selectedContainerColor = Color(0xFF2E7D32),
                                            containerColor = Color(0xFFBDBDBD),
                                            labelColor = Color(0xFF212121),
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
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
                    "Example: name = 'Exercise', values = 'High,Low,Rest'",
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

@Composable
fun EditStateDialog(
    stateName: String,
    currentValues: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDelete: () -> Unit
) {
    var values by remember { mutableStateOf(currentValues.toMutableList()) }
    var newValue by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete State") },
            text = { Text("Delete '$stateName' and all its values?") },
            confirmButton = {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit: $stateName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (values.isEmpty()) {
                    Text(
                        "No values - add one below",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    values.toList().forEach { value ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    values = values.toMutableList().also { it.remove(value) }
                                    error = ""
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it; error = "" },
                        label = { Text("New value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val v = newValue.trim()
                            when {
                                v.isEmpty() -> error = "Enter a value"
                                values.contains(v) -> error = "Already exists"
                                else -> {
                                    values = values.toMutableList().also { it.add(v) }
                                    newValue = ""
                                }
                            }
                        }
                    ) { Text("Add") }
                }
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text("Delete this state", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (values.isEmpty()) error = "At least one value required"
                else onConfirm(values.toList())
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}