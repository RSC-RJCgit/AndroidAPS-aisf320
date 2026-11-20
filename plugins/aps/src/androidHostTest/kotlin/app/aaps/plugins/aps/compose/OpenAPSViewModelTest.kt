package app.aaps.plugins.aps.compose

import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.onBlocking
import org.mockito.kotlin.whenever

internal class OpenAPSViewModelTest {

    @Mock private lateinit var apsPlugin: APS
    @Mock private lateinit var rxBus: RxBus
    @Mock private lateinit var dateUtil: DateUtil

    private val updateGuiFlow = MutableSharedFlow<EventOpenAPSUpdateGui>()
    private val resetGuiFlow = MutableSharedFlow<EventResetOpenAPSGui>()

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(rxBus.toFlow(EventOpenAPSUpdateGui::class)).thenReturn(updateGuiFlow)
        whenever(rxBus.toFlow(EventResetOpenAPSGui::class)).thenReturn(resetGuiFlow)
    }

    // Dispatchers.Unconfined runs the VM's launched coroutines (init updateState + onRefresh's
    // apsPlugin.invoke) eagerly and synchronously, so state and interactions settle in-line. The
    // never-completing toFlow subscriptions just park; a standalone scope has no leaked-coroutine check.
    // The resolver is the real one, not a mock. init's updateState() takes the no-result path and builds
    // OpenAPSUiState(statusMessage = rh.gs(...)); a mock answered that with null, which became a
    // non-null-param NPE inside the Unconfined init coroutine. The standalone scope has no leaked-coroutine
    // check, so it escaped this test and tripped "UncaughtExceptionsBeforeTest" in whatever runTest ran next
    // in the same fork. Real text cannot be null, so that whole hazard is gone.
    private fun viewModel() =
        OpenAPSViewModel(apsPlugin, rxBus, generatedTextResolver(), dateUtil, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun uiState_showsNotAvailable_whenNoApsResult() {
        whenever(apsPlugin.lastAPSResult).thenReturn(null)

        val sut = viewModel()

        assertThat(sut.uiState.value.statusMessage).isEqualTo("Not available")
        assertThat(sut.uiState.value.sections).isEmpty()
    }

    @Test
    fun onRefresh_setsRefreshingState() {
        whenever(apsPlugin.lastAPSResult).thenReturn(null)

        val sut = viewModel()
        sut.onRefresh()

        // isRefreshing is flipped synchronously; the subsequent apsPlugin.invoke(...) is a
        // fire-and-forget launch, not asserted here to keep the test deterministic.
        assertThat(sut.uiState.value.isRefreshing).isTrue()
    }

    // 2026-11-20: a client has no result of its own; the loop phone's from NS is shown instead
    private fun nsResult(): APSResult = mock<APSResult> {
        on { rawData() } doReturn ""
        onBlocking { resultAsString() } doReturn "Rate 0.80 U/h"
    }

    @Test
    fun uiState_showsTheLoopPhonesNsResult_whenThereIsNoLocalResult() {
        whenever(apsPlugin.lastAPSResult).thenReturn(null)
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("20/11 21:05")
        val ns = NsApsResultSource(result = { nsResult() }, resultTime = { 1L }, autoIsfValues = { mapOf("acce" to "1.10") })

        val sut = OpenAPSViewModel(apsPlugin, rxBus, generatedTextResolver(), dateUtil, CoroutineScope(Dispatchers.Unconfined), ns)

        assertThat(sut.uiState.value.statusMessage).isEmpty()
        assertThat(sut.uiState.value.lastRun).isEqualTo("20/11 21:05 (loop phone, from NS)")
        val rows = sut.uiState.value.sections.flatMap { it.rows }
        assertThat(rows).contains(KeyValueRow("acce", "1.10"))
        assertThat(rows).contains(KeyValueRow("", "Rate 0.80 U/h"))
    }

    @Test
    fun uiState_staysNotAvailable_whenNsHasNoResultEither() {
        whenever(apsPlugin.lastAPSResult).thenReturn(null)
        val ns = NsApsResultSource(result = { null }, resultTime = { 0L })

        val sut = OpenAPSViewModel(apsPlugin, rxBus, generatedTextResolver(), dateUtil, CoroutineScope(Dispatchers.Unconfined), ns)

        assertThat(sut.uiState.value.statusMessage).isEqualTo("Not available")
    }
}
