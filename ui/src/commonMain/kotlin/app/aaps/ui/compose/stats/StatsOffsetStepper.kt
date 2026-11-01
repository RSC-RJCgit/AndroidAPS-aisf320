package app.aaps.ui.compose.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource

/**
 * How many pods or sensors to skip, as − value + with no text field (2026-11-01, per explicit request). The field it
 * replaces opened a number pad, and on iOS that pad has no Done key, so the keyboard could not be closed and the tab
 * could not be left. The buttons are 48 dp, a whole finger, where the field's were small and sat under the keyboard.
 */
@Composable
internal fun StatsOffsetStepper(
    label: TextRef,
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val labelText = stringResource(label)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(labelText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { onValueChange((value - 1).coerceAtLeast(0)) },
            enabled = value > 0,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "${stringResource(CoreUiStrings.decrement)} $labelText")
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 32.dp)
        )
        FilledTonalIconButton(
            onClick = { onValueChange((value + 1).coerceAtMost(max)) },
            enabled = value < max,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "${stringResource(CoreUiStrings.increment)} $labelText")
        }
    }
}
