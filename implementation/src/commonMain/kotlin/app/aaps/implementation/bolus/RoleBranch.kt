package app.aaps.implementation.bolus

import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.WizardBolusExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.ClientControlActionDispatcher
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The shared role branch for the two-step relayed execution path — extracted from [BatchExecutorImpl] and
 * [WizardExecutorImpl], which were structurally identical twins. On a paired CLIENT the request is routed to the
 * master over the signed round-trip (gated on `masterReachable` per the offline-block decision); on a MASTER it
 * runs against the local executor. The only per-path difference is the client [ClientControlActionDispatcher.Command]
 * to dispatch and the master executor call to make — both supplied by the caller — so the gate, the round-trip,
 * and the `PrepareResult`/`ConfirmResult` → [ActionProgress] mapping live exactly once here.
 *
 * A client set to read-only ([BooleanKey.NsClientReadOnlyClient], for a master that takes no remote control, such as
 * 3.4) sends no command. A batch made only of a profile switch, a temp target or a note runs on the LOCAL executor:
 * the record is saved here and the normal Nightscout upload carries it to the master, as a 3.4 client does. Anything
 * else (insulin, carbs, pump commands, running mode) is refused at once with [FailureReason.ReadOnlyClient].
 *
 * The bolus calculator is the one exception on the prepare side (2026-10-16): a read-only client calculates it
 * locally as an example, so the confirmation lines can be shown. Its commit is always refused (`WizardExecutorImpl`),
 * so that example is never given.
 */
@SingleIn(AppScope::class)
@Inject
class RoleBranch(
    private val dispatcher: ClientControlActionDispatcher,
    private val nsClient: NsClient,
    private val config: Config,
    private val preferences: Preferences
) {

    val readOnlyClient: Boolean
        get() = config.AAPSCLIENT && preferences.get(BooleanKey.NsClientReadOnlyClient)

    /**
     * Run a PREPARE: client → [clientCommand] over the round-trip (offline → [FailureReason.NotReachable]);
     * master → [masterPrepare] locally, mapping its [WizardBolusExecutor.PrepareResult] to [ActionProgress].
     * [label] shows in the round-trip modal.
     */
    suspend fun prepare(
        label: String,
        clientCommand: ClientControlActionDispatcher.Command,
        masterPrepare: suspend () -> WizardBolusExecutor.PrepareResult
    ): ActionProgress {
        if (readOnlyClient) {
            if (!isNightscoutRecordOnly(clientCommand) && clientCommand !is ClientControlActionDispatcher.Command.WizardPrepare)
                return ActionProgress.Rejected(FailureReason.ReadOnlyClient)
        } else if (config.AAPSCLIENT) {
            if (!nsClient.masterReachable.value) return ActionProgress.Rejected(clientBlockReason())
            return dispatcher.run(clientCommand, label)
        }
        // Master, or a read-only client with a record-only batch: prepare locally — NO app-level modal (the caller renders the returned lines as the confirmation).
        return when (val r = masterPrepare()) {
            is WizardBolusExecutor.PrepareResult.Preview -> ActionProgress.Prepared(r.bolusId, r.lines, r.advisorApplies, r.advisorLines, r.wizardDetail)
            is WizardBolusExecutor.PrepareResult.Error   -> ActionProgress.Rejected(FailureReason.ExecutionFailed, r.message)
            WizardBolusExecutor.PrepareResult.NoAction   -> ActionProgress.Rejected(FailureReason.NoAction)
        }
    }

    /**
     * Run a COMMIT: client → [clientCommand] over the round-trip (offline → [FailureReason.NotReachable]);
     * master → [masterConfirm] locally. The async delivery failure is alarmed centrally by the executor, so the
     * [WizardBolusExecutor.Failure] captured here only carries a synchronous rejection (it doesn't double-report).
     *
     * A command dropped from the queue on purpose fills that same comment, so
     * [WizardBolusExecutor.ConfirmResult.Cancelled] is checked FIRST - it is not a delivery failure and must not
     * reach the caller as one. The return value is checked rather than `failure.cancelled` because a drop does not
     * always produce a failure at all: a drain that reports `success = true` (the "pump not configured" path in
     * `CommandExecutor`) never calls `onError`, and reading only the callback would report [ActionProgress.Applied]
     * for a command that never left the queue.
     */
    suspend fun commit(
        label: String,
        clientCommand: ClientControlActionDispatcher.Command,
        masterConfirm: suspend ((WizardBolusExecutor.Failure) -> Unit) -> WizardBolusExecutor.ConfirmResult
    ): ActionProgress {
        // A read-only client commits locally. Only a batch that passed [isNightscoutRecordOnly] in prepare was parked
        // here, so any other id finds nothing and comes back as NoPendingBolus.
        if (config.AAPSCLIENT && !readOnlyClient) {
            if (!nsClient.masterReachable.value) return ActionProgress.Rejected(clientBlockReason())
            return dispatcher.run(clientCommand, label)
        }
        // Master, or a read-only client: deliver the parked dose/bundle locally.
        var failure: WizardBolusExecutor.Failure? = null
        val result = masterConfirm { failure = it }
        val err = failure?.comment
        return when {
            result is WizardBolusExecutor.ConfirmResult.NoPending -> ActionProgress.Rejected(FailureReason.NoPendingBolus)
            result is WizardBolusExecutor.ConfirmResult.Cancelled -> ActionProgress.Rejected(FailureReason.Cancelled, err)
            err != null                                           -> ActionProgress.Rejected(FailureReason.ExecutionFailed, err)
            else                                                  -> ActionProgress.Applied
        }
    }

    /**
     * Why a client request is blocked, when [NsClient.masterReachable] is false. [NsClient.masterControlAllowed]
     * folds into masterReachable, so here it is the discriminator: the master is reachable but has remote control
     * turned OFF ([FailureReason.ControlDisabled]) vs. simply unreachable/unpaired ([FailureReason.NotReachable]).
     * The caller maps the reason to the correct user-facing message (offline vs. control disabled).
     */
    /**
     * True for a batch that a read-only client may save itself and send through Nightscout: only a profile switch,
     * a temp target and therapy events (notes, sensor change, ...). No insulin, no carbs, nothing for the pump.
     */
    private fun isNightscoutRecordOnly(command: ClientControlActionDispatcher.Command): Boolean =
        command is ClientControlActionDispatcher.Command.BatchPrepare &&
            command.actions.isNotEmpty() &&
            command.actions.all {
                it is BatchAction.ProfileSwitch || it is BatchAction.TempTarget ||
                    it is BatchAction.TherapyEvent || it is BatchAction.TherapyEventEdit
            }

    private fun clientBlockReason(): FailureReason =
        if (!nsClient.masterControlAllowed.value) FailureReason.ControlDisabled else FailureReason.NotReachable
}
