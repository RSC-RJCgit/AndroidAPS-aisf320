package app.aaps.plugins.sync.nsclientV3.trioremote

import app.aaps.core.data.model.CA
import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.trioremote.TrioRemoteMessage
import app.aaps.core.objects.constraints.ConstraintObject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Apply Trio commands only to the local Virtual master, using normal constraints and action handlers. */
@SingleIn(AppScope::class)
@Inject
class TrioSimulatorActionExecutor(
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val constraintsChecker: ConstraintsChecker,
    private val persistenceLayer: PersistenceLayer,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val masterActionExecutor: () -> MasterActionExecutor
) {
    data class Outcome(val applied: Boolean, val detail: String)

    private fun allowed(): Boolean = config.APS && !config.AAPSCLIENT && preferences.get(BooleanKey.TrioSimulatorReceiver) && activePlugin.activePumpInternal is VirtualPump

    internal suspend fun execute(action: TrioSimulatorProtocol.Action): Outcome {
        if (!allowed()) return Outcome(false, "Virtual master receiver is no longer enabled")
        return try {
            val message = action.message
            val note = "TrioSim: ${action.summary} (${message.user})"
            when (message.commandType) {
                TrioRemoteMessage.BOLUS -> batch(listOf(BatchAction.Bolus(
                    insulin = requireNotNull(message.bolusAmount), carbs = 0, carbsTimeOffsetMinutes = 0,
                    carbsDurationHours = 0, recordOnly = false, notes = note, timestamp = 0L, iCfg = null
                )), action.summary, dose = true)
                TrioRemoteMessage.MEAL -> {
                    // Trio's meal command records nutrition; it does not request a manual calculator bolus.
                    val carbs = message.carbs ?: 0
                    val capped = constraintsChecker.applyCarbsConstraints(ConstraintObject(carbs, aapsLogger)).value()
                    if (capped != carbs) return Outcome(false, "Meal exceeds the Virtual master's carb limit")
                    val at = message.scheduledTimeSeconds?.let { (it * 1000).toLong() } ?: dateUtil.now()
                    if (at < dateUtil.now() - 24 * 60 * 60_000L || at > dateUtil.now() + 24 * 60 * 60_000L)
                        return Outcome(false, "Meal time is outside the allowed day")
                    if (!allowed()) return Outcome(false, "Virtual master receiver is no longer enabled")
                    persistenceLayer.insertOrUpdateCarbs(CA(
                        timestamp = at, duration = 0L, amount = carbs.toDouble(), notes = note,
                        fatGrams = message.fat?.toDouble(), proteinGrams = message.protein?.toDouble(),
                        sourceId = "trio-sim-${action.fingerprint}"
                    ), Action.CARBS, Sources.NSClient, note)
                    Outcome(true, action.summary)
                }
                TrioRemoteMessage.TEMP_TARGET -> {
                    val target = requireNotNull(message.target).toDouble()
                    batch(listOf(BatchAction.TempTarget(
                        TT.Reason.CUSTOM.text, target, target,
                        requireNotNull(message.duration), 0, note
                    )), action.summary)
                }
                TrioRemoteMessage.CANCEL_TEMP_TARGET -> batch(listOf(BatchAction.TempTarget(TT.Reason.CUSTOM.text, 0.0, 0.0, 0, 0, note)), action.summary)
                TrioRemoteMessage.START_OVERRIDE -> startOverride(message, note)
                TrioRemoteMessage.CANCEL_OVERRIDE -> cancelOverride(note)
                else -> Outcome(false, "Unsupported simulator action")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Outcome(false, "Action failed. Inspect the Virtual master's history before sending another command.")
        }
    }

    private suspend fun batch(actions: List<BatchAction>, summary: String, dose: Boolean = false): Outcome {
        if (!allowed()) return Outcome(false, "VirtualPump is required")
        val executor = masterActionExecutor()
        val prepared = executor.prepareBatch(actions, virtualOnly = true)
        if (prepared is MasterActionExecutor.PrepareResult.Error) return Outcome(false, prepared.message)
        if (prepared !is MasterActionExecutor.PrepareResult.Preview) return Outcome(false, "No action after the master's checks")
        if (!allowed()) {
            executor.discard(prepared.bolusId)
            return Outcome(false, "VirtualPump changed before confirmation")
        }
        val completion = CompletableDeferred<Outcome>()
        val detail = if (dose) "Bolus ${prepared.insulin} U applied on VirtualPump" else summary
        val confirmed = executor.confirm(prepared.bolusId, Sources.NSClient,
            onError = { completion.complete(Outcome(false, it.comment)) },
            onSuccess = { completion.complete(Outcome(true, detail)) }
        )
        if (completion.isCompleted) return completion.await()
        if (confirmed != MasterActionExecutor.ConfirmResult.Delivered) return Outcome(false, "Master refused or cancelled this action")
        if (!dose) return Outcome(true, detail)
        return withTimeoutOrNull(120_000L) { completion.await() }
            ?: Outcome(false, "Virtual delivery result is unknown. Check history; do not resend automatically.")
    }

    private suspend fun startOverride(message: TrioRemoteMessage, note: String): Outcome {
        if (message.overrideName != "Simulator override") return Outcome(false, "Only the Simulator override test preset is supported")
        if (preferences.get(StringNonKey.TrioSimulatorOverride).isNotBlank()) return Outcome(false, "Cancel the active simulator override first")
        val previous = profileFunction.getRequestedProfile() ?: return Outcome(false, "No active profile")
        if (previous.duration != 0L || previous.timeshift % 3_600_000L != 0L)
            return Outcome(false, "Use a permanent profile before starting the override test")
        val startedAt = dateUtil.now()
        val result = batch(listOf(BatchAction.ProfileSwitch(100, (previous.timeshift / 3_600_000L).toInt(), 60, previous.profileName, note)), "Simulator override: 100% for 60 min")
        if (result.applied) preferences.put(StringNonKey.TrioSimulatorOverride, buildJsonObject {
            put("name", previous.profileName)
            put("percentage", previous.percentage)
            put("shift", previous.timeshift / 3_600_000L)
            put("startedAt", startedAt)
        }.toString())
        return result
    }

    private suspend fun cancelOverride(note: String): Outcome {
        val saved = runCatching { Json.parseToJsonElement(preferences.get(StringNonKey.TrioSimulatorOverride)) as JsonObject }.getOrNull()
            ?: return Outcome(false, "No simulator override to cancel")
        val started = (saved["startedAt"] as? JsonPrimitive)?.longOrNull ?: return Outcome(false, "Missing override state")
        val current = profileFunction.getRequestedProfile() ?: return Outcome(false, "No active profile")
        if (dateUtil.now() >= started + 60 * 60_000L) {
            preferences.put(StringNonKey.TrioSimulatorOverride, "")
            return Outcome(true, "Simulator override has already expired")
        }
        if (current.profileName != saved.text("name") || current.percentage != 100 || current.duration != 60 * 60_000L ||
            current.timestamp !in (started - 1000)..(started + 10_000))
            return Outcome(false, "Profile changed after the simulator override; kept your newer selection")
        val percent = (saved["percentage"] as? JsonPrimitive)?.intOrNull ?: return Outcome(false, "Missing previous percentage")
        val shift = (saved["shift"] as? JsonPrimitive)?.intOrNull ?: return Outcome(false, "Missing previous time shift")
        val result = batch(listOf(BatchAction.ProfileSwitch(percent, shift, 0, saved.text("name"), note)), "Simulator override cancelled; previous profile restored")
        if (result.applied) preferences.put(StringNonKey.TrioSimulatorOverride, "")
        return result
    }
}
