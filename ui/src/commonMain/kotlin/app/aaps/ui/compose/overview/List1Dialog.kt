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
    if (!viewModel.list1Open) return
    val rows = remember(viewModel.list1Generation) { viewModel.list1Rows() }
    var picked by remember { mutableStateOf<List1Row?>(null) }
    AlertDialog(
        onDismissRequest = viewModel::closeList1,
        title = { Text("Direct AutoISF settings") },
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
            TextButton(onClick = viewModel::closeList1) { Text("Cancel") }
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
                        viewModel.applyList1(row.downMmol)
                        picked = null
                    }) { Text("OK") }
                } else {
                    Row {
                        TextButton(onClick = {
                            viewModel.applyList1(row.downMmol)
                            picked = null
                        }) { Text("Down") }
                        TextButton(onClick = {
                            viewModel.applyList1(up)
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
