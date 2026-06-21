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

    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick

    private val _states = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val states: StateFlow<List<Pair<String, String>>> = _states

    val isEnabled: Boolean
        get() = preferences.get(BooleanKey.AutomationStatesEnabled)

    fun setEnabled(enabled: Boolean) {
        preferences.put(BooleanKey.AutomationStatesEnabled, enabled)
    }

    fun refresh() {
        // Force new list reference for Compose recomposition
        _states.value = emptyList(); _states.value = automationState.getAllStates().toList()
        _refreshTick.value += 1
    }

    fun setState(stateName: String, value: String) {
        try {
            automationState.setState(stateName, value)
            refresh()
        } catch (e: Exception) { }
    }

    fun addState(name: String, values: List<String>) {
        automationState.setStateValues(name, values)
        try { automationState.setState(name, values.first()) } catch (e: Exception) { }
        refresh()
    }

    fun updateStateValues(name: String, values: List<String>) {
        if (values.isEmpty()) return // safety guard
        automationState.setStateValues(name, values)
        val current = automationState.getState(name)
        if (current.isEmpty() || !values.contains(current)) {
            try { automationState.setState(name, values.first()) } catch (e: Exception) { }
        }
        refresh()
    }

    fun deleteState(name: String) {
        automationState.deleteState(name)
        refresh()
    }

    fun getValues(stateName: String): List<String> = automationState.getStateValues(stateName)

    init { refresh() }
}