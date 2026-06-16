package app.aaps.plugins.automationstate.compose

import androidx.lifecycle.ViewModel
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AutomationStateViewModel @Inject constructor(
    private val automationState: AutomationStateInterface,
    private val preferences: Preferences
) : ViewModel() {

    private val _states = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val states: StateFlow<List<Pair<String, String>>> = _states

    val isEnabled: Boolean
        get() = preferences.get(BooleanKey.AutomationStatesEnabled)

    fun refresh() {
        _states.value = automationState.getAllStates()
    }

    fun setState(stateName: String, value: String) {
        try {
            automationState.setState(stateName, value)
            refresh()
        } catch (e: Exception) { }
    }

    fun getValues(stateName: String): List<String> = automationState.getStateValues(stateName)

    init { refresh() }
}
