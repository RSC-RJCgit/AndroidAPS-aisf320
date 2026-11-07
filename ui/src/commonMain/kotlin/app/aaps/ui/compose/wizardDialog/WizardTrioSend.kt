package app.aaps.ui.compose.wizardDialog

import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.trio.TrioRemoteCommand
import app.aaps.core.interfaces.trio.TrioRemoteControl
import app.aaps.core.interfaces.trio.TrioRemoteResult
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.trioRemote.TrioRemoteViewModel
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.objects.wizard.SPLIT_LEFTOVER_DEADLINE_MINUTES
import app.aaps.core.objects.wizard.TrioLaterTarget
import app.aaps.core.objects.wizard.WarsawFpuPlan
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * One "Send to Trio?" confirmation for [send], then the meal and then the bolus (2026-11-07, per explicit request). Shared
 * by the bolus calculator's Send to Trio button and the QuickWizard Trio buttons. This app's max bolus and max carbs are
 * checked first; Trio checks again with its own limits. [onConfirmed] runs when the user confirms, before sending.
 */
internal fun offerWizardTrioSend(
    send: WizardTrioSend,
    icon: ImageVector,
    trioRemoteControl: TrioRemoteControl,
    preferences: Preferences,
    rh: TextResolver,
    rxBus: RxBus,
    dateUtil: DateUtil,
    appScope: CoroutineScope,
    /** Later parts shown on the confirmation (2026-11-07): split leftover, fat/protein doses, extended carbs */
    laterLines: List<ConfirmationLine> = emptyList(),
    onConfirmed: () -> Unit = {},
    /** Runs once the meal and the bolus now were sent (or there were none), to start the later parts */
    afterSent: () -> Unit = {}
) {
    val title = rh.gs(UiStrings.trio_remote_confirm_title)
    val maxBolus = preferences.get(DoubleKey.SafetyMaxBolus)
    val maxCarbs = preferences.get(IntKey.SafetyMaxCarbs)
    val meal = send.meal
    val problem = when {
        send.isEmpty && laterLines.isEmpty()  -> rh.gs(CoreUiStrings.no_action_selected)
        (send.bolus?.units ?: 0.0) > maxBolus ->
            rh.gs(UiStrings.trio_remote_problem_bolus, rh.gs(UiStrings.trio_remote_insulin_value, twoDecimals(maxBolus)))

        meal != null && (meal.carbs > maxCarbs || meal.fat > maxCarbs || meal.protein > maxCarbs) ->
            rh.gs(UiStrings.trio_remote_problem_carbs, maxCarbs)

        else                                  -> null
    }
    if (problem != null) {
        rxBus.send(EventShowDialog.Ok(title = title, message = problem))
        return
    }
    val setup = trioRemoteControl.setup()
    val lines = buildList {
        send.meal?.let { add(ConfirmationLine(ConfirmationRole.CARBS, trioCommandText(it, rh, dateUtil))) }
        send.bolus?.let { add(ConfirmationLine(ConfirmationRole.BOLUS, trioCommandText(it, rh, dateUtil))) }
        if (send.eCarbsNotSent > 0) add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.wizard_trio_ecarbs_not_sent, send.eCarbsNotSent)))
        if (send.futureCarbTimeIgnored) add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.wizard_trio_carb_time_now)))
        addAll(laterLines)
    }
    rxBus.send(
        EventShowDialog.OkCancel(
            title = title, message = "",
            secondMessage = rh.gs(if (setup.simulator) UiStrings.trio_simulator_banner else UiStrings.trio_remote_confirm_note),
            icon = icon, confirmationLines = lines,
            onOk = {
                onConfirmed()
                appScope.launch { if (sendWizardToTrio(send, setup.simulator, title, trioRemoteControl, rh, rxBus, dateUtil)) afterSent() }
            }
        )
    )
}

/**
 * The meal first, then the bolus; stops at the first one not sent, so a bolus never goes without its meal. True when all
 * were sent (the later parts start only then).
 */
private suspend fun sendWizardToTrio(
    send: WizardTrioSend, simulator: Boolean, title: String, trioRemoteControl: TrioRemoteControl, rh: TextResolver, rxBus: RxBus, dateUtil: DateUtil
): Boolean {
    val done = mutableListOf<String>()
    for (command in listOfNotNull(send.meal, send.bolus)) {
        val text = trioCommandText(command, rh, dateUtil)
        when (val result = trioRemoteControl.send(command, expectedSimulator = simulator)) {
            TrioRemoteResult.Sent         -> done += text
            is TrioRemoteResult.Simulated -> done += rh.gs(UiStrings.trio_simulator_result, result.detail)
            is TrioRemoteResult.Failed    -> {
                val failed = rh.gs(UiStrings.trio_remote_failed, text, result.reason)
                val message = if (done.isEmpty()) failed else rh.gs(UiStrings.trio_remote_sent, done.joinToString(", ")) + "\n" + failed
                rxBus.send(EventShowDialog.Ok(title = title, message = message))
                return false
            }
        }
    }
    if (done.isNotEmpty()) rxBus.send(EventShowDialog.Ok(title = title, message = rh.gs(UiStrings.trio_remote_sent, done.joinToString(", "))))
    return true
}

/**
 * Later doses to Trio (2026-11-07, per explicit request), see [TrioLaterTarget]. Trio loops every 5 minutes, so its
 * insulinReq and predictions count as current for 10 minutes after Trio worked them out.
 */
internal fun trioLaterTarget(
    trioRemoteControl: TrioRemoteControl, deviceStatus: ProcessedDeviceStatusData, simulator: Boolean, dateUtil: DateUtil
): TrioLaterTarget {
    fun current(at: Long): Long {
        val now = dateUtil.now()
        return if (at > 0L && at <= now && now - at <= TRIO_LOOP_FRESH_MS) now else at
    }
    return TrioLaterTarget(
        sendBolus = { units ->
            when (val result = trioRemoteControl.send(TrioRemoteCommand.Bolus(units), expectedSimulator = simulator)) {
                TrioRemoteResult.Sent, is TrioRemoteResult.Simulated -> null
                is TrioRemoteResult.Failed                           -> result.reason
            }
        },
        insulinReq = {
            val at = deviceStatus.openAPSData.clockSuggested
            deviceStatus.openAPSData.suggested?.insulinReq?.takeIf { it.isFinite() }
                ?.let { current(at) to (max(0.0, it) * 1000).roundToLong() }
        },
        lowestPrediction = {
            val at = deviceStatus.openAPSData.clockSuggested
            val predictions = deviceStatus.openAPSData.suggested?.predBGs
            // IOB, COB and UAM over the next hour (13 points, 5 min apart). Not ZT: it assumes no insulin at all.
            listOfNotNull(predictions?.IOB, predictions?.COB, predictions?.UAM).flatMap { it.take(13) }.minOrNull()
                ?.let { lowest -> current(at) to (lowest / Constants.MMOLL_TO_MGDL * 1000).roundToLong() }
        },
    )
}

private const val TRIO_LOOP_FRESH_MS = 10 * 60_000L

/**
 * The later parts of one Send to Trio (2026-11-07, per explicit request), from the bolus calculator or a QuickWizard
 * entry: the split leftover, the fat/protein doses and the extended carbs. [wizard] is the calculation they come from
 * (null for a carbs-only entry, which can have extended carbs only).
 */
internal class TrioLaterPlan(
    val wizard: BolusWizard?,
    val bolusNow: Double,
    val splitLeftover: Boolean,
    val fpuPlan: WarsawFpuPlan?,
    val eCarbs: Int,
    val eCarbsDelayMinutes: Int,
    val eCarbsDurationHours: Int,
    val source: Sources,
) {

    private val splitTexts: List<String> = if (splitLeftover) wizard?.splitLeftoverTexts().orEmpty() else emptyList()

    val isEmpty: Boolean get() = splitTexts.isEmpty() && fpuPlan == null && eCarbs <= 0

    /** About when the last part can still be sent: the split time limit, the last fat/protein dose and its retry, the last carbs part */
    fun lastAt(now: Long): Long = maxOf(
        if (splitTexts.isNotEmpty()) now + SPLIT_LEFTOVER_DEADLINE_MINUTES * 60_000L else 0L,
        fpuPlan?.let { now + (it.durationMinutes + 30) * 60_000L } ?: 0L,
        if (eCarbs > 0) now + (eCarbsDelayMinutes.coerceAtLeast(0) + (trioECarbsParts(eCarbs, eCarbsDurationHours).size - 1) * 60 + 10) * 60_000L else 0L
    )

    /** The confirmation lines: each later part, until when the app must keep running, and the iPhone keep-awake switch */
    fun lines(rh: TextResolver, dateUtil: DateUtil, iosKeepAwake: Boolean): List<ConfirmationLine> = buildList {
        splitTexts.forEach { add(ConfirmationLine(ConfirmationRole.BOLUS, rh.gs(UiStrings.wizard_trio_later, it))) }
        fpuPlan?.let {
            val planText = rh.gs(InterfacesStrings.wizard_warsaw_plan, it.totalInsulin, it.numDoses, it.durationMinutes / 60)
            add(ConfirmationLine(ConfirmationRole.BOLUS, rh.gs(UiStrings.wizard_trio_later, planText)))
        }
        if (eCarbs > 0)
            add(ConfirmationLine(ConfirmationRole.CARBS, rh.gs(UiStrings.wizard_trio_ecarbs_later, eCarbs,
                trioECarbsParts(eCarbs, eCarbsDurationHours).size, eCarbsDelayMinutes.coerceAtLeast(0))))
        if (!isEmpty) {
            add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.wizard_trio_later_until, dateUtil.timeString(lastAt(dateUtil.now())))))
            if (iosKeepAwake) add(ConfirmationLine(ConfirmationRole.WARNING, rh.gs(UiStrings.wizard_trio_keep_awake_ios)))
        }
    }
}

/**
 * The later Trio parts waiting on this phone (2026-11-07, per explicit request). One set at a time: a newer Send to Trio
 * cancels the parts of the one before. [cancel] is the "Cancel later parts" button on the Trio remote screen.
 *
 * On an iPhone, while parts are waiting, the OrangeLink heartbeat is switched off and silent sound on: the heartbeat
 * only wakes the app for a few seconds a minute, which may be too short to send a part, while silent sound keeps it
 * running. Both settings are set back when the last part is due, or on cancel. (Switching the heartbeat off forgets the
 * device, so when it is switched back on it connects to the nearest one again.)
 */
internal object TrioLaterParts {

    private val _until = MutableStateFlow(0L)

    /** About when the last part waiting can be sent; 0 when none is waiting */
    val until: StateFlow<Long> = _until

    private var wizard: BolusWizard? = null
    private var restore: (() -> Unit)? = null
    private var finishJob: Job? = null

    /** True when starting later parts on this phone would switch the iPhone keep-awake settings */
    fun keepAwakeNeeded(config: Config, preferences: Preferences): Boolean =
        config.platform == AppPlatform.Ios &&
            (preferences.get(BooleanKey.IosRileyLinkHeartbeat) || !preferences.get(BooleanKey.IosKeepRunningInBackground))

    /** Starts [plan]'s parts, after the meal and the bolus now were sent; cancels any still waiting from before */
    fun start(
        plan: TrioLaterPlan, trioRemoteControl: TrioRemoteControl, deviceStatus: ProcessedDeviceStatusData, preferences: Preferences,
        config: Config, rh: TextResolver, rxBus: RxBus, dateUtil: DateUtil, appScope: CoroutineScope
    ) {
        cancel()
        if (plan.isEmpty) return
        val simulator = trioRemoteControl.setup().simulator
        plan.wizard?.let { w ->
            w.trioLater = trioLaterTarget(trioRemoteControl, deviceStatus, simulator, dateUtil)
            appScope.launch {
                if (plan.splitLeftover)
                    w.scheduleLeftoverSplit(
                        w.calculatedTotalInsulin, plan.bolusNow, w.warsawIobBaseline, plan.source,
                        rapidFall = w.rapidFallForSplit, walkingSoonHeld = w.walkingSoonHeldUnits,
                        splitOverMax = w.splitOverMax, intervalMinutes = w.splitIntervalMinutes,
                    )
                plan.fpuPlan?.let { w.scheduleWarsawDoses(it, w.warsawIobBaseline, plan.source, w.warsawRetryLater) }
            }
        }
        scheduleTrioECarbs(plan.eCarbs, plan.eCarbsDelayMinutes, plan.eCarbsDurationHours, simulator, trioRemoteControl, rh, rxBus, dateUtil, appScope)
        wizard = plan.wizard
        val until = plan.lastAt(dateUtil.now())
        _until.value = until
        if (keepAwakeNeeded(config, preferences)) {
            val heartbeat = preferences.get(BooleanKey.IosRileyLinkHeartbeat)
            val silent = preferences.get(BooleanKey.IosKeepRunningInBackground)
            preferences.put(BooleanKey.IosRileyLinkHeartbeat, false)
            preferences.put(BooleanKey.IosKeepRunningInBackground, true)
            restore = {
                preferences.put(BooleanKey.IosKeepRunningInBackground, silent)
                preferences.put(BooleanKey.IosRileyLinkHeartbeat, heartbeat)
            }
        }
        finishJob = appScope.launch {
            while (true) {
                val wait = until - dateUtil.now()
                if (wait <= 0L) break
                delay(min(wait, 60_000L))
            }
            finish()
        }
    }

    /** Cancels every later part still waiting, and sets the iPhone keep-awake settings back */
    fun cancel() {
        wizard?.cancelLeftoverSplit()
        wizard?.cancelWarsawDoses()
        TrioECarbsGate.next()
        finishJob?.cancel()
        finish()
    }

    private fun finish() {
        finishJob = null
        restore?.invoke()
        restore = null
        wizard = null
        _until.value = 0L
    }
}

/** A newer Send to Trio cancels extended carbs still waiting from an earlier one */
private object TrioECarbsGate {

    private var current = 0L

    fun next(): Long = ++current

    fun isCurrent(token: Long): Boolean = token == current
}

/** How [grams] of extended carbs over [durationHours] are sent: one part each hour, the first at the start */
internal fun trioECarbsParts(grams: Int, durationHours: Int): List<Int> {
    val parts = durationHours.coerceAtLeast(1)
    return (0 until parts).map { grams / parts + if (it < grams % parts) 1 else 0 }.filter { it > 0 }
}

/**
 * Extended carbs to Trio (2026-11-07, per explicit request): Trio has no extended carbs, so they go as ordinary carbs, one
 * part each hour from [delayMinutes] after now. Each part is sent when it is due: Trio refuses a command more than 10
 * minutes old, so a part more than 10 minutes late (the app was not running) is not sent, and the user is told.
 * A newer Send to Trio cancels the parts still waiting.
 */
internal fun scheduleTrioECarbs(
    grams: Int, delayMinutes: Int, durationHours: Int, simulator: Boolean,
    trioRemoteControl: TrioRemoteControl, rh: TextResolver, rxBus: RxBus, dateUtil: DateUtil, appScope: CoroutineScope
) {
    val token = TrioECarbsGate.next()
    if (grams <= 0) return
    val start = dateUtil.now() + delayMinutes.coerceAtLeast(0) * 60_000L
    val title = rh.gs(UiStrings.trio_remote_confirm_title)
    trioECarbsParts(grams, durationHours).forEachIndexed { index, part ->
        val dueAt = start + index * 60 * 60_000L
        appScope.launch {
            while (true) {
                if (!TrioECarbsGate.isCurrent(token)) return@launch
                val wait = dueAt - dateUtil.now()
                if (wait <= 0L) break
                delay(min(wait, 120_000L))
            }
            val command = TrioRemoteCommand.Meal(carbs = part, fat = 0, protein = 0, eatenAt = null)
            val text = trioCommandText(command, rh, dateUtil)
            if (dateUtil.now() - dueAt > 10 * 60_000L) {
                rxBus.send(EventShowDialog.Ok(title = title, message = rh.gs(UiStrings.trio_remote_failed, text, rh.gs(UiStrings.wizard_trio_part_late))))
                return@launch
            }
            val result = trioRemoteControl.send(command, expectedSimulator = simulator)
            if (result is TrioRemoteResult.Failed)
                rxBus.send(EventShowDialog.Ok(title = title, message = rh.gs(UiStrings.trio_remote_failed, text, result.reason)))
        }
    }
}

private fun trioCommandText(command: TrioRemoteCommand, rh: TextResolver, dateUtil: DateUtil): String = when (command) {
    is TrioRemoteCommand.Meal  -> command.eatenAt?.let { eatenAt ->
        rh.gs(UiStrings.trio_remote_command_meal_earlier, command.carbs, command.fat, command.protein, ((dateUtil.now() - eatenAt) / 60_000L).toInt())
    } ?: rh.gs(UiStrings.trio_remote_command_meal, command.carbs, command.fat, command.protein)

    is TrioRemoteCommand.Bolus -> rh.gs(UiStrings.trio_remote_command_bolus, rh.gs(UiStrings.trio_remote_insulin_value, twoDecimals(command.units)))
    else                       -> command.toString()
}

/** Two decimals with a point, the way Trio is sent it (as on the Trio remote screen) */
private fun twoDecimals(value: Double): String {
    val hundredths = kotlin.math.round(value * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/**
 * What the bolus wizard sends to Trio (2026-11-07, per explicit request). On a read-only client with Trio remote control
 * set up, the wizard's result goes to Trio instead of being only an example: the meal first, then the bolus, as the
 * Trio remote screen sends them.
 *
 * Fat and protein go as the grams typed, not as this app's split doses, because Trio spreads them with its own settings.
 * Trio has no extended carbs, so those are left out and named on the confirmation.
 */
internal data class WizardTrioSend(
    val meal: TrioRemoteCommand.Meal?,
    val bolus: TrioRemoteCommand.Bolus?,
    /** Extended carbs the wizard worked out, which are not sent */
    val eCarbsNotSent: Int,
    /** A carb time in the future: Trio takes only "eaten earlier", so the meal goes as eaten now */
    val futureCarbTimeIgnored: Boolean
) {

    val isEmpty: Boolean get() = meal == null && bolus == null
}

/**
 * [insulin] is the wizard's final dose, [carbs] its carbs now, [carbTimeMinutes] its carb time (negative = eaten earlier).
 * An earlier carb time goes as Trio's "eaten at", at most [TrioRemoteViewModel.MAX_MINUTES_AGO] minutes back.
 */
internal fun wizardTrioSend(insulin: Double, carbs: Int, fat: Int, protein: Int, eCarbs: Int, carbTimeMinutes: Int, now: Long): WizardTrioSend {
    // Two decimals, as the Trio remote screen sends it
    val units = (insulin.coerceAtLeast(0.0) * 100).roundToLong() / 100.0
    val c = carbs.coerceAtLeast(0)
    val f = fat.coerceAtLeast(0)
    val p = protein.coerceAtLeast(0)
    val eatenAt = if (carbTimeMinutes < 0) now - minOf(-carbTimeMinutes, TrioRemoteViewModel.MAX_MINUTES_AGO) * 60_000L else null
    return WizardTrioSend(
        meal = if (c + f + p > 0) TrioRemoteCommand.Meal(carbs = c, fat = f, protein = p, eatenAt = eatenAt) else null,
        bolus = if (units > 0.0) TrioRemoteCommand.Bolus(units) else null,
        eCarbsNotSent = eCarbs.coerceAtLeast(0),
        futureCarbTimeIgnored = carbTimeMinutes > 0 && c + f + p > 0
    )
}
