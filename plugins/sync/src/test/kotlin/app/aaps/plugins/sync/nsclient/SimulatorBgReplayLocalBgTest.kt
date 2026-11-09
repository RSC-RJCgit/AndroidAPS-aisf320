package app.aaps.plugins.sync.nsclient

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** While real CGM BG (for example from xDrip) arrives on this phone, the replay uploads that BG and nothing else. */
class SimulatorBgReplayLocalBgTest {

    private fun gv(at: Long, value: Double = 150.0, valid: Boolean = true) =
        GV(timestamp = at, value = value, raw = null, noise = null, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN, isValid = valid)

    @Test
    fun xdripReadingCountsAsRealBg() {
        val xdrip = gv(1_000_000L)
        assertThat(SimulatorBgReplay.realBgReadings(listOf(xdrip), emptySet())).containsExactly(xdrip)
    }

    @Test
    fun replayReadingsDoNotCount() {
        val replay = listOf(gv(1_000_000L), gv(1_060_000L))
        assertThat(SimulatorBgReplay.realBgReadings(replay, setOf(1_000_000L, 1_060_000L))).isEmpty()
    }

    @Test
    fun realReadingBetweenReplayReadingsIsFound() {
        val xdrip = gv(1_030_000L, 156.0)
        val readings = listOf(gv(1_000_000L, 163.0), xdrip, gv(1_060_000L, 162.0))
        assertThat(SimulatorBgReplay.realBgReadings(readings, setOf(1_000_000L, 1_060_000L))).containsExactly(xdrip)
    }

    @Test
    fun realReadingsAreOldestFirst() {
        val older = gv(1_000_000L)
        val newer = gv(1_120_000L)
        assertThat(SimulatorBgReplay.realBgReadings(listOf(newer, older), emptySet())).containsExactly(older, newer).inOrder()
    }

    @Test
    fun invalidReadingDoesNotCount() {
        assertThat(SimulatorBgReplay.realBgReadings(listOf(gv(1_000_000L, valid = false)), emptySet())).isEmpty()
    }

    @Test
    fun noReadingsMeansReplayCarriesOn() {
        assertThat(SimulatorBgReplay.realBgReadings(emptyList(), emptySet())).isEmpty()
    }

    @Test
    fun onlyReadingsMissingOnTheSiteAreUploaded() {
        val sent = gv(1_000_000L)
        val missing = gv(1_060_000L)
        assertThat(SimulatorBgReplay.localToUpload(listOf(sent, missing), setOf(1_000_000L))).containsExactly(missing)
    }

    @Test
    fun siteEntryWithAnotherValueIsNotReplaced() {
        // The site's echo is calibrated again on receipt; replacing it would repeat every minute.
        assertThat(SimulatorBgReplay.localToUpload(listOf(gv(1_000_000L, 156.0)), setOf(1_000_000L))).isEmpty()
    }
}
