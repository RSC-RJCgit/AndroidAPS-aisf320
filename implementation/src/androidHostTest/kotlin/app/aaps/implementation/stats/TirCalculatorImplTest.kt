package app.aaps.implementation.stats

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Several ranges from one read (2026-10-20). The statistics screen read 30 days of readings once per range, which an
 * iPhone could not finish before the next reading restarted it.
 */
internal class TirCalculatorImplTest {

    private val dateUtil: DateUtil = mock()
    private val persistenceLayer: PersistenceLayer = mock()
    private val sut = TirCalculatorImpl(dateUtil, persistenceLayer)

    private val hour = 60 * 60 * 1000L
    private val today = MidnightTime.calc(System.currentTimeMillis())
    private val yesterday = MidnightTime.calc(today - 12 * hour)

    private fun reading(time: Long, value: Double) =
        GV(raw = value, noise = 0.0, value = value, timestamp = time, sourceSensor = SourceSensor.UNKNOWN, trendArrow = TrendArrow.FLAT)

    private suspend fun givenReadings(vararg readings: GV) {
        whenever(dateUtil.now()).thenReturn(today + 2 * hour)
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readings.toList())
    }

    @Test
    fun twoRangesComeFromOneReadAndAreCountedPerDay() = runTest {
        givenReadings(
            reading(yesterday + 1 * hour, 60.0),   // below both
            reading(yesterday + 2 * hour, 120.0),  // in both
            reading(yesterday + 3 * hour, 170.0),  // in the wide range, above the narrow one
            reading(today - hour, 250.0)           // late yesterday, above both
        )

        val (wide, narrow) = sut.calculate(7, listOf(70.0 to 180.0, 70.0 to 140.0))

        verify(persistenceLayer, times(1)).getBgReadingsDataFromTimeToTime(any(), any(), any())
        assertThat(wide.size()).isEqualTo(1)
        val wideDay = wide[yesterday]!!
        assertThat(wideDay.below).isEqualTo(1)
        assertThat(wideDay.inRange).isEqualTo(2)
        assertThat(wideDay.above).isEqualTo(1)
        val narrowDay = narrow[yesterday]!!
        assertThat(narrowDay.inRange).isEqualTo(1)
        assertThat(narrowDay.above).isEqualTo(2)
    }

    @Test
    fun readingsOnTwoDaysGoToTwoDays() = runTest {
        val twoDaysAgo = MidnightTime.calc(yesterday - 12 * hour)
        givenReadings(reading(twoDaysAgo + hour, 100.0), reading(yesterday + hour, 100.0))

        val result = sut.calculate(7, 70.0, 180.0)

        assertThat(result.size()).isEqualTo(2)
        assertThat(result[twoDaysAgo]!!.inRange).isEqualTo(1)
        assertThat(result[yesterday]!!.inRange).isEqualTo(1)
    }
}
