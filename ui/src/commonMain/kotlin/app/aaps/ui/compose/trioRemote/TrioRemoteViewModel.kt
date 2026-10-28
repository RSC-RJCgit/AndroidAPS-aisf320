package app.aaps.ui.compose.trioRemote

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.trio.TrioRemoteCommand
import app.aaps.core.interfaces.trio.TrioRemoteControl
import app.aaps.core.interfaces.trio.TrioRemoteResult
import app.aaps.core.interfaces.trio.TrioRemoteSetup
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Trio remote screen (2026-10-29, per explicit request): temp target, carbs, bolus and override, sent to Trio the way
 * LoopFollow sends them.
 *
 * Each command is checked here, then shown for confirmation, then sent. The checks are this app's own limits (max bolus,
 * max carbs, the temp target range); Trio checks again with its own.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Inject
class TrioRemoteViewModel(
    private val trioRemoteControl: TrioRemoteControl,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    private val dateUtil: DateUtil
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrioRemoteUiState(setup = trioRemoteControl.setup(), units = profileUtil.units))
    val uiState: StateFlow<TrioRemoteUiState> = _uiState.asStateFlow()

    /** Read again when the screen is shown, so a change in the settings is seen */
    fun refresh() = _uiState.update { it.copy(setup = trioRemoteControl.setup(), units = profileUtil.units) }

    fun onTargetChange(text: String) = _uiState.update { it.copy(target = text, problem = null) }
    fun onDurationChange(text: String) = _uiState.update { it.copy(duration = text, problem = null) }
    fun onCarbsChange(text: String) = _uiState.update { it.copy(carbs = text, problem = null) }
    fun onFatChange(text: String) = _uiState.update { it.copy(fat = text, problem = null) }
    fun onProteinChange(text: String) = _uiState.update { it.copy(protein = text, problem = null) }
    fun onMinutesAgoChange(text: String) = _uiState.update { it.copy(minutesAgo = text, problem = null) }
    fun onBolusChange(text: String) = _uiState.update { it.copy(bolus = text, problem = null) }

    fun askTempTarget() {
        val state = _uiState.value
        val target = state.target.toNumber() ?: return problem(Problem.TARGET)
        val targetMgdl = profileUtil.convertToMgdl(target, state.units)
        if (targetMgdl !in HardLimits.LIMIT_TEMP_TARGET_BG) return problem(Problem.TARGET)
        val duration = state.duration.toNumber()?.roundToInt() ?: return problem(Problem.DURATION)
        if (duration !in DURATION_RANGE) return problem(Problem.DURATION)
        ask(TrioRemoteCommand.TempTarget(targetMgdl = targetMgdl.roundToInt(), durationMinutes = duration))
    }

    fun askCancelTempTarget() = ask(TrioRemoteCommand.CancelTempTarget)

    fun askMeal() {
        val state = _uiState.value
        val maxCarbs = preferences.get(IntKey.SafetyMaxCarbs)
        val carbs = state.carbs.toGrams() ?: return problem(Problem.CARBS)
        val fat = state.fat.toGrams() ?: return problem(Problem.CARBS)
        val protein = state.protein.toGrams() ?: return problem(Problem.CARBS)
        if (carbs + fat + protein == 0 || carbs > maxCarbs || fat > maxCarbs || protein > maxCarbs) return problem(Problem.CARBS)
        val minutesAgo = state.minutesAgo.toGrams() ?: return problem(Problem.MINUTES_AGO)
        if (minutesAgo > MAX_MINUTES_AGO) return problem(Problem.MINUTES_AGO)
        val eatenAt = if (minutesAgo == 0) null else dateUtil.now() - minutesAgo * 60_000L
        ask(TrioRemoteCommand.Meal(carbs = carbs, fat = fat, protein = protein, eatenAt = eatenAt))
    }

    fun askBolus() {
        val units = _uiState.value.bolus.toNumber() ?: return problem(Problem.BOLUS)
        if (units <= 0.0 || units > preferences.get(DoubleKey.SafetyMaxBolus)) return problem(Problem.BOLUS)
        ask(TrioRemoteCommand.Bolus(units))
    }

    fun askStartOverride(name: String) = ask(TrioRemoteCommand.StartOverride(name))
    fun askCancelOverride() = ask(TrioRemoteCommand.CancelOverride)

    fun dismissConfirmation() = _uiState.update { it.copy(confirming = null) }

    /** Called after the confirmation, and for a bolus after the bolus protection too */
    fun send(command: TrioRemoteCommand) {
        if (_uiState.value.sending) return
        _uiState.update { it.copy(confirming = null, sending = true, result = null) }
        viewModelScope.launch {
            val result = trioRemoteControl.send(command)
            _uiState.update { it.copy(sending = false, result = SendOutcome(command, result)) }
        }
    }

    /** A target in mg/dL, in the user's unit with the unit */
    fun targetText(mgdl: Double): String = profileUtil.fromMgdlToStringWithUnits(mgdl)

    val maxBolus: Double get() = preferences.get(DoubleKey.SafetyMaxBolus)
    val maxCarbs: Int get() = preferences.get(IntKey.SafetyMaxCarbs)

    /** Minutes between [eatenAt] and now, for the confirmation */
    fun minutesAgo(eatenAt: Long): Int = ((dateUtil.now() - eatenAt) / 60_000L).toInt()

    private fun ask(command: TrioRemoteCommand) = _uiState.update { it.copy(confirming = command, problem = null, result = null) }

    private fun problem(problem: Problem) = _uiState.update { it.copy(problem = problem) }

    /** A comma is read as a decimal point, so a value typed with either works */
    private fun String.toNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Empty is 0 */
    private fun String.toGrams(): Int? = if (isBlank()) 0 else toNumber()?.takeIf { it >= 0 }?.roundToInt()

    enum class Problem { TARGET, DURATION, CARBS, MINUTES_AGO, BOLUS }

    companion object {

        val DURATION_RANGE = 5..720
        const val MAX_MINUTES_AGO = 120
    }
}

@Immutable
data class SendOutcome(val command: TrioRemoteCommand, val result: TrioRemoteResult)

@Immutable
data class TrioRemoteUiState(
    val setup: TrioRemoteSetup,
    val units: GlucoseUnit,
    val target: String = "",
    val duration: String = "60",
    val carbs: String = "",
    val fat: String = "",
    val protein: String = "",
    val minutesAgo: String = "",
    val bolus: String = "",
    val problem: TrioRemoteViewModel.Problem? = null,
    /** The command waiting for the user's OK */
    val confirming: TrioRemoteCommand? = null,
    val sending: Boolean = false,
    val result: SendOutcome? = null
)
