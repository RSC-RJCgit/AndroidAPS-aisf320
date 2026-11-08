package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalAapsScale
import app.aaps.core.ui.compose.icons.IcNoTbr
import app.aaps.core.ui.compose.icons.IcTbrHigh
import app.aaps.core.ui.compose.icons.IcTbrLow
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull

/**
 * @see TbrChipHighPreview
 * @see TbrChipLowPreview
 * @see TbrChipNonePreview
 */
@Composable
fun TbrChip(
    state: TbrState,
    onClick: () -> Unit,
    // Fork: the rate delivered now (U/h). When known the chip shows just this number, as 3426 does; null keeps the icon.
    rate: Double? = null,
    onLongClick: () -> Unit = {},
    onDoubleClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val iconColor = AapsTheme.elementColors.tempBasal
    val containerColor = iconColor.copy(alpha = 0.2f)
    val haptic = LocalHapticFeedback.current

    // Disable the clickable Surface's 48dp minimum interactive size so this content-width chip
    // isn't padded to 48dp and centered, which would inset it from the row's right edge
    // (same guard IobChip uses).
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        // Whether a temp basal is running, and which way it goes, was carried ONLY by which of the
        // three icons was drawn - this chip has no text at all. So it announced "Temp basal" in
        // every state and a blind user could not tell a running temp basal from none.
        val tbrState = stringResource(state.toDescription())
        Surface(
            shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
            color = containerColor,
            modifier = modifier
                .height(AapsSpacing.chipHeight * LocalAapsScale.current)
                .combinedClickable(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                    onDoubleClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDoubleClick() },
                )
                .semantics { stateDescription = tbrState }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = AapsSpacing.medium)
            ) {
                if (rate != null) Text(
                    text = formatBasalRate(rate),
                    color = iconColor,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                ) else Icon(
                    imageVector = state.toIcon(),
                    contentDescription = stringResourceOrNull(ElementType.TEMP_BASAL.label()),
                    tint = iconColor,
                    modifier = Modifier.size(AapsSpacing.chipIconSize)
                )
            }
        }
    }
}

/** Fork: a basal rate with two decimals, e.g. 0.85 or 1.20. Locale-free, so it reads the same on every platform. */
internal fun formatBasalRate(rate: Double): String {
    val cents = kotlin.math.round(rate.coerceAtLeast(0.0) * 100).toLong()
    return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/**
 * What the chip's icon means in words. Kept beside [toIcon] so the two cannot drift, and exhaustive
 * with no `else` so a new [TbrState] fails to compile here rather than silently going unspoken.
 */
private fun TbrState.toDescription(): TextRef = when (this) {
    TbrState.HIGH -> CoreUiStrings.tbr_state_above_profile
    TbrState.LOW  -> CoreUiStrings.tbr_state_below_profile
    TbrState.NONE -> CoreUiStrings.tbr_state_none
}

internal fun TbrState.toIcon(): ImageVector = when (this) {
    TbrState.HIGH -> IcTbrHigh
    TbrState.LOW  -> IcTbrLow
    TbrState.NONE -> IcNoTbr
}
