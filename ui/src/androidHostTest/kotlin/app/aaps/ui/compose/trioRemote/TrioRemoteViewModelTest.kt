package app.aaps.ui.compose.trioRemote

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.trio.TrioRemoteCommand
import app.aaps.core.interfaces.trio.TrioRemoteControl
import app.aaps.core.interfaces.trio.TrioRemoteResult
import app.aaps.core.interfaces.trio.TrioRemoteSetup
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class TrioRemoteViewModelTest {
    @Test
    fun confirmationKeepsItsOriginalModeWhenSettingsAreRefreshed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val control: TrioRemoteControl = mock()
            val profileUtil: ProfileUtil = mock()
            val preferences: Preferences = mock()
            val simulator = TrioRemoteSetup(true, emptyList(), null, emptyList(), simulator = true)
            whenever(control.setup()).thenReturn(simulator)
            whenever(profileUtil.units).thenReturn(GlucoseUnit.MMOL)
            whenever(preferences.get(DoubleKey.SafetyMaxBolus)).thenReturn(2.0)
            val command = TrioRemoteCommand.Bolus(1.25)
            whenever(control.send(command, true)).thenReturn(TrioRemoteResult.Failed("Remote mode changed"))
            val viewModel = TrioRemoteViewModel(control, profileUtil, preferences, mock())
            viewModel.onBolusChange("1.25")
            viewModel.askBolus()
            assertThat(viewModel.uiState.value.confirmingSimulator).isTrue()

            whenever(control.setup()).thenReturn(simulator.copy(simulator = false))
            viewModel.refresh()
            val confirmed = viewModel.uiState.value
            assertThat(confirmed.setup.simulator).isFalse()
            assertThat(confirmed.confirmingSimulator).isTrue()
            viewModel.send(command, confirmed.confirmingSimulator)
            advanceUntilIdle()
            verify(control).send(command, true)
            assertThat(viewModel.uiState.value.sending).isFalse()
            assertThat(viewModel.uiState.value.result?.result).isInstanceOf(TrioRemoteResult.Failed::class.java)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
