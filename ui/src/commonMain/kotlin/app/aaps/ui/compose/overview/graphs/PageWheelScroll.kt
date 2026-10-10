package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/** Pixels of page movement per wheel notch. A notch reports a delta of about 1 on Windows. */
private const val WHEEL_STEP_PX = 90f

/**
 * Makes a mouse wheel over the graphs scroll the page up and down.
 *
 * A Vico chart takes the wheel for itself and scrolls sideways, and with a mouse nothing else moves the
 * page, so on desktop the lower graphs could not be reached. The wheel is taken before the chart sees
 * it, in the initial pass, and applied to [scroll].
 *
 * Shift is the way back to the chart: Shift + wheel is left alone, so the graph still pans sideways.
 * Touch has no wheel events, so phones and tablets are not affected.
 *
 * Put this ahead of the `verticalScroll` that owns [scroll], on the same container as the graphs.
 */
@Composable
fun Modifier.pageWheelScroll(scroll: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    return pointerInput(scroll) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                if (event.keyboardModifiers.isShiftPressed) continue
                val dy = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                if (dy == 0f) continue
                event.changes.forEach { it.consume() }
                scope.launch { scroll.scrollBy(dy * WHEEL_STEP_PX) }
            }
        }
    }
}
