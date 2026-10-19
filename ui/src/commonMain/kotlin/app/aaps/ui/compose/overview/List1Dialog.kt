package app.aaps.ui.compose.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
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
    ReadOnlyNotice(viewModel)
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
    ReadOnlyNotice(viewModel)
}

@Composable
fun List3Dialog(viewModel: ChipsViewModel) {
    val rows = remember(viewModel.list3Open) { viewModel.list3Rows() }
    DirectListDialog(
        open = viewModel.list3Open,
        title = "List 3",
        rows = rows,
        onDismiss = viewModel::closeList3,
        onApply = viewModel::applyList3,
    )
    ReadOnlyNotice(viewModel)
}

/** Shown after Apply on a read-only client: practice only, nothing was sent to the master. */
@Composable
private fun ReadOnlyNotice(viewModel: ChipsViewModel) {
    if (!viewModel.readOnlyNotice) return
    AlertDialog(
        onDismissRequest = viewModel::closeReadOnlyNotice,
        text = { Text(stringResource(CoreUiStrings.clientcontrol_fail_read_only_client)) },
        confirmButton = {
            TextButton(onClick = viewModel::closeReadOnlyNotice) { Text(stringResource(CoreUiStrings.ok)) }
        }
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(0.95f)) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.85f),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                    // Codes sent from a client that wait for a running TT to end (2026-10-08).
                    if (queuedText != null) Text(queuedText, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(rows, key = { it.label }) { row ->
                            Text(
                                text = if (row.current.isBlank()) row.label else stringResource(UiStrings.direct_list_setting_value, row.label, row.current),
                                fontSize = 15.sp,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                                    .clickable(role = Role.Button) {
                                        if (row.pickProfiles) onPickProfiles() else picked = row
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(CoreUiStrings.cancel))
                        }
                    }
                }
            }
        }
    }
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
