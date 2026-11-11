package app.aaps.ui.compose.overview.graphs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GraphRenderReadyTest {
    @Test
    fun oldDrawDoesNotReleaseNewScale() = runTest {
        val reporter = GraphRenderReporter()
        val nextScale = GraphRenderToken()
        reporter.drawn.value = GraphRenderToken()
        val waiting = async { reporter.awaitDrawn(nextScale) }
        runCurrent()
        assertFalse(waiting.isCompleted)

        reporter.drawn.value = nextScale
        runCurrent()
        assertTrue(waiting.isCompleted)
        waiting.await()
    }

    @Test
    fun drawBeforeCollectorStartsIsStillAccepted() = runTest {
        val reporter = GraphRenderReporter()
        val scale = GraphRenderToken()
        reporter.drawn.value = scale
        reporter.awaitDrawn(scale)
    }
}
