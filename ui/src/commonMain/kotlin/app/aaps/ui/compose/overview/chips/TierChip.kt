package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalAapsScale

/**
 * Fork (2026-11-25, per explicit request): the coded tier of the running profile, right of the profile chip.
 * tAS / tBS / tCS = Standard tier A / B / C, tAL / tBL / tCL = Low tier A / B / C. A profile set in two tier roles shows
 * both (tAS·tCL). With no tier match: St100..St250 for a steroid role, S or L for the current Standard or Low role, and
 * nothing at all otherwise. A tap opens the coded profile roles list.
 */
@Composable
fun TierChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.height(AapsSpacing.chipHeight * LocalAapsScale.current)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = AapsSpacing.medium)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
        }
    }
}

private val tierChipRoles = listOf(
    "tAS" to StringKey.ApsAutoIsfStandard100ProfileName,
    "tBS" to StringKey.ApsAutoIsfStandard105ProfileName,
    "tCS" to StringKey.ApsAutoIsfStandard110ProfileName,
    "tAL" to StringKey.ApsAutoIsfLow70ProfileName,
    "tBL" to StringKey.ApsAutoIsfLow80ProfileName,
    "tCL" to StringKey.ApsAutoIsfLow90ProfileName,
)

private val steroidChipRoles = listOf(
    "St100" to StringKey.ApsAutoIsfSteroid100ProfileName,
    "St110" to StringKey.ApsAutoIsfSteroid110ProfileName,
    "St130" to StringKey.ApsAutoIsfSteroid130ProfileName,
    "St150" to StringKey.ApsAutoIsfSteroid150ProfileName,
    "St190" to StringKey.ApsAutoIsfSteroid190ProfileName,
    "St250" to StringKey.ApsAutoIsfSteroid250ProfileName,
)

/** The [TierChip] text for [runningName] (the running profile without percent or time shift); empty hides the chip. */
fun tierChipLabel(runningName: String, role: (StringKey) -> String): String {
    if (runningName.isBlank()) return ""
    fun matches(key: StringKey) = role(key).let { it.isNotBlank() && it == runningName }
    val tiers = tierChipRoles.filter { matches(it.second) }.map { it.first }
    if (tiers.isNotEmpty()) return tiers.joinToString("·")
    steroidChipRoles.firstOrNull { matches(it.second) }?.let { return it.first }
    if (matches(StringKey.ApsAutoIsfStandardProfileName)) return "S"
    if (matches(StringKey.ApsAutoIsfLowProfileName)) return "L"
    return ""
}
