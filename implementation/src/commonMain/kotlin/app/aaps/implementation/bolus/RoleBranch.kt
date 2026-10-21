package app.aaps.implementation.bolus

import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.WizardBolusExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.ClientControlActionDispatcher
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * the record is saved here and the normal Nightscout upload carries it to the master, as a 3.4 client does.
 *
 * Every other action (2026-10-22, per explicit request) is worked out here as an EXAMPLE, so its normal confirmation
 * shows: the bolus calculator, QuickWizard, insulin, carbs, scenes, pump commands. Its confirm gives nothing and shows
 * "example only" once. Commit allows only the ids prepared as Nightscout records, so an example can never be given from
 * this phone. An action that cannot be worked out on a client is refused at once with [FailureReason.ReadOnlyClient].
 */
@SingleIn(AppScope::class)
@Inject
class RoleBranch(
    private val dispatcher: ClientControlActionDispatcher,
    private val nsClient: NsClient,
    private val config: Config,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val rh: TextResolver
) {

    // A read-only client's prepares: the Nightscout records it may commit, and the examples it may not
    private val idsLock = Mutex()
    private val recordOnlyIds = mutableSetOf<Long>()
    private val exampleIds = mutableSetOf<Long>()

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
            val recordOnly = isNightscoutRecordOnly(clientCommand)
            return when (val r = masterPrepare()) {
                is WizardBolusExecutor.PrepareResult.Preview -> {
                    idsLock.withLock { if (recordOnly) recordOnlyIds += r.bolusId else exampleIds += r.bolusId }
                    ActionProgress.Prepared(r.bolusId, r.lines, r.advisorApplies, r.advisorLines, r.wizardDetail)
                }
                // An example that cannot be worked out on a client: the read-only message at once, as before
                is WizardBolusExecutor.PrepareResult.Error   ->
                    if (recordOnly) ActionProgress.Rejected(FailureReason.ExecutionFailed, r.message)
                    else ActionProgress.Rejected(FailureReason.ReadOnlyClient)
                WizardBolusExecutor.PrepareResult.NoAction   -> ActionProgress.Rejected(FailureReason.NoAction)
            }
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
        // A read-only client commits locally only an id it prepared as a Nightscout record. Anything else was an example:
        // it is never given from this phone, and the message is shown here once for every kind of action.
        if (readOnlyClient) {
            val id = commandId(clientCommand)
            val allowed = idsLock.withLock {
                val record = id != null && recordOnlyIds.remove(id)
                if (id != null) exampleIds.remove(id)
                record
            }
            if (!allowed) {
                rxBus.send(EventShowDialog.Ok(title = label, message = rh.gs(CoreUiStrings.clientcontrol_read_only_example)))
                return ActionProgress.Rejected(FailureReason.ReadOnlyExample)
            }
        }
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

    /** The prepared id a commit refers to. Null for a command that carries none. */
    private fun commandId(command: ClientControlActionDispatcher.Command): Long? = when (command) {
        is ClientControlActionDispatcher.Command.BolusCommit -> command.bolusId
        is ClientControlActionDispatcher.Command.SceneCommit -> command.bolusId
        else                                                 -> null
    }

    private fun clientBlockReason(): FailureReason =
        if (!nsClient.masterControlAllowed.value) FailureReason.ControlDisabled else FailureReason.NotReachable
}
