package app.aaps.plugins.automationstate.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.ToolbarConfig

@Composable
fun AutomationStateScreen(
    viewModel: AutomationStateViewModel,
    setToolbarConfig: (ToolbarConfig) -> Unit,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(Unit) {
        setToolbarConfig(ToolbarConfig(title = "Automation States", navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }, actions = {}))
        viewModel.refresh()
    }

    val states by viewModel.states.collectAsState()

    if (!viewModel.isEnabled) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Automation States are disabled. Enable in plugin settings.")
        }
        return
    }

    if (states.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("No automation states defined.")
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(states) { (name, current) ->
            Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = name, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewModel.getValues(name).forEach { value ->
                            FilterChip(
                                selected = value == current,
                                onClick = { viewModel.setState(name, value) },
                                label = { Text(value) }
                            )
                        }
                    }
                }
            }
        }
    }
}

