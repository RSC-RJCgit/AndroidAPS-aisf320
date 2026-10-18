package app.aaps.ui.compose.tempTarget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource

/**
 * Quick temp target buttons at the top of the TT screen (2026-10-18), like the v3 dialog: Eating soon, Activity and Hypo
 * open a popup with +/- boxes for target and duration, filled from that preset; OK sets it now. The fourth button stops
 * the running TT and is shown only while one runs. Setting goes through the same confirm-and-set flow as the editor.
 */
@Composable
internal fun TempTargetQuickButtons(
    units: GlucoseUnit,
    showStop: Boolean,
    quickDefaults: (TT.Reason) -> Pair<Double, Int>?,
    onSet: (reason: TT.Reason, targetUserUnits: Double, durationMinutes: Int) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var popupReason by remember { mutableStateOf<TT.Reason?>(null) }
    val colors = AapsTheme.generalColors
    val reasons = listOf(
        Triple(TT.Reason.EATING_SOON, CoreUiStrings.eatingsoon, colors.ttEatingSoon),
        Triple(TT.Reason.ACTIVITY, CoreUiStrings.activity, colors.ttActivity),
        Triple(TT.Reason.HYPOGLYCEMIA, CoreUiStrings.hypo, colors.ttHypoglycemia)
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AapsTheme.spacing.small)
    ) {
        reasons.forEach { (reason, label, color) ->
            // Shown only when the preset exists (it always does for these three, unless the stored list was damaged)
            if (quickDefaults(reason) != null) {
                QuickButton(stringResource(label), color, Modifier.weight(1f)) { popupReason = reason }
            }
        }
        if (showStop) {
            QuickButton(stringResource(CoreUiStrings.stoptemptarget), MaterialTheme.colorScheme.error, Modifier.weight(1f), onClick = onStop)
        }
    }

    val reason = popupReason
    val defaults = reason?.let(quickDefaults)
    if (reason != null && defaults != null) {
        QuickTtPopup(
            title = stringResource(reasons.first { it.first == reason }.second),
            units = units,
            initialTarget = defaults.first,
            initialDuration = defaults.second,
            onOk = { target, duration ->
                popupReason = null
                onSet(reason, target, duration)
            },
            onDismiss = { popupReason = null }
        )
    }
}

@Composable
private fun QuickButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = ButtonDefaults.TextButtonContentPadding
    ) {
        Text(text = text, textAlign = TextAlign.Center, maxLines = 2, style = MaterialTheme.typography.labelMedium)
    }
}

/** The +/- popup: the same target and duration rows, limits and steps as the TT editor. */
@Composable
private fun QuickTtPopup(
    title: String,
    units: GlucoseUnit,
    initialTarget: Double,
    initialDuration: Int,
    onOk: (targetUserUnits: Double, durationMinutes: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var target by remember { mutableDoubleStateOf(initialTarget) }
    var duration by remember { mutableDoubleStateOf(initialDuration.toDouble()) }
    val (targetRange, targetStep) = when (units) {
        GlucoseUnit.MGDL -> Constants.TT_RANGE_MGDL to 1.0
        GlucoseUnit.MMOL -> Constants.TT_RANGE_MMOL to 0.1
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsTheme.spacing.medium)) {
                NumberInputRow(
                    labelRef = CoreUiStrings.temporary_target,
                    value = target,
                    onValueChange = { target = it },
                    valueRange = targetRange,
                    step = targetStep,
                    valueFormat = if (units == GlucoseUnit.MGDL) NumberFormat.INTEGER else NumberFormat.DECIMAL_1,
                    unitLabel = TextRef.Literal(units.displayLabel),
                    modifier = Modifier.fillMaxWidth()
                )
                NumberInputRow(
                    labelRef = CoreUiStrings.duration,
                    value = duration,
                    onValueChange = { duration = it },
                    valueRange = Constants.ACTION_DURATION,
                    step = 5.0,
                    unitLabel = CoreUiStrings.units_min,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOk(target, duration.toInt()) }) { Text(stringResource(CoreUiStrings.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) }
        },
        modifier = Modifier.padding(AapsTheme.spacing.small)
    )
}
