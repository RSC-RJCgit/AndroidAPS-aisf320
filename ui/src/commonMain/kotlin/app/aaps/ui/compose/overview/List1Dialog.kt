package app.aaps.ui.compose.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.chips.CodedProfileRole
import app.aaps.ui.compose.overview.chips.List1Row

@Composable
fun List1Dialog(viewModel: ChipsViewModel) {
    val rows = remember(viewModel.list1Generation, viewModel.list1Open) { viewModel.list1Rows() }
    var pickProfiles by remember { mutableStateOf(false) }
    DirectListDialog(
        open = viewModel.list1Open,
        title = "Direct AutoISF settings",
        rows = rows,
        onDismiss = viewModel::closeList1,
        onApply = viewModel::applyList1,
        queuedText = viewModel.queuedRelayText(),
        onPickProfiles = { pickProfiles = true },
    )
    if (pickProfiles) {
        CodedProfileDialog(viewModel, onDismiss = { pickProfiles = false })
    }
}

@Composable
fun List2Dialog(viewModel: ChipsViewModel) {
    val rows = remember(viewModel.list2Generation, viewModel.list2Open) { viewModel.list2Rows() }
    DirectListDialog(
        open = viewModel.list2Open,
        title = "Direct actions",
        rows = rows,
        onDismiss = viewModel::closeList2,
        onApply = viewModel::applyList1,
        queuedText = viewModel.queuedRelayText(),
    )
}

@Composable
private fun DirectListDialog(
    open: Boolean,
    title: String,
    rows: List<List1Row>,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit,
    queuedText: String? = null,
    onPickProfiles: () -> Unit = {},
) {
    if (!open) return
    var picked by remember { mutableStateOf<List1Row?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Codes sent from a client that wait for a running TT to end (2026-10-08).
                if (queuedText != null) Text(queuedText)
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(rows, key = { it.label }) { row ->
                        TextButton(onClick = {
                            if (row.pickProfiles) onPickProfiles() else picked = row
                        }) {
                            Column {
                                Text(row.label)
                                Text("Current: ${row.current}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
    val row = picked
    if (row != null) {
        val up = row.upMmol
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(row.label) },
            text = { Text("Current: ${row.current}") },
            confirmButton = {
                if (row.readOnly) {
                    TextButton(onClick = { picked = null }) { Text("Close") }
                } else if (up == null) {
                    TextButton(onClick = {
                        onApply(row.downMmol)
                        picked = null
                    }) { Text("OK") }
                } else {
                    Row {
                        TextButton(onClick = {
                            onApply(row.downMmol)
                            picked = null
                        }) { Text("Down") }
                        TextButton(onClick = {
                            onApply(up)
                            picked = null
                        }) { Text("Up") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { picked = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CodedProfileDialog(viewModel: ChipsViewModel, onDismiss: () -> Unit) {
    var role by remember { mutableStateOf<CodedProfileRole?>(null) }
    var refuse by remember { mutableStateOf("") }
    val chosen = role
    if (chosen == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Re-pick coded profiles") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(viewModel.codedProfileRoles(), key = { it.title }) { item ->
                        val value = viewModel.codedRoleValue(item)
                        TextButton(onClick = {
                            role = item
                            refuse = ""
                        }) {
                            Column {
                                Text(item.title)
                                Text(if (value.isBlank()) "(not set)" else value)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )
        return
    }
    val names = viewModel.profileNames()
    AlertDialog(
        onDismissRequest = { role = null },
        title = { Text(chosen.title) },
        text = {
            Column {
                if (refuse.isNotEmpty()) Text(refuse)
                if (names.isEmpty()) {
                    Text("No profiles to pick.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        if (chosen.optional) {
                            item(key = "unset") {
                                TextButton(onClick = {
                                    viewModel.setCodedRole(chosen, "")
                                    role = null
                                }) { Text("(not set)") }
                            }
                        }
                        items(names, key = { it }) { name ->
                            TextButton(onClick = {
                                val reason = viewModel.setCodedRole(chosen, name)
                                if (reason.isEmpty()) role = null else refuse = reason
                            }) { Text(name) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { role = null }) { Text("Back") }
        }
    )
}
