package app.aaps.ui.compose.overview.graphs

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeAxisStepTest {

    @Test
    fun twoHoursUses15Minutes() {
        assertEquals(15, timeAxisStepMinutes(120.0))
    }

    @Test
    fun justOverTwoHoursUses30Minutes() {
        assertEquals(30, timeAxisStepMinutes(121.0))
    }

    @Test
    fun threeHoursUses30Minutes() {
        assertEquals(30, timeAxisStepMinutes(180.0))
    }

    @Test
    fun justOverThreeHoursStaysHourly() {
        assertEquals(60, timeAxisStepMinutes(181.0))
    }

    @Test
    fun onTheHourStaysHourOnly() {
        assertEquals("09", timeAxisLabel(9, 0))
    }

    @Test
    fun quarterHourShowsMinutes() {
        assertEquals("09:15", timeAxisLabel(9, 15))
    }

    @Test
    fun halfHourShowsMinutes() {
        assertEquals("14:30", timeAxisLabel(14, 30))
    }
}
