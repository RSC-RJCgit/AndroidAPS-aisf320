package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.AIV
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.stringResource
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The AIV table (2026-11-15, ported from the AaAPS v4 fork): one line per AutoISF loop run of the last 24 hours,
 * newest first. Opened by a long press on the AutoISF chip, on the master and on its clients. Display only.
 */
@Composable
fun AivTableDialog(table: AivTable?, onClose: () -> Unit) {
    table ?: return
    val dateUtil = LocalDateUtil.current
    val scroll = rememberScrollState()
    val columns = aivColumns(table.mmol, table.bolusByTime)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("AIV  (${table.rows.size})") },
        text = {
            if (table.rows.isEmpty()) Text("No AutoISF rows in the last 24 hours")
            else Column(modifier = Modifier.horizontalScroll(scroll)) {
                Row {
                    AivCell("Time", TIME_WIDTH, bold = true)
                    columns.forEach { AivCell(it.title, it.width, bold = true) }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp).width(TIME_WIDTH + columns.fold(0.dp) { sum, c -> sum + c.width })) {
                    items(table.rows, key = { it.timestamp }) { row ->
                        Row {
                            AivCell(dateUtil.timeString(row.timestamp), TIME_WIDTH)
                            columns.forEach { AivCell(it.value(row), it.width) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(CoreUiStrings.close)) } }
    )
}

@Composable
private fun AivCell(text: String, width: Dp, bold: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.width(width).padding(horizontal = 2.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.End,
        maxLines = 1
    )
}

private class AivColumn(val title: String, val width: Dp, val value: (AIV) -> String)

private fun aivColumns(mmol: Boolean, bolusByTime: Map<Long, Double>): List<AivColumn> {
    fun bg(mgdl: Double) = if (mmol) fixed(mgdl * Constants.MGDL_TO_MMOLL, 1) else fixed(mgdl, 0)
    fun delta(mgdl: Double) = if (mmol) fixed(mgdl * Constants.MGDL_TO_MMOLL, 2) else fixed(mgdl, 1)
    return listOf(
        AivColumn("BG", 40.dp) { bg(it.glucose) },
        AivColumn("Δ", 44.dp) { delta(it.delta) },
        AivColumn("sΔ", 44.dp) { delta(it.shortAvgDelta) },
        AivColumn("lΔ", 44.dp) { delta(it.longAvgDelta) },
        AivColumn("acce", 44.dp) { fixed(it.bgAcceleration, 2) },
        AivColumn("ACCE", 40.dp) { fixed(it.acceIsf, 2) },
        AivColumn("BG", 40.dp) { fixed(it.bgIsf, 2) },
        AivColumn("PP", 40.dp) { fixed(it.ppIsf, 2) },
        AivColumn("DURA", 40.dp) { fixed(it.duraIsf, 2) },
        AivColumn("FINAL", 44.dp) { fixed(it.finalIsf, 2) },
        AivColumn("iobTH", 44.dp) { fixed(it.iobThEffective, 2) },
        AivColumn("IOB", 40.dp) { fixed(it.iob, 2) },
        AivColumn("SMB", 40.dp) { fixed(it.smbDelivered, 2) },
        // Manual, calculator, carb-split and protein/fat boluses since the row before (2026-11-15)
        AivColumn("Bolus", 44.dp) { row -> bolusByTime[row.timestamp]?.takeIf { it > 0.0 }?.let { fixed(it, 2) } ?: "--" },
        AivColumn("DR", 36.dp) { fixed(it.smbDeliveryRatio, 2) },
        AivColumn("COB", 36.dp) { fixed(it.cob, 0) },
        AivColumn("TBR", 40.dp) { fixed(it.basal, 2) },
        AivColumn("target", 44.dp) { bg(it.targetMgdl) },
        AivColumn("S5", 36.dp) { it.steps5.toString() },
        AivColumn("S15", 40.dp) { it.steps15.toString() },
        AivColumn("S30", 40.dp) { it.steps30.toString() },
        AivColumn("S60", 44.dp) { it.steps60.toString() },
    )
}

private val TIME_WIDTH = 52.dp

/** [value] with [decimals] decimals, the same text on every platform (no String.format in common code). */
private fun fixed(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "-"
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (abs(value) * factor).roundToLong()
    val whole = scaled / factor
    val fraction = scaled % factor
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole.${fraction.toString().padStart(decimals, '0')}"
}
