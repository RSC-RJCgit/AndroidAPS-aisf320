package app.aaps.plugins.automationstate.services

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.plugins.automationstate.keys.AutomationStateStringKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever

class AutomationStateServiceTest : TestBase() {

    @Mock lateinit var preferences: Preferences

    // In-memory backing store so get/put behave like real SharedPreferences
    private val prefStore = mutableMapOf<String, String>()

    private lateinit var sut: AutomationStateService

    @BeforeEach
    fun setUp() {
        prefStore.clear()

        // Stub get: return stored value or the key's defaultValue
        whenever(preferences.get(any<StringNonPreferenceKey>())).doAnswer { invocation ->
            val key = invocation.getArgument<StringNonPreferenceKey>(0)
            prefStore[key.key] ?: key.defaultValue
        }

        // Stub put: write into the backing store
        whenever(preferences.put(any<StringNonPreferenceKey>(), any<String>())).doAnswer { invocation ->
            val key = invocation.getArgument<StringNonPreferenceKey>(0)
            val value = invocation.getArgument<String>(1)
            prefStore[key.key] = value
            Unit
        }

        sut = AutomationStateService(preferences, rxBus)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun addState(name: String, vararg values: String) {
        sut.setStateValues(name, values.toList())
        sut.setState(name, values.first())
    }

    // ── Basic CRUD ────────────────────────────────────────────────────────────

    @Test
    fun `newly created state appears in getAllStates`() {
        addState("Exercise", "High", "Low")
        val names = sut.getAllStates().map { it.first }
        assertThat(names).contains("Exercise")
    }

    @Test
    fun `setState persists and getState retrieves it`() {
        addState("Exercise", "High", "Low")
        sut.setState("Exercise", "Low")
        assertThat(sut.getState("Exercise")).isEqualTo("Low")
    }

    @Test
    fun `inState returns true for current value`() {
        addState("Exercise", "High", "Low")
        sut.setState("Exercise", "High")
        assertThat(sut.inState("Exercise", "High")).isTrue()
        assertThat(sut.inState("Exercise", "Low")).isFalse()
    }

    @Test
    fun `getStateValues returns all defined values`() {
        addState("Mode", "Sport", "Rest", "Sleep")
        assertThat(sut.getStateValues("Mode")).containsExactly("Sport", "Rest", "Sleep")
    }

    @Test
    fun `hasStateValues returns false for unknown state`() {
        assertThat(sut.hasStateValues("NonExistent")).isFalse()
    }

    @Test
    fun `deleteState removes it from getAllStates`() {
        addState("Exercise", "High", "Low")
        sut.deleteState("Exercise")
        val names = sut.getAllStates().map { it.first }
        assertThat(names).doesNotContain("Exercise")
    }

    @Test
    fun `multiple states all appear in getAllStates`() {
        addState("Exercise", "High", "Low")
        addState("Meal", "Fasting", "Eating")
        val names = sut.getAllStates().map { it.first }
        assertThat(names).containsAtLeast("Exercise", "Meal")
    }

    // ── Bug regression: deleting the active value must not hide the state ─────

    @Test
    fun `state remains visible after its active value is deleted`() {
        // Set up: "Exercise" with two values, active = "High"
        addState("Exercise", "High", "Low")
        assertThat(sut.getState("Exercise")).isEqualTo("High")

        // Delete "High" (the active value) by saving only "Low"
        sut.setStateValues("Exercise", listOf("Low"))

        // State must still appear in getAllStates — this was the bug
        val states = sut.getAllStates()
        val stateNames = states.map { it.first }
        assertThat(stateNames).contains("Exercise")
    }

    @Test
    fun `active value is empty string after its value is deleted`() {
        addState("Exercise", "High", "Low")
        sut.setStateValues("Exercise", listOf("Low"))  // removes "High" which was active

        val currentValue = sut.getAllStates().first { it.first == "Exercise" }.second
        assertThat(currentValue).isEmpty()
    }

    @Test
    fun `remaining values are correct after active value deleted`() {
        addState("Exercise", "High", "Low")
        sut.setStateValues("Exercise", listOf("Low"))

        assertThat(sut.getStateValues("Exercise")).containsExactly("Low")
    }

    @Test
    fun `can set new state after active value was deleted`() {
        addState("Exercise", "High", "Low")
        sut.setStateValues("Exercise", listOf("Low"))   // clears active

        // Should now be able to set "Low" as active without error
        sut.setState("Exercise", "Low")
        assertThat(sut.getState("Exercise")).isEqualTo("Low")
        assertThat(sut.inState("Exercise", "Low")).isTrue()
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `getState returns empty string for unknown state`() {
        assertThat(sut.getState("NonExistent")).isEmpty()
    }

    @Test
    fun `setState trims whitespace from name and value`() {
        sut.setStateValues("Exercise", listOf("High", "Low"))
        sut.setState("  Exercise  ", "  High  ")
        assertThat(sut.getState("Exercise")).isEqualTo("High")
    }

    @Test
    fun `clearStates removes all current state values`() {
        addState("Exercise", "High", "Low")
        addState("Meal", "Fasting", "Eating")
        sut.clearStates()
        // stateValues definitions survive; current values are cleared
        assertThat(sut.getState("Exercise")).isEmpty()
        assertThat(sut.getState("Meal")).isEmpty()
    }
}
