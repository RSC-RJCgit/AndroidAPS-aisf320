package app.aaps.ui.compose.overview.graphs

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.overview.graph.DeviceChange
import app.aaps.core.interfaces.overview.graph.TherapyEventGraphPoint
import app.aaps.core.interfaces.overview.graph.TherapyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pod and sensor change marks on the BG graph (2026-11-01). */
class GraphDeviceChangeTest {

    private val minute = 60_000L

    private fun event(timestamp: Long, change: DeviceChange?) =
        TherapyEventGraphPoint(timestamp = timestamp, eventType = TherapyEventType.GENERAL, label = "", duration = 0, deviceChange = change)

    @Test
    fun onlySiteAndSensorChangesGetAMark() {
        assertEquals(DeviceChange.POD, deviceChangeOf(TE.Type.CANNULA_CHANGE))
        assertEquals(DeviceChange.SENSOR, deviceChangeOf(TE.Type.SENSOR_CHANGE))
        assertEquals(DeviceChange.SENSOR, deviceChangeOf(TE.Type.SENSOR_STARTED))
        // A pod change also writes an insulin change: that one draws no second line
        assertNull(deviceChangeOf(TE.Type.INSULIN_CHANGE))
        assertNull(deviceChangeOf(TE.Type.NOTE))
        assertNull(deviceChangeOf(TE.Type.ANNOUNCEMENT))
    }

    @Test
    fun eventsWithoutAChangeAreLeftOut() {
        val marks = deviceChangeMarks(listOf(event(0, null), event(10 * minute, DeviceChange.POD)))

        assertEquals(listOf(DeviceChangeMark(10 * minute, DeviceChange.POD)), marks)
    }

    /** A sensor change and its start, or the same change from both sites, is one line */
    @Test
    fun theSameChangeRecordedTwiceIsOneMark() {
        val marks = deviceChangeMarks(
            listOf(event(0, DeviceChange.SENSOR), event(5 * minute, DeviceChange.SENSOR), event(29 * minute, DeviceChange.SENSOR))
        )

        assertEquals(listOf(DeviceChangeMark(0, DeviceChange.SENSOR)), marks)
    }

    @Test
    fun aPodAndASensorAtTheSameTimeBothShow() {
        val marks = deviceChangeMarks(listOf(event(5 * minute, DeviceChange.SENSOR), event(0, DeviceChange.POD)))

        assertEquals(listOf(DeviceChangeMark(0, DeviceChange.POD), DeviceChangeMark(5 * minute, DeviceChange.SENSOR)), marks)
    }

    @Test
    fun changesFarApartAreEachMarked() {
        val marks = deviceChangeMarks(listOf(event(0, DeviceChange.POD), event(3 * 24 * 60 * minute, DeviceChange.POD)))

        assertEquals(2, marks.size)
    }
}
