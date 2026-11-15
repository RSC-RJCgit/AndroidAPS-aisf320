package app.aaps.ui.compose.overview.chips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/** How many AutoISF values can be pinned to the closed chip. More do not fit in one line on a phone. */
const val AUTOISF_MAX_PINNED = 4

/** Which way an AutoISF factor pushes: above 1 it makes ISF stronger (more insulin), below 1 weaker. */
enum class AutoIsfTone {

    STRONGER, WEAKER, NEUTRAL;

    companion object {

        fun of(factor: Double?): AutoIsfTone = when {
            factor == null -> NEUTRAL
            factor > 1.0   -> STRONGER
            factor < 1.0   -> WEAKER
            else           -> NEUTRAL
        }
    }
}

/** One value in the AutoISF chip. [key] is what gets saved when it is pinned, [label] is shown. */
@Immutable
data class AutoIsfValue(
    val key: String,
    val value: String,
    val tone: AutoIsfTone,
    val label: String = key
) {

    companion object {

        const val ACCE = "acce"
        const val BG = "bg"
        const val PP = "pp"
        const val DURA = "dura"
        const val FINAL = "final"
        const val IOBTH = "iobth"
    }
}

/**
 * What the AutoISF chip shows. [visible] is false while AutoISF is not the active APS, and then the chip
 * is not shown. [pinned] are the keys shown in the closed chip, in this order. [apsPluginClassName] is
 * the class of the active APS plugin, so a long click can open its screen. Display only.
 */
@Immutable
data class AutoIsfFactorsUiState(
    val visible: Boolean = false,
    val values: List<AutoIsfValue> = emptyList(),
    val pinned: List<String> = emptyList(),
    val expanded: Boolean = false,
    val apsPluginClassName: String? = null
)

/**
 * AutoISF fork: the AutoISF values of the last loop run in a chip over the whole width, under the BG and
 * the chips, in the color of the sensitivity chip.
 *
 * - Closed: the AutoISF symbol and the pinned values, right-aligned.
 * - Open: every value in a grid. A tap on a value pins it to the closed chip or removes it
 *   (at most [AUTOISF_MAX_PINNED]). [extraContent] is shown under the grid.
 * - A tap on the chip opens or closes it. A long click on the chip opens the AIV table (2026-11-15) when
 *   [onOpenAivTable] is given, else the AutoISF screen (APS tab).
 *
 * Display only, nothing here changes dosing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutoIsfChip(
    state: AutoIsfFactorsUiState,
    onNavigate: (NavigationRequest) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onTogglePin: (String) -> Unit,
    modifier: Modifier = Modifier,
    extraContent: (@Composable () -> Unit)? = null,
    aivTable: AivTable? = null,
    onOpenAivTable: (() -> Unit)? = null,
    onCloseAivTable: () -> Unit = {}
) {
    AivTableDialog(aivTable, onCloseAivTable)
    if (!state.visible) return
    val openAps = { state.apsPluginClassName?.let { onNavigate(NavigationRequest.Plugin(it)) } ?: Unit }
    val openLabel = stringResource(UiStrings.overview_autoisf_open)
    val toggleLabel = stringResource(if (state.expanded) UiStrings.overview_autoisf_collapse else UiStrings.overview_autoisf_expand)
    val pinnedValues = state.pinned.mapNotNull { key -> state.values.firstOrNull { it.key == key } }
    Surface(
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = ElementType.SENSITIVITY.color().copy(alpha = 0.2f),
        modifier = modifier
            .fillMaxWidth()
            // The same gap above and below as between the chips next to the BG.
            .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.extraSmall)
            .combinedClickable(
                onClickLabel = toggleLabel,
                onLongClickLabel = if (onOpenAivTable != null) "AIV" else openLabel,
                onLongClick = onOpenAivTable ?: openAps,
                onClick = { onExpandedChange(!state.expanded) }
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AapsSpacing.chipHeight - AapsSpacing.small * 2)
            ) {
                Icon(
                    imageVector = IcAutoIsf,
                    contentDescription = null,
                    tint = ElementType.SENSITIVITY.color(),
                    modifier = Modifier.size(AapsSpacing.chipIconSize)
                )
                Text(
                    text = stringResource(UiStrings.overview_autoisf_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = AapsSpacing.medium)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.large, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = AapsSpacing.medium)
                ) {
                    pinnedValues.forEach { item ->
                        Text(
                            text = stringResource(UiStrings.overview_autoisf_value, item.label, item.value),
                            style = MaterialTheme.typography.bodyMedium,
                            color = toneColor(item.tone),
                            maxLines = 1
                        )
                    }
                }
                Icon(
                    imageVector = if (state.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = AapsSpacing.small)
                )
            }
            AnimatedVisibility(visible = state.expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
                    modifier = Modifier.padding(top = AapsSpacing.small)
                ) {
                    state.values.chunked(GRID_COLUMNS).forEach { rowValues ->
                        Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                            rowValues.forEach { item ->
                                AutoIsfTile(
                                    item = item,
                                    pinned = item.key in state.pinned,
                                    pinAllowed = item.key in state.pinned || state.pinned.size < AUTOISF_MAX_PINNED,
                                    onClick = { onTogglePin(item.key) },
                                    onLongClick = openAps,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(GRID_COLUMNS - rowValues.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Text(
                        text = stringResource(UiStrings.overview_autoisf_pinned, state.pinned.size, AUTOISF_MAX_PINNED),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                    extraContent?.invoke()
                }
            }
        }
    }
}

private const val GRID_COLUMNS = 3

/** One value in the open chip: name and value, right-aligned, with a pin that shows whether it is pinned. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AutoIsfTile(
    item: AutoIsfValue,
    pinned: Boolean,
    pinAllowed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pinLabel = stringResource(if (pinned) UiStrings.overview_autoisf_unpin else UiStrings.overview_autoisf_pin)
    val background = MaterialTheme.colorScheme.onSurface.copy(alpha = if (pinned) 0.12f else 0.05f)
    Surface(
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = background,
        modifier = modifier.combinedClickable(
            enabled = pinAllowed,
            onClickLabel = pinLabel,
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Box(modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)) {
            Icon(
                imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (pinAllowed) 1f else 0.38f),
                modifier = Modifier
                    .size(PIN_ICON_SIZE)
                    .align(Alignment.TopStart)
            )
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PIN_ICON_SIZE + AapsSpacing.small)
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = toneColor(item.tone),
                    maxLines = 1
                )
            }
        }
    }
}

private val PIN_ICON_SIZE = 14.dp

@Composable
private fun toneColor(tone: AutoIsfTone): Color = when (tone) {
    AutoIsfTone.STRONGER -> AapsTheme.generalColors.statusWarning
    AutoIsfTone.WEAKER   -> AapsTheme.generalColors.statusNormal
    AutoIsfTone.NEUTRAL  -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** AutoISF symbol: two crossing arms that bend, drawn as lines so the chip can tint it. */
private val IcAutoIsf: ImageVector by lazy {
    ImageVector.Builder(
        name = "IcAutoIsf",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4.5f, 4.5f)
            quadTo(12f, 9f, 19.5f, 19.5f)
            moveTo(19.5f, 4.5f)
            quadTo(12f, 9f, 4.5f, 19.5f)
        }
    }.build()
}
