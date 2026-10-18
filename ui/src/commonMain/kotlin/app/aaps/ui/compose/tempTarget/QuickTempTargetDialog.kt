package app.aaps.ui.compose.tempTarget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextAlign
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TTPreset
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlin.math.round

/**
 * The temp target popup on the overview (2026-10-18), like the v3 TT dialog: one tap on the TT chip opens it.
 * Eating soon, Activity and Hypo fill the +/- boxes from their preset; OK sets the TT (after the usual confirmation).
 * "Stop" is shown while a TT runs. "More" opens the full TT screen with all presets.
 */
@Composable
fun QuickTempTargetDialog(
    presetFor: (TT.Reason) -> TTPreset?,
    showStop: Boolean,
    onSet: (reason: TT.Reason, targetMgdl: Double, durationMinutes: Int) -> Unit,
    onStop: () -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit
) {
    val profileUtil = LocalProfileUtil.current
    val units = profileUtil.units
    val colors = AapsTheme.generalColors
    val reasons = listOf(
        Triple(TT.Reason.EATING_SOON, CoreUiStrings.eatingsoon, colors.ttEatingSoon),
        Triple(TT.Reason.ACTIVITY, CoreUiStrings.activity, colors.ttActivity),
        Triple(TT.Reason.HYPOGLYCEMIA, CoreUiStrings.hypo, colors.ttHypoglycemia)
    ).filter { presetFor(it.first) != null }

    fun toUserUnits(mgdl: Double): Double =
        if (units == GlucoseUnit.MGDL) round(mgdl) else round(profileUtil.fromMgdlToUnits(mgdl, units) * 10.0) / 10.0

    val first = reasons.firstOrNull()?.first
    var reason by remember { mutableStateOf(first) }
    var target by remember { mutableDoubleStateOf(first?.let(presetFor)?.let { toUserUnits(it.targetValue) } ?: 0.0) }
    var duration by remember { mutableDoubleStateOf(first?.let(presetFor)?.let { (it.duration / 60000L).toDouble() } ?: 0.0) }
    val (targetRange, targetStep) = when (units) {
        GlucoseUnit.MGDL -> Constants.TT_RANGE_MGDL to 1.0
        GlucoseUnit.MMOL -> Constants.TT_RANGE_MMOL to 0.1
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreUiStrings.temporary_target)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsTheme.spacing.medium)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AapsTheme.spacing.small), modifier = Modifier.fillMaxWidth()) {
                    reasons.forEach { (r, label, color) ->
                        val text = @Composable { Text(stringResource(label), textAlign = TextAlign.Center, maxLines = 2, style = MaterialTheme.typography.labelMedium) }
                        val pick = {
                            presetFor(r)?.let { preset ->
                                reason = r
                                target = toUserUnits(preset.targetValue)
                                duration = (preset.duration / 60000L).toDouble()
                            }
                            Unit
                        }
                        if (r == reason) FilledTonalButton(onClick = pick, modifier = Modifier.weight(1f), contentPadding = ButtonDefaults.TextButtonContentPadding) { text() }
                        else OutlinedButton(
                            onClick = pick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) { text() }
                    }
                }
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
                Row(horizontalArrangement = Arrangement.spacedBy(AapsTheme.spacing.small), modifier = Modifier.fillMaxWidth()) {
                    if (showStop) {
                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(stringResource(CoreUiStrings.stoptemptarget), textAlign = TextAlign.Center) }
                    }
                    OutlinedButton(onClick = onMore, modifier = Modifier.weight(1f)) {
                        Text(stringResource(UiStrings.quick_tt_more), textAlign = TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val r = reason ?: return@TextButton
                    onSet(r, profileUtil.convertToMgdl(target, units), duration.toInt())
                },
                enabled = reason != null && duration > 0.0
            ) { Text(stringResource(CoreUiStrings.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) }
        }
    )
}
