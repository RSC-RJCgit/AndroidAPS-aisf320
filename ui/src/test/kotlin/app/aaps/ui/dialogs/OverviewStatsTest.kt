package app.aaps.ui.dialogs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The numbers of the overview's statistics dot (2026-11-01) */
class OverviewStatsTest {

    @Test
    fun percentagesSplitAtTheRangeEdges() {
        // 70 and 180 are in range, as in v4
        val stats = calculateOverviewStats(listOf(60.0, 70.0, 120.0, 180.0, 200.0))!!

        assertThat(stats.readingCount).isEqualTo(5)
        assertThat(stats.lowPercent).isWithin(1e-9).of(20.0)
        assertThat(stats.inRangePercent).isWithin(1e-9).of(60.0)
        assertThat(stats.highPercent).isWithin(1e-9).of(20.0)
    }

    @Test
    fun averageGmiAndCv() {
        val stats = calculateOverviewStats(listOf(100.0, 200.0))!!

        assertThat(stats.averageMgdl).isWithin(1e-9).of(150.0)
        assertThat(stats.gmiPercent).isWithin(1e-9).of(3.31 + 0.02392 * 150.0)
        // Population SD 50 over a mean of 150
        assertThat(stats.cvPercent).isWithin(1e-9).of(50.0 / 150.0 * 100.0)
    }

    @Test
    fun sensorErrorsAreLeftOut() {
        val stats = calculateOverviewStats(listOf(38.0, 100.0))!!

        assertThat(stats.readingCount).isEqualTo(1)
        assertThat(stats.averageMgdl).isWithin(1e-9).of(100.0)
    }

    @Test
    fun noReadingsGiveNothing() {
        assertThat(calculateOverviewStats(emptyList())).isNull()
        assertThat(calculateOverviewStats(listOf(10.0))).isNull()
    }
}
