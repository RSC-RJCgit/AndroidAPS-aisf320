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
import app.aaps.ui.compose.overview.chips.List1Row

@Composable
fun List1Dialog(viewModel: ChipsViewModel) {
    val rows = remember(viewModel.list1Generation, viewModel.list1Open) { viewModel.list1Rows() }
    DirectListDialog(
        open = viewModel.list1Open,
        title = "Direct AutoISF settings",
        rows = rows,
        onDismiss = viewModel::closeList1,
        onApply = viewModel::applyList1,
    )
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
    )
}

@Composable
private fun DirectListDialog(
    open: Boolean,
    title: String,
    rows: List<List1Row>,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit,
) {
    if (!open) return
    var picked by remember { mutableStateOf<List1Row?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(rows, key = { it.label }) { row ->
                    TextButton(onClick = { picked = row }) {
                        Column {
                            Text(row.label)
                            Text("Current: ${row.current}")
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
                if (up == null) {
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
