package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.AutoIsfGraphData
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.StepsGraphData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class InspectStatusTest {

    @Test
    fun `empty status rows take no space`() {
        assertThat(inspectStatusLines(AutoIsfGraphData(statusIsf = " ", statusSteps = ""))).isEmpty()
        assertThat(inspectStatusLines(AutoIsfGraphData(statusIsf = "ISF=90", statusTarget = "Target=100")))
            .containsExactly("ISF=90", "Target=100").inOrder()
    }

    @Test
    fun `hiding step text keeps the other status rows`() {
        val data = AutoIsfGraphData(statusIsf = "ISF=90", statusSteps = "S5=12", statusRatio = "DR=0.5")
        assertThat(inspectStatusLines(data, showSteps = false)).containsExactly("ISF=90", "DR=0.5").inOrder()
        assertThat(inspectStatusLines(data, showSteps = true)).contains("S5=12")
    }

    @Test
    fun `steps keep their own times and do not fill gaps with old counts`() {
        val minute = 60_000L
        val near = GraphDataPoint(9 * minute, 12.0)
        val old = GraphDataPoint(1 * minute, 99.0)
        val data = StepsGraphData(steps = listOf(old, near), steps15 = listOf(old), steps60 = listOf(near))

        assertThat(inspectStepSamples(data, 10 * minute)).containsExactly(0 to near, 3 to near).inOrder()
        assertThat(inspectStepSamples(data, 20 * minute)).isEmpty()
    }
}
