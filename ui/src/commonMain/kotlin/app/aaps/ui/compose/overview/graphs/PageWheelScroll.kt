package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
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
                // Shift pans the graph, Ctrl zooms it (graphZoomControls): both are left to the graphs.
                if (event.keyboardModifiers.isShiftPressed || event.keyboardModifiers.isCtrlPressed) continue
                val dy = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                if (dy == 0f) continue
                event.changes.forEach { it.consume() }
                scope.launch { scroll.scrollBy(dy * WHEEL_STEP_PX) }
            }
        }
    }
}

/**
 * Sideways wheel for a table that has its own horizontal [scroll].
 *
 * Compose Desktop does not scroll on a mouse drag, and a plain wheel is vertical, so a wide table could
 * not be moved sideways at all. Shift + wheel, a horizontal wheel or a touchpad swipe now moves it. A
 * plain vertical wheel is left alone and still scrolls the table up and down.
 */
@Composable
fun Modifier.horizontalWheelScroll(scroll: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    return pointerInput(scroll) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val dx = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.x }
                val dy = event.changes.fold(0f) { sum, change -> sum + change.scrollDelta.y }
                val delta = when {
                    dx != 0f                            -> dx
                    event.keyboardModifiers.isShiftPressed -> dy
                    else                                -> 0f
                }
                if (delta == 0f) continue
                event.changes.forEach { it.consume() }
                scope.launch { scroll.scrollBy(delta * WHEEL_STEP_PX) }
            }
        }
    }
}

/**
 * Mouse and keyboard control of the time axis: zoom, and sideways panning.
 *
 * - Ctrl + wheel and the + and - keys call [onZoom] with +1 (shorter time span) or -1.
 * - Shift + wheel, and holding the primary button while moving, call [onPan] with the number of pixels to
 *   move forward in time (negative is back). Compose Desktop does not scroll a chart on a mouse drag, and
 *   the wheel alone only goes up and down, so neither was possible before.
 *
 * The drag is only seen, never consumed, so taps and the chart's own touch handling are not disturbed. The
 * keys work while the graphs have focus, which a click on them gives.
 */
@Composable
fun Modifier.graphZoomControls(onZoom: (Int) -> Unit, onPan: (Float) -> Unit): Modifier {
    val focusRequester = remember { FocusRequester() }
    return this
        .focusRequester(focusRequester)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Plus, Key.Equals, Key.NumPadAdd, Key.ZoomIn  -> { onZoom(1); true }
                Key.Minus, Key.NumPadSubtract, Key.ZoomOut       -> { onZoom(-1); true }
                else                                             -> false
            }
        }
        .focusable()
        .pointerInput(onZoom, onPan) {
            var lastX = 0f
            var downX = 0f
            var dragging = false
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    when (event.type) {
                        PointerEventType.Press   -> {
                            focusRequester.requestFocus()
                            lastX = change.position.x
                            downX = lastX
                            dragging = false
                        }

                        PointerEventType.Move    -> {
                            if (change.type == PointerType.Mouse && event.buttons.isPrimaryPressed) {
                                val x = change.position.x
                                if (!dragging && kotlin.math.abs(x - downX) > DRAG_START_PX) dragging = true
                                if (dragging) {
                                    onPan(lastX - x)
                                }
                                lastX = x
                            }
                        }

                        PointerEventType.Release -> dragging = false

                        PointerEventType.Scroll  -> {
                            val dy = event.changes.fold(0f) { sum, c -> sum + c.scrollDelta.y }
                            if (dy != 0f) {
                                when {
                                    event.keyboardModifiers.isCtrlPressed  -> {
                                        event.changes.forEach { it.consume() }
                                        onZoom(if (dy < 0f) 1 else -1)
                                    }

                                    event.keyboardModifiers.isShiftPressed -> {
                                        event.changes.forEach { it.consume() }
                                        onPan(dy * WHEEL_STEP_PX)
                                    }
                                }
                            }
                        }

                        else                     -> Unit
                    }
                }
            }
        }
}

/** The pointer has to move this far with the button down before it counts as a drag and not a click. */
private const val DRAG_START_PX = 6f
