package app.aaps.implementation.bolus

import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.ClientControlActionDispatcher
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.clientcontrol.isNotDeliveryError
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Covers [RoleBranch] prepare/commit routing: client round-trip gating + master local-execution result mapping. */
class RoleBranchTest : TestBase() {

    @Mock lateinit var dispatcher: ClientControlActionDispatcher
    @Mock lateinit var nsClient: NsClient
    @Mock lateinit var config: Config
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var rh: TextResolver

    private val command: ClientControlActionDispatcher.Command = ClientControlActionDispatcher.Command.BolusCommit(bolusId = 1L)

    private fun roleBranch(client: Boolean, readOnly: Boolean = false): RoleBranch {
        whenever(config.AAPSCLIENT).thenReturn(client)
        if (client) whenever(preferences.get(BooleanKey.NsClientReadOnlyClient)).thenReturn(readOnly)
        whenever(rh.gs(any<TextRef>())).thenReturn("text")
        return RoleBranch(dispatcher, nsClient, config, preferences, rxBus, rh, aapsLogger)
    }

    private val profileSwitch = BatchAction.ProfileSwitch(percentage = 90, timeShiftHours = 0, durationMinutes = 10, profileName = "Standard100")
    private val note = BatchAction.TherapyEvent(teType = TE.Type.NOTE, timestamp = 1L, note = "n", source = Sources.Note)

    // ---- master (prepare) ----

    @Test fun prepare_masterPreview_returnsPrepared() = runTest {
        val result = roleBranch(client = false).prepare("l", command) {
            MasterActionExecutor.PrepareResult.Preview(insulin = 1.0, carbs = 0, bolusId = 42L)
        }
        assertThat(result).isInstanceOf(ActionProgress.Prepared::class.java)
        assertThat((result as ActionProgress.Prepared).id).isEqualTo(42L)
    }

    @Test fun prepare_masterError_returnsRejectedExecutionFailed() = runTest {
        val result = roleBranch(client = false).prepare("l", command) {
            MasterActionExecutor.PrepareResult.Error("boom")
        }
        assertThat(result).isEqualTo(ActionProgress.Rejected(FailureReason.ExecutionFailed, "boom"))
    }

    @Test fun prepare_masterNoAction_returnsRejectedNoAction() = runTest {
        val result = roleBranch(client = false).prepare("l", command) {
            MasterActionExecutor.PrepareResult.NoAction
        }
        assertThat(result).isEqualTo(ActionProgress.Rejected(FailureReason.NoAction))
    }

    // ---- client (prepare) ----

    @Test fun prepare_clientReachable_delegatesToDispatcher() = runTest {
        whenever(nsClient.masterReachable).thenReturn(MutableStateFlow(true))
        whenever(dispatcher.run(command, "l")).thenReturn(ActionProgress.Applied)
        val result = roleBranch(client = true).prepare("l", command) { error("master path must not run") }
        assertThat(result).isEqualTo(ActionProgress.Applied)
    }

    @Test fun prepare_clientOfflineControlDisabled_rejectedControlDisabled() = runTest {
        whenever(nsClient.masterReachable).thenReturn(MutableStateFlow(false))
        whenever(nsClient.masterControlAllowed).thenReturn(MutableStateFlow(false))
        val result = roleBranch(client = true).prepare("l", command) { error("must not run") }
        assertThat((result as ActionProgress.Rejected).reason).isEqualTo(FailureReason.ControlDisabled)
    }

    @Test fun prepare_clientOfflineUnreachable_rejectedNotReachable() = runTest {
        whenever(nsClient.masterReachable).thenReturn(MutableStateFlow(false))
        whenever(nsClient.masterControlAllowed).thenReturn(MutableStateFlow(true))
        val result = roleBranch(client = true).prepare("l", command) { error("must not run") }
        assertThat((result as ActionProgress.Rejected).reason).isEqualTo(FailureReason.NotReachable)
    }

    // ---- read-only client ----

    @Test fun prepare_readOnlyClient_profileSwitchAndNote_runLocally() = runTest {
        val command = ClientControlActionDispatcher.Command.BatchPrepare(listOf(profileSwitch, note))
        val result = roleBranch(client = true, readOnly = true).prepare("l", command) {
            MasterActionExecutor.PrepareResult.Preview(insulin = 0.0, carbs = 0, bolusId = 7L)
        }
        assertThat((result as ActionProgress.Prepared).id).isEqualTo(7L)
        verify(dispatcher, never()).run(any(), any())
    }

    // 2026-10-22: every other action is worked out here as an example, so its confirmation shows
    @Test fun prepare_readOnlyClient_bolusInBatch_preparedAsExample() = runTest {
        val bolus = BatchAction.Bolus(
            insulin = 1.0, carbs = 0, carbsTimeOffsetMinutes = 0, carbsDurationHours = 0, recordOnly = false, notes = "", timestamp = 0L, iCfg = null
        )
        val command = ClientControlActionDispatcher.Command.BatchPrepare(listOf(profileSwitch, bolus))
        val result = roleBranch(client = true, readOnly = true).prepare("l", command) {
            MasterActionExecutor.PrepareResult.Preview(insulin = 1.0, carbs = 0, bolusId = 11L)
        }
        assertThat((result as ActionProgress.Prepared).id).isEqualTo(11L)
        verify(dispatcher, never()).run(any(), any())
    }

    @Test fun prepare_readOnlyClient_exampleThatCannotBeWorkedOut_rejectedReadOnly() = runTest {
        val result = roleBranch(client = true, readOnly = true).prepare("l", command) {
            MasterActionExecutor.PrepareResult.Error("pump not available")
        }
        assertThat((result as ActionProgress.Rejected).reason).isEqualTo(FailureReason.ReadOnlyClient)
        assertThat(result.reason.isNotDeliveryError()).isTrue()
        assertThat(result.detail).isEqualTo("pump not available")
    }

    // The bolus calculator on a read-only client: calculated here as an example, never sent (2026-10-16).
    @Test fun prepare_readOnlyClient_wizard_runsLocallyAsExample() = runTest {
        val command = ClientControlActionDispatcher.Command.WizardPrepare(mock<MasterActionExecutor.WizardInputs>())
        val result = roleBranch(client = true, readOnly = true).prepare("l", command) {
            MasterActionExecutor.PrepareResult.Preview(insulin = 2.0, carbs = 20, bolusId = 9L)
        }
        assertThat((result as ActionProgress.Prepared).id).isEqualTo(9L)
        verify(dispatcher, never()).run(any(), any())
    }

    @Test fun prepare_readOnlyClient_quickWizard_preparedAsExample() = runTest {
        val command = ClientControlActionDispatcher.Command.BolusPrepare("guid")
        val result = roleBranch(client = true, readOnly = true).prepare("l", command) {
            MasterActionExecutor.PrepareResult.Preview(insulin = 2.0, carbs = 20, bolusId = 12L)
        }
        assertThat((result as ActionProgress.Prepared).id).isEqualTo(12L)
    }

    // An example is never given from a read-only client, whatever kind of action it was
    @Test fun commit_readOnlyClient_exampleIsNeverGiven() = runTest {
        val branch = roleBranch(client = true, readOnly = true)
        branch.prepare("l", ClientControlActionDispatcher.Command.BolusPrepare("guid")) {
            MasterActionExecutor.PrepareResult.Preview(insulin = 2.0, carbs = 20, bolusId = 13L)
        }
        val result = branch.commit("l", ClientControlActionDispatcher.Command.BolusCommit(bolusId = 13L)) { error("must not deliver") }
        assertThat((result as ActionProgress.Rejected).reason).isEqualTo(FailureReason.ReadOnlyExample)
        verify(dispatcher, never()).run(any(), any())
    }

    @Test fun commit_readOnlyClient_unknownIdIsNeverGiven() = runTest {
        val result = roleBranch(client = true, readOnly = true).commit("l", command) { error("must not deliver") }
        assertThat((result as ActionProgress.Rejected).reason).isEqualTo(FailureReason.ReadOnlyExample)
    }

    @Test fun commit_readOnlyClient_nightscoutRecordRunsLocally() = runTest {
        val branch = roleBranch(client = true, readOnly = true)
        branch.prepare("l", ClientControlActionDispatcher.Command.BatchPrepare(listOf(profileSwitch, note))) {
            MasterActionExecutor.PrepareResult.Preview(insulin = 0.0, carbs = 0, bolusId = 14L)
        }
        val result = branch.commit("l", ClientControlActionDispatcher.Command.BolusCommit(bolusId = 14L)) { MasterActionExecutor.ConfirmResult.Delivered }
        assertThat(result).isEqualTo(ActionProgress.Applied)
        verify(dispatcher, never()).run(any(), any())
    }

    // ---- master (commit) ----

    @Test fun commit_masterDelivered_returnsApplied() = runTest {
        val result = roleBranch(client = false).commit("l", command) { MasterActionExecutor.ConfirmResult.Delivered }
        assertThat(result).isEqualTo(ActionProgress.Applied)
    }

    @Test fun commit_masterNoPending_returnsRejected() = runTest {
        val result = roleBranch(client = false).commit("l", command) { MasterActionExecutor.ConfirmResult.NoPending }
        assertThat((result as ActionProgress.Rejected).reason).isEqualTo(FailureReason.NoPendingBolus)
    }

    @Test fun commit_masterSyncError_returnsRejectedExecutionFailed() = runTest {
        val result = roleBranch(client = false).commit("l", command) { onError ->
            onError(MasterActionExecutor.Failure("fail")); MasterActionExecutor.ConfirmResult.Delivered
        }
        assertThat((result as ActionProgress.Rejected).reason).isEqualTo(FailureReason.ExecutionFailed)
        assertThat(result.detail).isEqualTo("fail")
    }

    /**
     * A command dropped from the queue on purpose fills the same `onError` comment as a real failure, so without
     * the [MasterActionExecutor.ConfirmResult.Cancelled] check first, the dialog would raise the full screen
     * BOLUS_ERROR alarm for something that never reached the pump.
     */
    @Test fun commit_masterCancelled_returnsRejectedCancelledNotExecutionFailed() = runTest {
        val result = roleBranch(client = false).commit("l", command) { onError ->
            onError(MasterActionExecutor.Failure("cancelled by import", cancelled = true))
            MasterActionExecutor.ConfirmResult.Cancelled
        }
        assertThat((result as ActionProgress.Rejected).reason).isEqualTo(FailureReason.Cancelled)
        assertThat(result.detail).isEqualTo("cancelled by import")
        assertThat(result.reason.isNotDeliveryError()).isTrue()
    }

    @Test fun commit_clientReachable_delegatesToDispatcher() = runTest {
        whenever(nsClient.masterReachable).thenReturn(MutableStateFlow(true))
        whenever(dispatcher.run(command, "l")).thenReturn(ActionProgress.Applied)
        val result = roleBranch(client = true).commit("l", command) { error("master path must not run") }
        assertThat(result).isEqualTo(ActionProgress.Applied)
    }
}
