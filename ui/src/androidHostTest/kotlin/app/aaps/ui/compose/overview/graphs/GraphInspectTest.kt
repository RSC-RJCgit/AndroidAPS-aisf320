package app.aaps.ui.compose.overview.graphs

import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CalibrationGraphPoint
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.RunningModeSegment
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.interfaces.overview.graph.TherapyEventGraphPoint
import app.aaps.core.interfaces.overview.graph.TherapyEventType
import com.google.common.truth.Truth.assertThat
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.Interaction
import com.patrykandpatrick.vico.compose.common.Point
import org.junit.jupiter.api.Test

/** The values shown when holding the BG graph. */
class GraphInspectTest {

    private val minute = 60_000L
    private val t0 = 1_000_000_000_000L

    private fun bg(minutes: Int, value: Double = 6.0) = BgDataPoint(t0 + minutes * minute, value, BgRange.IN_RANGE, BgType.REGULAR)
    private fun point(minutes: Int, value: Double) = GraphDataPoint(t0 + minutes * minute, value)

    private val readings = listOf(bg(0, 5.0), bg(5, 5.5), bg(10, 6.0))

    @Test
    fun `snaps to the nearest reading and takes the values at its time`() {
        val data = inspectDataAt(
            timestamp = t0 + 6 * minute,
            bgReadings = readings,
            predictions = emptyList(),
            iob = listOf(point(0, 1.0), point(5, 1.2), point(10, 1.4)),
            cob = listOf(point(5, 0.0)),
            actualBasal = listOf(point(-30, 0.8), point(4, 1.5), point(20, 0.0)),
            profileBasal = listOf(point(-60, 0.8)),
            boluses = emptyList(),
            carbs = emptyList(),
            calibrations = emptyList(),
            therapyEvents = emptyList()
        )

        assertThat(data.timestamp).isEqualTo(t0 + 5 * minute)
        assertThat(data.bg?.value).isEqualTo(5.5)
        assertThat(data.iob).isEqualTo(1.2)
        // 0 is a value, not missing data
        assertThat(data.cob).isEqualTo(0.0)
        // Basal is the last change before the reading
        assertThat(data.basal).isEqualTo(1.5)
        assertThat(data.tbrState).isEqualTo(TbrState.HIGH)
    }

    @Test
    fun `no reading within 5 minutes gives no glucose, and values at the finger's time`() {
        val data = inspectDataAt(
            timestamp = t0 + 30 * minute,
            bgReadings = readings,
            predictions = emptyList(),
            iob = listOf(point(30, 0.3)),
            cob = emptyList(),
            actualBasal = emptyList(),
            profileBasal = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            calibrations = emptyList(),
            therapyEvents = emptyList()
        )

        assertThat(data.bg).isNull()
        assertThat(data.timestamp).isEqualTo(t0 + 30 * minute)
        assertThat(data.iob).isEqualTo(0.3)
        assertThat(data.cob).isNull()
        assertThat(data.basal).isNull()
    }

    /**
     * The window is measured from the finger, not from the reading the pill snapped to. A marker is
     * drawn on the BG graph at its own time, so a treatment between two readings has to be listed
     * when it is held — it used to be dropped, which read as "I can see it but holding it shows
     * nothing".
     */
    @Test
    fun `after the last reading the time snaps to a prediction point, without glucose`() {
        val predictions = listOf(15, 20, 25).map { BgDataPoint(t0 + it * minute, 7.0, BgRange.HIGH, BgType.IOB_PREDICTION) }

        val data = inspectDataAt(
            timestamp = t0 + 19 * minute,
            bgReadings = readings,
            predictions = predictions,
            iob = listOf(point(20, 0.9)),
            cob = emptyList(),
            actualBasal = emptyList(),
            profileBasal = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            calibrations = emptyList(),
            therapyEvents = emptyList()
        )

        assertThat(data.timestamp).isEqualTo(t0 + 20 * minute)
        assertThat(data.bg).isNull()
        assertThat(data.iob).isEqualTo(0.9)
    }

    @Test
    fun `the finger's x comes from its distance to the nearest point, clamped to the graph`() {
        val state = GraphInspectState().apply { pxPerX = 2f; originPx = 10f; minX = 0.0; maxX = 100.0 }
        // Vico snaps to the last point of the held graph, here at x = 60, drawn at 120 px
        val lastPoint = object : CartesianMarker.Target {
            override val x = 60.0
            override val canvasX = 120f
        }

        assertThat(state.fingerX(130f, lastPoint)).isEqualTo(65.0)
        assertThat(state.fingerX(300f, lastPoint)).isEqualTo(100.0)
        assertThat(state.fingerX(-500f, lastPoint)).isEqualTo(0.0)
        // No point (Vico clears them when it measures the chart): the graph's own geometry
        assertThat(state.fingerX(130f, null)).isEqualTo(60.0)
        // Nothing drawn yet: the point itself, as before, and nothing without a point
        assertThat(GraphInspectState().fingerX(130f, lastPoint)).isEqualTo(60.0)
        assertThat(GraphInspectState().fingerX(130f, null)).isNull()
    }

    @Test
    fun `only valid treatments within 5 minutes of the finger are listed`() {
        val near = BolusGraphPoint(t0 + 8 * minute, 0.4, BolusType.SMB, isValid = true, label = "0.40 U")
        val far = BolusGraphPoint(t0 + 13 * minute, 1.0, BolusType.NORMAL, isValid = true, label = "1.00 U")
        val invalid = BolusGraphPoint(t0 + 7 * minute, 2.0, BolusType.NORMAL, isValid = false, label = "2.00 U")
        val carbs = CarbsGraphPoint(t0 + 6 * minute, 20.0, isValid = true, label = "20 g")

        // The finger is at minute 7, between the readings at minute 5 and minute 10
        val data = inspectDataAt(
            timestamp = t0 + 7 * minute,
            bgReadings = readings,
            predictions = emptyList(),
            iob = emptyList(),
            cob = emptyList(),
            actualBasal = emptyList(),
            profileBasal = emptyList(),
            boluses = listOf(near, far, invalid),
            carbs = listOf(carbs),
            calibrations = emptyList(),
            therapyEvents = emptyList()
        )

        assertThat(data.boluses).containsExactly(near)
        assertThat(data.carbs).containsExactly(carbs)
    }

    @Test
    fun `a calibration and a BG check near the finger are listed with their value`() {
        val calibration = CalibrationGraphPoint(t0 + 6 * minute, 7.2, isValid = true, label = "7.2 mmol/L")
        val invalid = CalibrationGraphPoint(t0 + 6 * minute, 9.9, isValid = false, label = "9.9 mmol/L")
        val check = TherapyEventGraphPoint(t0 + 8 * minute, TherapyEventType.FINGER_STICK, "6.8 mmol/L", 0, 6.8)
        val note = TherapyEventGraphPoint(t0 + 8 * minute, TherapyEventType.GENERAL, "sensor changed", 0, null)
        val far = TherapyEventGraphPoint(t0 + 20 * minute, TherapyEventType.MBG, "5.0 mmol/L", 0, 5.0)

        val data = inspectDataAt(
            timestamp = t0 + 7 * minute,
            bgReadings = readings,
            predictions = emptyList(),
            iob = emptyList(),
            cob = emptyList(),
            actualBasal = emptyList(),
            profileBasal = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            calibrations = listOf(calibration, invalid),
            therapyEvents = listOf(check, note, far)
        )

        assertThat(data.measured).containsExactly("7.2 mmol/L", "6.8 mmol/L")
    }

    @Test
    fun `COB is left out of the pill when it is 0 g`() {
        assertThat(shownCob(null)).isNull()
        assertThat(shownCob(0.0)).isNull()
        // Rounds to 0 g, so it would read "0 g"
        assertThat(shownCob(0.4)).isNull()
        assertThat(shownCob(0.6)).isEqualTo(1)
        assertThat(shownCob(23.7)).isEqualTo(24)
    }

    @Test
    fun `leaving the plotted area with the finger down keeps inspecting, lifting it ends`() {
        val state = GraphInspectState()
        val marker = SilentInspectMarker
        state.fingerDown = true
        state.pointerDown = true

        // Finger past the readings, e.g. over the axis labels
        state.visibilityListener.onHidden(marker)
        assertThat(state.fingerDown).isTrue()

        state.pointerDown = false
        state.visibilityListener.onHidden(marker)
        assertThat(state.fingerDown).isFalse()
    }

    /** The pill is drawn over the treatment belt, so it lists what the belt shows at that time. */
    @Test
    fun `belt events near the finger are listed and a duration event at every reading it covers`() {
        val note = TherapyEventGraphPoint(t0 + 4 * minute, TherapyEventType.GENERAL, "sensor changed", 0)
        val check = TherapyEventGraphPoint(t0 + 4 * minute, TherapyEventType.FINGER_STICK, "6.8 mmol/L", 0, 6.8)
        // Nearer the reading at minute 10, but within 5 minutes of the finger: the fork matches against the finger
        val announcement = TherapyEventGraphPoint(t0 + 9 * minute, TherapyEventType.ANNOUNCEMENT, "pump alarm", 0)
        val exercise = TherapyEventGraphPoint(t0 - 30 * minute, TherapyEventType.EXERCISE, "Exercise", 60 * minute)
        val ended = TherapyEventGraphPoint(t0 - 60 * minute, TherapyEventType.EXERCISE, "Exercise", 30 * minute)

        // The finger is at minute 7, so the pill snaps to the reading at minute 5
        val data = inspectDataAt(
            timestamp = t0 + 7 * minute,
            bgReadings = readings,
            predictions = emptyList(),
            iob = emptyList(),
            cob = emptyList(),
            actualBasal = emptyList(),
            profileBasal = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            calibrations = emptyList(),
            therapyEvents = listOf(note, check, announcement, exercise, ended)
        )

        assertThat(data.events).containsExactly(note, announcement, exercise)
        // A BG check stays where it was, with its value
        assertThat(data.measured).containsExactly("6.8 mmol/L")
    }

    @Test
    fun `the running mode is listed only when it is not closed loop`() {
        val modes = listOf(
            RunningModeSegment(RM.Mode.CLOSED_LOOP, t0 - 60 * minute, t0 + 3 * minute),
            RunningModeSegment(RM.Mode.SUSPENDED_BY_USER, t0 + 3 * minute, t0 + 8 * minute),
            RunningModeSegment(RM.Mode.CLOSED_LOOP, t0 + 8 * minute, t0 + 20 * minute)
        )

        fun modeAt(minutes: Int) = inspectDataAt(
            timestamp = t0 + minutes * minute,
            bgReadings = readings,
            predictions = emptyList(),
            iob = emptyList(),
            cob = emptyList(),
            actualBasal = emptyList(),
            profileBasal = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            calibrations = emptyList(),
            therapyEvents = emptyList(),
            runningModes = modes
        ).runningMode

        // Snaps to the reading at minute 5, inside the suspension
        assertThat(modeAt(6)).isEqualTo(RM.Mode.SUSPENDED_BY_USER)
        // Snaps to the reading at minute 10, closed loop again
        assertThat(modeAt(11)).isNull()
    }

    /**
     * An icon is about 17 minutes wide on a 6 h graph. The time under the finger snapped to a reading
     * the tapped note did not belong to, and the pill did not list it (seen on a phone).
     */
    @Test
    fun `a tap on a belt icon stands for its event's time and a tap beside the icons for the finger's time`() {
        val note = TherapyEventGraphPoint(t0, TherapyEventType.GENERAL, "note", 0)
        val exercise = TherapyEventGraphPoint(t0 + 60 * minute, TherapyEventType.EXERCISE, "Exercise", 30 * minute)
        // Drawn in the BG graph, not on the belt
        val check = TherapyEventGraphPoint(t0 + 20 * minute, TherapyEventType.FINGER_STICK, "6.8 mmol/L", 0, 6.8)
        val events = listOf(note, exercise, check)
        val radius = 8 * minute

        assertThat(beltTapTimestamp(t0 + 3 * minute, radius, events)).isEqualTo(t0)
        // Both ends of a duration event have an icon
        assertThat(beltTapTimestamp(t0 + 86 * minute, radius, events)).isEqualTo(t0 + 90 * minute)
        assertThat(beltTapTimestamp(t0 + 20 * minute, radius, events)).isEqualTo(t0 + 20 * minute)
        assertThat(beltTapTimestamp(t0 + 40 * minute, radius, events)).isEqualTo(t0 + 40 * minute)
    }

    /** Vico clears its targets whenever the chart is measured, and the belt seldom draws. */
    @Test
    fun `a tap on the belt takes its time from the graph's geometry and not from Vico's targets`() {
        val state = GraphInspectState().apply { pxPerX = 2f; originPx = 4f; minX = 0.0; maxX = 100.0 }
        val controller = state.beltController(iconRadiusPx = 20f)

        controller.shouldShowMarker(Interaction.Tap(Point(64f, 10f)), emptyList())

        assertThat(state.tapX).isEqualTo(30.0)
        assertThat(state.tapRadiusX).isEqualTo(10.0)
    }

    @Test
    fun `a tap on the belt shows the tapped time and the next touch closes the pill`() {
        val state = GraphInspectState().apply { pxPerX = 2f; originPx = 4f; minX = 0.0; maxX = 100.0; shownX = 30.0 }
        val controller = state.beltController(iconRadiusPx = 20f)
        val tap = Interaction.Tap(Point(64f, 10f))

        assertThat(controller.shouldAcceptInteraction(tap, emptyList())).isTrue()
        // No Vico marker: the pill shows the time
        assertThat(controller.shouldShowMarker(tap, emptyList())).isFalse()
        assertThat(state.tapX).isEqualTo(30.0)
        assertThat(state.fingerDown).isFalse()

        // The line shows the tapped time while the pill is up
        assertThat(state.lineX).isNull()
        state.pill = pillData
        assertThat(state.lineX).isEqualTo(30.0)

        state.onTouchDown()
        assertThat(state.pill).isNull()
        assertThat(state.tapX).isNull()
        assertThat(state.lineX).isNull()
    }

    @Test
    fun `a hold on the belt works as on every graph and a touch does not close its pill`() {
        val state = GraphInspectState().apply { pointerDown = true }
        val target = object : CartesianMarker.Target {
            override val x = 30.0
            override val canvasX = 60f
        }
        val longPress = Interaction.LongPress(Point(60f, 10f))
        val controller = state.beltController(iconRadiusPx = 20f)

        assertThat(controller.shouldAcceptInteraction(longPress, listOf(target))).isTrue()
        assertThat(controller.shouldShowMarker(longPress, listOf(target))).isTrue()
        assertThat(state.fingerDown).isTrue()
        assertThat(state.tapX).isNull()

        state.pill = pillData
        state.onTouchDown()
        assertThat(state.pill).isEqualTo(pillData)
    }

    @Test
    fun `a late long press cannot restart inspection after release`() {
        val state = GraphInspectState().apply {
            pointerDown = true
            fingerDown = true
            pill = pillData
        }
        state.pointerDown = false
        val latePress = Interaction.LongPress(Point(60f, 10f))

        assertThat(state.controller.shouldAcceptInteraction(latePress, emptyList())).isFalse()
        assertThat(state.controller.shouldShowMarker(latePress, emptyList())).isFalse()
        assertThat(state.fingerDown).isFalse()
        // Normal release keeps the pill for the existing timeout, rather than clearing it immediately.
        assertThat(state.pill).isEqualTo(pillData)
    }

    @Test
    fun `losing window focus clears inspection and rejects queued chart callbacks`() {
        val state = GraphInspectState().apply {
            pointerDown = true
            fingerDown = true
            shownX = 30.0
            tapX = 30.0
            pill = pillData
        }
        state.onWindowFocusChanged(false)

        assertThat(state.pointerDown).isFalse()
        assertThat(state.fingerDown).isFalse()
        assertThat(state.pill).isNull()
        assertThat(state.tapX).isNull()
        assertThat(state.lineX).isNull()
        val latePress = Interaction.LongPress(Point(60f, 10f))
        assertThat(state.controller.shouldShowMarker(latePress, emptyList())).isFalse()
        state.beltController(20f).shouldShowMarker(Interaction.Tap(Point(60f, 10f)), emptyList())
        assertThat(state.tapX).isNull()
    }

    @Test
    fun `returning focus requires a new physical press before inspection can start`() {
        val state = GraphInspectState()
        val press = Interaction.LongPress(Point(60f, 10f))
        state.onWindowFocusChanged(false)
        state.onWindowFocusChanged(true)
        assertThat(state.controller.shouldAcceptInteraction(press, emptyList())).isFalse()
        state.pointerDown = true
        assertThat(state.controller.shouldAcceptInteraction(press, emptyList())).isTrue()
        assertThat(state.controller.shouldShowMarker(press, emptyList())).isTrue()
    }

    @Test
    fun `physical release ends a hold even if the chart never sends Release`() {
        val state = GraphInspectState().apply { shownX = 30.0 }
        state.onPointerState(true, startsPress = true)
        state.controller.shouldShowMarker(Interaction.LongPress(Point(60f, 10f)), emptyList())
        state.pill = pillData

        state.onPointerState(false)

        assertThat(state.pointerDown).isFalse()
        assertThat(state.fingerDown).isFalse()
        assertThat(state.lineX).isNull()
        assertThat(state.pill).isEqualTo(pillData)
        assertThat(state.controller.shouldShowMarker(Interaction.Move(Point(70f, 10f)), emptyList())).isFalse()
    }

    @Test
    fun `chart still accepts release and exit after the physical state has cleared`() {
        val state = GraphInspectState()
        val release = Interaction.Release(Point(60f, 10f))
        val exit = Interaction.Exit(Point(60f, 10f), isInBounds = false)

        assertThat(state.controller.shouldAcceptInteraction(release, emptyList())).isTrue()
        assertThat(state.controller.shouldShowMarker(release, emptyList())).isFalse()
        assertThat(state.controller.shouldAcceptInteraction(exit, emptyList())).isTrue()
        assertThat(state.controller.shouldShowMarker(exit, emptyList())).isFalse()
    }

    @Test
    fun `a new click clears stale inspection before another hold starts`() {
        val state = GraphInspectState().apply {
            fingerDown = true
            shownX = 30.0
            tapX = 30.0
            pill = pillData
        }

        state.onPointerState(true, startsPress = true)

        assertThat(state.pointerDown).isTrue()
        assertThat(state.fingerDown).isFalse()
        assertThat(state.pill).isNull()
        assertThat(state.tapX).isNull()
        assertThat(state.lineX).isNull()
        assertThat(state.controller.shouldAcceptInteraction(Interaction.Move(Point(70f, 10f)), emptyList())).isFalse()
    }

    @Test
    fun `continued movement with the button down keeps the current hold`() {
        val state = GraphInspectState()
        state.onPointerState(true, startsPress = true)
        state.controller.shouldShowMarker(Interaction.LongPress(Point(60f, 10f)), emptyList())
        state.pill = pillData

        state.onPointerState(true)

        assertThat(state.fingerDown).isTrue()
        assertThat(state.pill).isEqualTo(pillData)
    }

    @Test
    fun `mouse movement after exit cannot restart the old hold`() {
        val state = GraphInspectState()
        state.onPointerState(true, startsPress = true)
        state.controller.shouldShowMarker(Interaction.LongPress(Point(60f, 10f)), emptyList())
        state.onPointerState(false)

        state.onPointerState(true)

        assertThat(state.pointerDown).isFalse()
        assertThat(state.fingerDown).isFalse()
        assertThat(state.controller.shouldAcceptInteraction(Interaction.LongPress(Point(60f, 10f)), emptyList())).isFalse()
    }

    private val pillData = InspectData(
        timestamp = t0, bg = null, iob = null, cob = null, basal = null, tbrState = TbrState.NONE,
        boluses = emptyList(), carbs = emptyList(), measured = emptyList()
    )

    @Test
    fun `TBR state follows the overview rule`() {
        assertThat(tbrState(basal = 0.8, profileBasal = 0.8)).isEqualTo(TbrState.NONE)
        assertThat(tbrState(basal = 0.805, profileBasal = 0.8)).isEqualTo(TbrState.NONE)
        assertThat(tbrState(basal = 1.5, profileBasal = 0.8)).isEqualTo(TbrState.HIGH)
        assertThat(tbrState(basal = 0.4, profileBasal = 0.8)).isEqualTo(TbrState.LOW)
        assertThat(tbrState(basal = 0.0, profileBasal = 0.8)).isEqualTo(TbrState.LOW)
        assertThat(tbrState(basal = 0.8, profileBasal = null)).isEqualTo(TbrState.NONE)
    }
}
