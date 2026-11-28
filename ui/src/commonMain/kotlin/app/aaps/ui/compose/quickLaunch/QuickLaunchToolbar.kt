package app.aaps.ui.compose.quickLaunch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.icons.IcBolus
import app.aaps.core.ui.compose.icons.IcCarbs
import app.aaps.core.ui.compose.icons.IcTtActivity
import app.aaps.core.ui.compose.icons.IcTtEatingSoon
import app.aaps.core.ui.compose.icons.IcTtHypo
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.boundedHeight

@Immutable
data class ResolvedQuickLaunchItem(
    val action: QuickLaunchAction,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val description: String? = null
)

internal val QuickLaunchButtonSize = 48.dp
private val ToolbarIconSize = 24.dp

/** The larger buttons (2026-11-26, per explicit request, List 2 "Quick-launch buttons"). */
internal val QuickLaunchLargeButtonSize = 64.dp
private val LargeToolbarIconSize = 32.dp

/**
 * Floating pill-shaped toolbar for quick actions.
 * Displayed as an overlay at the bottom of the overview screen.
 * Horizontal: when items exceed the available width the row scrolls sideways (2026-11-12, per explicit request, so the
 * bar can hold more items now that it takes the hidden bottom bar's place). Vertical (landscape rail): extra items go
 * into an overflow dropdown menu, as before.
 * Long-press any icon to see a tooltip with label and description.
 */
@Composable
fun QuickLaunchToolbar(
    items: List<ResolvedQuickLaunchItem>,
    onActionClick: (QuickLaunchAction) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    large: Boolean = false,
    // 2026-11-27, per explicit request: a grip at the start of the bar. Hold and drag it to move the bar ([onGripDrag] gets each
    // move, [onGripDragEnd] the end); double-tap it to turn the bar between horizontal and vertical ([onGripDoubleTap]).
    // No grip when [onGripDrag] is null (the landscape rail).
    onGripDrag: ((Offset) -> Unit)? = null,
    onGripDragEnd: () -> Unit = {},
    onGripDoubleTap: () -> Unit = {},
) {
    if (items.isEmpty()) return
    val buttonSize = if (large) QuickLaunchLargeButtonSize else QuickLaunchButtonSize
    val iconSize = if (large) LargeToolbarIconSize else ToolbarIconSize

    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 6.dp,
        modifier = modifier.padding(
            horizontal = if (vertical) 4.dp else 16.dp,
            vertical = if (vertical) 8.dp else 0.dp
        )
    ) {
        BoxWithConstraints(if (vertical) Modifier.width(buttonSize) else Modifier.height(buttonSize)) {
            val itemSizePx = with(LocalDensity.current) { buttonSize.toPx() }
            val contentPaddingPx = with(LocalDensity.current) { 8.dp.toPx() }
            val gripPx = if (onGripDrag != null) with(LocalDensity.current) { QuickLaunchGripLength.toPx() } else 0f
            val availablePx = (if (vertical) constraints.maxHeight else constraints.maxWidth) - gripPx
            val maxItems = ((availablePx - contentPaddingPx) / itemSizePx).toInt()
                .coerceAtLeast(1)

            val needsOverflow = vertical && items.size > maxItems
            val visibleItems = if (needsOverflow) items.take((maxItems - 1).coerceAtLeast(0)) else items
            val overflowItems = if (needsOverflow) items.drop((maxItems - 1).coerceAtLeast(0)) else emptyList()

            if (vertical) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    if (onGripDrag != null) QuickLaunchGrip(vertical = true, buttonSize, onGripDrag, onGripDragEnd, onGripDoubleTap)
                    visibleItems.forEach { item ->
                        ToolbarIconButton(item, onActionClick, buttonSize, iconSize, tooltipBeside = true)
                    }
                    if (needsOverflow) {
                        OverflowMenuButton(overflowItems, onActionClick, buttonSize, iconSize)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The grip stays put while the icons scroll
                    if (onGripDrag != null) QuickLaunchGrip(vertical = false, buttonSize, onGripDrag, onGripDragEnd, onGripDoubleTap)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp)
                    ) {
                        visibleItems.forEach { item ->
                            ToolbarIconButton(item, onActionClick, buttonSize, iconSize)
                        }
                        if (needsOverflow) {
                            OverflowMenuButton(overflowItems, onActionClick, buttonSize, iconSize)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun resolveItemColor(item: ResolvedQuickLaunchItem): androidx.compose.ui.graphics.Color {
    val action = item.action
    if (action is QuickLaunchAction.CodedAction)
        return if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF64B5FF) else Color(0xFF006BD6)
    if (action is QuickLaunchAction.QuickWizardAction) return when (item.icon) {
        IcBolus -> ElementType.INSULIN.color()
        IcCarbs -> ElementType.CARBS.color()
        else    -> ElementType.QUICK_WIZARD.color()
    }
    if (action is QuickLaunchAction.TempTargetPreset) {
        val colors = AapsTheme.generalColors
        return when (item.icon) {
            IcTtActivity   -> colors.ttActivity
            IcTtEatingSoon -> colors.ttEatingSoon
            IcTtHypo       -> colors.ttHypoglycemia
            else            -> colors.ttCustom
        }
    }
    return action.elementType?.color() ?: MaterialTheme.colorScheme.primary
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarIconButton(
    item: ResolvedQuickLaunchItem,
    onActionClick: (QuickLaunchAction) -> Unit,
    buttonSize: Dp,
    iconSize: Dp,
    tooltipBeside: Boolean = false
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (tooltipBeside) TooltipAnchorPosition.Right else TooltipAnchorPosition.Below
        ),
        tooltip = {
            PlainTooltip {
                if (item.description != null) {
                    Column {
                        Text(item.label, style = MaterialTheme.typography.labelMedium)
                        Text(item.description, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(item.label)
                }
            }
        },
        state = rememberTooltipState()
    ) {
        IconButton(
            onClick = { onActionClick(item.action) },
            enabled = item.enabled,
            modifier = Modifier
                .size(buttonSize)
        ) {
            val action = item.action
            if (action is QuickLaunchAction.CodedAction) {
                Text(
                    text = action.toolbarLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = resolveItemColor(item),
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = item.label }
                )
            } else {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = resolveItemColor(item),
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

private val QuickLaunchGripLength = 22.dp

/** Six dots: hold and drag to move the bar, double-tap to turn it (2026-11-27, per explicit request). */
@Composable
private fun QuickLaunchGrip(
    vertical: Boolean,
    buttonSize: Dp,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gripName = "Move quick launch (double-tap to turn)"
    // The gesture blocks below start once; these keep them calling the newest callbacks (positions change every frame)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (vertical) Modifier.width(buttonSize).height(QuickLaunchGripLength) else Modifier.width(QuickLaunchGripLength).height(buttonSize))
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() }) { change, amount ->
                    change.consume()
                    drag(amount)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTap() }) }
            .clearAndSetSemantics { contentDescription = gripName }
    ) {
        Canvas(modifier = Modifier.size(if (vertical) 18.dp else 10.dp, if (vertical) 10.dp else 18.dp)) {
            val r = 1.6.dp.toPx()
            // 2 x 3 dots across the bar's direction
            for (a in 0..1) for (b in 0..2) {
                val along = size.width * (if (vertical) (b + 0.5f) / 3f else (a + 0.5f) / 2f)
                val across = size.height * (if (vertical) (a + 0.5f) / 2f else (b + 0.5f) / 3f)
                drawCircle(dotColor, r, Offset(along, across))
            }
        }
    }
}

@Composable
private fun OverflowMenuButton(
    overflowItems: List<ResolvedQuickLaunchItem>,
    onActionClick: (QuickLaunchAction) -> Unit,
    buttonSize: Dp,
    iconSize: Dp
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(CoreUiStrings.more_options),
                modifier = Modifier.size(iconSize)
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            // 2026-11-28: the iPhone client crashed with "Vertically scrollable component was measured with an infinity
            // maximum height" from this menu (the vertical bar in landscape, the phone just turned). The menu scrolls, so
            // it needs a height limit; the window height stands in when the popup hands down none.
            modifier = Modifier.boundedHeight()
        ) {
            overflowItems.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.label)
                            if (item.description != null) {
                                Text(
                                    item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        val action = item.action
                        if (action is QuickLaunchAction.CodedAction) {
                            Text(
                                text = action.toolbarLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = resolveItemColor(item),
                                modifier = Modifier.clearAndSetSemantics {}
                            )
                        } else {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = resolveItemColor(item),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    enabled = item.enabled,
                    onClick = {
                        showMenu = false
                        onActionClick(item.action)
                    }
                )
            }
        }
    }
}
