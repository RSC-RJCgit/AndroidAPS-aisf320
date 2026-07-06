package app.aaps.ui.compose.autoIsfHistoryDialog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.GeneralColors
import app.aaps.core.ui.R as CoreUiR

// Fixed column widths, in the same left-to-right order as [columnHeaders] and the cells built in
// DataRow — keep both in sync. Fixed dp (not weight-based) since the whole table scrolls
// horizontally as one unit, matching the source fork's HorizontalScrollView + TableLayout.
private val columnWidths = listOf(52, 44, 48, 44, 44, 44, 44, 44, 40, 48, 44, 40, 40, 44, 44, 40, 40, 40, 40, 44).map { it.dp }
private val columnHeaders = listOf(
    "Time", "BGL", "Final", "acce", "bg", "pp", "dura", "SMB", "FR", "iobTH",
    "acce", "Δ", "SΔ", "Req", "TBR", "S5", "S15", "S30", "S60", "S180"
)

@Composable
fun AutoIsfHistoryScreen(
    viewModel: AutoIsfHistoryViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = AapsTheme.generalColors

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text("AutoISF History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(CoreUiR.string.close))
                    }
                },
                actions = {}
            )
        }
    ) { padding ->
        when {
            uiState.isLoading    ->
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            uiState.rows.isEmpty() ->
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("No AutoISF data in last 4 hours", style = MaterialTheme.typography.bodyMedium)
                }

            else                 -> {
                val horizontalScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .horizontalScroll(horizontalScrollState)
                ) {
                    HeaderRow()
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(uiState.rows) { row -> DataRow(row = row, colors = colors) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        columnHeaders.forEachIndexed { index, label ->
            TableCell(text = label, width = columnWidths[index], color = MaterialTheme.colorScheme.onSurfaceVariant, bold = true)
        }
    }
}

@Composable
private fun DataRow(row: AutoIsfRow, colors: GeneralColors) {
    Row {
        TableCell(row.time, columnWidths[0])
        TableCell(row.bgl, columnWidths[1], color = colors.autoIsfGlucose)
        TableCell(row.finalRatio, columnWidths[2], color = row.finalDominant.color(colors, fallback = colors.autoIsfFinalRatio))
        TableCell(row.acce, columnWidths[3], color = colors.autoIsfAcce)
        TableCell(row.bg, columnWidths[4], color = colors.autoIsfBg)
        TableCell(row.pp, columnWidths[5], color = colors.autoIsfPp)
        TableCell(row.dura, columnWidths[6], color = colors.autoIsfDura)
        TableCell(row.smb, columnWidths[7], color = row.smbDominant.color(colors, fallback = colors.autoIsfInsulin))
        TableCell(row.fastRise, columnWidths[8], color = colors.autoIsfInsulin)
        TableCell(row.iobTh, columnWidths[9])
        TableCell(row.bgAcceleration, columnWidths[10], color = colors.autoIsfGlucose)
        TableCell(row.delta, columnWidths[11], color = colors.autoIsfGlucose)
        TableCell(row.shortAvgDelta, columnWidths[12], color = colors.autoIsfGlucose)
        TableCell(row.insulinReq, columnWidths[13], color = colors.autoIsfInsulin)
        TableCell(row.tbrRate, columnWidths[14], color = colors.autoIsfInsulin)
        TableCell(row.steps5min, columnWidths[15])
        TableCell(row.steps15min, columnWidths[16])
        TableCell(row.steps30min, columnWidths[17])
        TableCell(row.steps60min, columnWidths[18])
        TableCell(row.steps180min, columnWidths[19])
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    bold: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        color = color,
        fontSize = 12.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1
    )
}

/** Resolves this dominant-adaptation-type marker to an actual theme color, or [fallback] if NONE
 *  (no factor deviates meaningfully from neutral). */
private fun DominantIsfType.color(colors: GeneralColors, fallback: Color): Color = when (this) {
    DominantIsfType.ACCE -> colors.autoIsfAcce
    DominantIsfType.BG   -> colors.autoIsfBg
    DominantIsfType.PP   -> colors.autoIsfPp
    DominantIsfType.DURA -> colors.autoIsfDura
    DominantIsfType.NONE -> fallback
}
