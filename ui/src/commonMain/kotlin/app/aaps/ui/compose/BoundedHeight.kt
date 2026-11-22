package app.aaps.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints

/**
 * Put before a `verticalScroll` (2026-11-21, per explicit request): when the parent hands down no height limit, the window
 * height is used as the limit instead of letting Compose throw "Vertically scrollable component was measured with an infinity
 * maximum height constraints". The iPhone client crashed with that twice on 22 Nov (05:32, 05:38), each time after the AIV
 * table had been open in landscape and the phone was turned. With a limit already in place it changes nothing.
 */
@Composable
fun Modifier.boundedHeight(): Modifier {
    val windowHeight = LocalWindowInfo.current.containerSize.height.coerceAtLeast(1)
    return layout { measurable, constraints ->
        val bounded = if (constraints.hasBoundedHeight) constraints
        else constraints.copy(maxHeight = maxOf(windowHeight, constraints.minHeight).coerceAtMost(Constraints.Infinity - 1))
        val placeable = measurable.measure(bounded)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
