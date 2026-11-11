package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** A new token for each model, including changes to its axis ranges. */
internal class GraphRenderToken

internal val GRAPH_RENDER_TOKEN = ExtraStore.Key<GraphRenderToken>()

/** Reports the model actually drawn, rather than just the transaction submitted to Vico. */
internal class GraphRenderReporter : Decoration {
    val drawn = MutableStateFlow<GraphRenderToken?>(null)

    suspend fun awaitDrawn(token: GraphRenderToken) {
        drawn.first { it === token }
    }

    override fun drawOverLayers(context: CartesianDrawingContext) {
        if (context.layerBounds.width <= 0f || context.layerBounds.height <= 0f) return
        drawn.value = context.model.extraStore.getOrNull(GRAPH_RENDER_TOKEN)
    }
}

@Composable
internal fun ReportGraphReady(reporter: GraphRenderReporter, token: GraphRenderToken, windowReady: Boolean, onReady: (Boolean) -> Unit) {
    val currentOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(reporter, token, windowReady) {
        currentOnReady(false)
        if (!windowReady) return@LaunchedEffect
        reporter.awaitDrawn(token)
        // A draw signal can resume a collector immediately. Keep Compose writes off the draw path.
        withFrameNanos { }
        currentOnReady(true)
    }
}
