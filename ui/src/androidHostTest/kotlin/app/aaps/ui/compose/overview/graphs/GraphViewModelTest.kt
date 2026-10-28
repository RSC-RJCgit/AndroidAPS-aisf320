package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.GraphConfigRepository
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.overview.graph.TimeRange
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
internal class GraphViewModelTest {

    @Mock private lateinit var cache: OverviewDataCache
    @Mock private lateinit var graphConfigRepository: GraphConfigRepository
    @Mock private lateinit var aapsLogger: AAPSLogger
    @Mock private lateinit var preferences: Preferences
    @Mock private lateinit var dateUtil: DateUtil
    @Mock private lateinit var rh: ResourceHelper
    @Mock private lateinit var persistenceLayer: PersistenceLayer
    @Mock private lateinit var config: Config
    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var profileUtil: ProfileUtil

    private lateinit var sut: GraphViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // StandardTestDispatcher defers the init{} launchIn observers (no advanceUntilIdle), so we test
        // the synchronous field initializers / setter methods against the default state.
        Dispatchers.setMain(StandardTestDispatcher())
        // Field initializer for _chartConfigFlow reads these synchronously at construction.
        whenever(preferences.get(UnitDoubleKey.OverviewHighMark)).thenReturn(180.0)
        whenever(preferences.get(UnitDoubleKey.OverviewLowMark)).thenReturn(72.0)
        // init{} builds a cold flow chain (.drop(1)...) on each observed key — must be non-null.
        whenever(preferences.observe(UnitDoubleKey.OverviewHighMark)).thenReturn(MutableStateFlow(180.0))
        whenever(preferences.observe(UnitDoubleKey.OverviewLowMark)).thenReturn(MutableStateFlow(72.0))
        whenever(preferences.observe(BooleanKey.ApsAutoIsfShowGraph5)).thenReturn(MutableStateFlow(true))
        whenever(preferences.observe(BooleanKey.ApsAutoIsfShowGraphText)).thenReturn(MutableStateFlow(true))
        whenever(preferences.observe(BooleanNonKey.ApsAutoIsfCleanGraphRequested)).thenReturn(MutableStateFlow(false))
        whenever(preferences.observe(BooleanNonKey.ApsAutoIsfGraphResetRequested)).thenReturn(MutableStateFlow(false))
        // fullWindow = false: the overview behaviour, where the axis hugs the data.
        whenever(preferences.observe(BooleanKey.VirtualShowStepText)).thenReturn(MutableStateFlow(true))
        sut = GraphViewModel(cache, false, graphConfigRepository, aapsLogger, preferences, dateUtil, rh, persistenceLayer, config, activePlugin, profileUtil)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private abstract class TestVirtualPump : Pump, VirtualPump

    @Test
    fun summaryUsesTheBrowsedWindowAndStoredGlucose() = runBlocking {
        // This read uses the real IO dispatcher, so its timeout must use real time too.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val end = 1_800_000_000_000L
        val start = end - 7 * 24 * 60 * 60_000L
        whenever(dateUtil.now()).thenReturn(end + 24 * 60 * 60_000L)
        whenever(profileUtil.convertToMgdlDetect(72.0)).thenReturn(72.0)
        whenever(profileUtil.convertToMgdlDetect(180.0)).thenReturn(180.0)
        val reading = GV(timestamp = end, raw = null, value = 120.0, trendArrow = TrendArrow.NONE, noise = null, sourceSensor = SourceSensor.UNKNOWN)
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(start, end, true)).thenReturn(listOf(reading))
        sut.aivHistoryEndTime = end

        sut.openStatsSummary(7)

        val result = withTimeout(10_000) { sut.statsSummary.filterNotNull().first { it.data != null || it.failed } }
        assertThat(result.failed).isFalse()
        assertThat(result.endTime).isEqualTo(end)
        assertThat(result.days).isEqualTo(7)
        assertThat(result.data?.readingCount).isEqualTo(1)
        assertThat(result.data?.averageMgdl).isEqualTo(120.0)
        assertThat(result.data?.tir?.inRange).isEqualTo(100.0)
    }

    @Test
    fun closingSummaryCancelsItsPendingReadAndDoesNotReopenIt() = runTest {
        whenever(dateUtil.now()).thenReturn(1_800_000_000_000L)
        sut.openStatsSummary()

        sut.closeStatsSummary()
        runCurrent()

        assertThat(sut.statsSummary.value).isNull()
        verify(persistenceLayer, never()).getBgReadingsDataFromTimeToTime(any(), any(), any())
    }

    @Test
    fun stepTextSettingAffectsOnlyTheFullVirtualPumpApp() {
        val virtual = mock<TestVirtualPump>()
        val wrapper = mock<PumpWithConcentration>()
        whenever(config.APS).thenReturn(true)
        whenever(activePlugin.activePump).thenReturn(wrapper)
        whenever(wrapper.selectedActivePump()).thenReturn(virtual)

        assertThat(sut.showStepText(GraphDisplay(showStepText = false))).isFalse()
        assertThat(sut.showStepText(GraphDisplay(showStepText = true))).isTrue()

        whenever(config.AAPSCLIENT).thenReturn(true)
        assertThat(sut.showStepText(GraphDisplay(showStepText = false))).isTrue()

        whenever(config.AAPSCLIENT).thenReturn(false)
        val physical = mock<Pump>()
        whenever(wrapper.selectedActivePump()).thenReturn(physical)
        assertThat(sut.showStepText(GraphDisplay(showStepText = false))).isTrue()
    }

    /**
     * A prediction must never set the left edge of the axis - issue #5111.
     *
     * Predictions are future points. When the glucose and bucketed flows are empty and a loop run
     * has already published some, taking the minimum of everything put the left edge at roughly
     * now: the axis became [now, now + horizon], the current time sat pinned to the left with no
     * past behind it, the prediction was the only thing drawn, and it could not be scrolled back
     * because that was the whole range. The next reading refilled the glucose flow and dragged the
     * edge back, which is why it looked transient. Reported as happening after the app had been
     * offline for a while and started loading data again.
     */
    @Test
    fun predictionsAloneDoNotPullTheLeftEdgeOfTheAxisUpToNow() = runTest {
        val now = 1_700_000_000_000L
        val windowStart = now - 24 * 3600_000L
        whenever(dateUtil.now()).thenReturn(now)
        // No glucose and no bucketed data - the state after a spell offline - but the last loop run
        // left predictions behind, all of them in the future.
        val predictions = listOf(
            BgDataPoint(timestamp = now + 5 * 60_000L, value = 7.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
            BgDataPoint(timestamp = now + 60 * 60_000L, value = 6.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION)
        )
        whenever(cache.bgReadingsFlow).thenReturn(MutableStateFlow(emptyList()))
        whenever(cache.bucketedDataFlow).thenReturn(MutableStateFlow(emptyList()))
        whenever(cache.predictionsFlow).thenReturn(MutableStateFlow(predictions))
        whenever(cache.timeRangeFlow).thenReturn(MutableStateFlow(TimeRange(fromTime = windowStart, toTime = now, endTime = now + 2 * 3600_000L)))
        whenever(graphConfigRepository.graphConfigFlow).thenReturn(MutableStateFlow(GraphConfig()))
        val vm = GraphViewModel(cache, false, graphConfigRepository, aapsLogger, preferences, dateUtil, rh, persistenceLayer, config, activePlugin, profileUtil)

        val range = vm.derivedTimeRange.filterNotNull().first()

        // The window's own start, not the first prediction.
        assertThat(range.first).isEqualTo(windowStart)
        // And the predictions still get to push the right edge out past the cached end.
        assertThat(range.second).isAtLeast(now)
    }

    @Test
    fun chartConfigReflectsTheHighAndLowMarkPreferences() {
        val config = sut.chartConfigFlow.value
        assertThat(config.highMark).isEqualTo(180.0)
        assertThat(config.lowMark).isEqualTo(72.0)
    }

    @Test
    fun onGraphInteractionRecordsTheInteractionTimestamp() {
        // The time comes from DateUtil rather than the wall clock, so it can be pinned here instead
        // of only being asserted as "some number above zero".
        whenever(dateUtil.now()).thenReturn(1_700_000_000_000L)

        assertThat(sut.lastInteractionMs).isEqualTo(0L)
        sut.onGraphInteraction()
        assertThat(sut.lastInteractionMs).isEqualTo(1_700_000_000_000L)
    }
}
