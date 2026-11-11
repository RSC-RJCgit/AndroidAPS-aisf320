package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/** Keep the last ready frame visible while every chart draws the next model into a spare layer. */
@Composable
internal fun Modifier.holdGraphFrame(ready: Boolean): Modifier {
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val shown = remember { arrayOfNulls<GraphicsLayer>(1) }
    return drawWithContent {
        val next = if (shown[0] === first) second else first
        next.record { this@drawWithContent.drawContent() }
        if (ready) shown[0] = next
        shown[0]?.let { drawLayer(it) }
    }
}
