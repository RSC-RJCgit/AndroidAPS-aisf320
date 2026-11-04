package app.aaps.ui.compose.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlin.math.ceil

/** Four highest totals in a wrapping summary, then the latest pods with only their date and total. */
@Composable
fun PodInsulinCompose(
    data: PodInsulinData?,
    formatDate: (Long) -> String,
    modifier: Modifier = Modifier,
    summary: List<PodInsulin> = emptyList(),
    formatSummaryDate: (Long) -> String = formatDate
) {
    val decimalFormatter = LocalDecimalFormatter.current
    Column(modifier = modifier) {
        if (data == null) {
            Text(stringResource(UiStrings.pod_insulin_no_data))
        } else {
            Text(stringResource(UiStrings.pod_insulin_found, data.pods.size, data.podsAvailable), style = MaterialTheme.typography.labelMedium)
            Text(
                data.pods.joinToString("  ·  ") { pod ->
                    "${formatSummaryDate(pod.startTimestamp)} ${decimalFormatter.to1Decimal(pod.totalUnits)} U"
                },
                softWrap = true,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }
        summary.asReversed().forEach { pod ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatSummaryDate(pod.startTimestamp))
                Text(if (pod.days.any { it.units != null }) "${decimalFormatter.to1Decimal(pod.totalUnits)} U" else "—")
            }
        }
    }
}

/** 0 up to the highest day, in steps of 10 U, or 20 U when a day passes 60 U, so a phone's axis stays readable. */
internal fun podAxisTicks(highestUnits: Double): List<Double> {
    val step = if (highestUnits > 60.0) 20.0 else 10.0
    val top = (ceil(highestUnits / step) * step).coerceAtLeast(step)
    return generateSequence(0.0) { it + step }.takeWhile { it <= top }.toList()
}
