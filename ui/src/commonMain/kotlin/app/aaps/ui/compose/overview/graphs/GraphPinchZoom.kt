package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * Our own two-finger zoom for the graphs (2026-11-20, per explicit request), in place of the graph library's.
 *
 * The library (Vico 3.3.1) gives up on a whole pinch as soon as one finger move reaching it was already used by another
 * handler, and the rest of the pinch then scrolls the graph sideways instead. On Flip5 (a12f) and the iPhone (38af), 20 Nov,
 * a pinch within 1-3 seconds of a zoom mostly only scrolled ("NO ZOOM" in the log, 13 of 25 and 16 of 23), although the fingers
 * moved 150-350 px apart or together.
 *
 * This watches in the Initial pass, before the graphs. Once two fingers have moved apart or together by more than the touch
 * slop, every change of their distance is passed on as a zoom ratio ([onPinch], with the anchor between the fingers as a
 * share of the width, 0 left to 1 right), and the moves are consumed: the graph does not scroll during the pinch, and the
 * library's own pinch, which sees consumed moves, does not zoom a second time. One finger, a mouse and the wheel are untouched.
 */
internal fun Modifier.graphPinchZoom(onPinch: (ratio: Float, bias: Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (first.type == PointerType.Mouse) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var startSpread = Float.NaN
        var lastSpread = Float.NaN
        var pinching = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val spread = (a - b).getDistance()
                if (startSpread.isNaN() || lastSpread.isNaN()) {
                    startSpread = spread
                    lastSpread = spread
                } else {
                    if (!pinching && abs(spread - startSpread) > slop) pinching = true
                    if (pinching) {
                        if (spread > 0f && lastSpread > 0f && spread != lastSpread) {
                            val bias = if (size.width > 0) (((a.x + b.x) / 2f) / size.width).coerceIn(0f, 1f) else 0.5f
                            onPinch(spread / lastSpread, bias)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    lastSpread = spread
                }
            } else {
                // Back to one finger: a new pinch measures from its own start
                startSpread = Float.NaN
                lastSpread = Float.NaN
            }
        } while (event.changes.any { it.pressed })
    }
}
