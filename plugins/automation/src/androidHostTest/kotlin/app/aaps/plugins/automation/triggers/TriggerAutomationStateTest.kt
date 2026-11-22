package app.aaps.plugins.automation.triggers

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.automation.asJsonObject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever

class TriggerAutomationStateTest : TriggerTestBase() {

    @BeforeEach
    fun states() {
        whenever(preferences.get(BooleanKey.AutomationStatesEnabled)).thenReturn(true)
        whenever(preferences.get(StringNonKey.AutomationCurrentStates)).thenReturn("""{"MJ":"MJ3","LowBG":"NO50rec"}""")
        whenever(preferences.get(StringNonKey.AutomationStateValues))
            .thenReturn("""{"MJ":["NOMJremains","MJ active","MJ3"],"LowBG":["50recent","NO50rec"]}""")
    }

    private fun trigger(name: String, value: String) =
        TriggerAutomationState(triggerDeps).also { it.stateName.value = name; it.stateValue.value = value }

    @Test
    fun matchesTheCurrentValueOnly() = runTest {
        assertThat(trigger("MJ", "MJ3").shouldRun()).isTrue()
        assertThat(trigger("MJ", "NOMJremains").shouldRun()).isFalse()
        assertThat(trigger("Steroids", "SteroidsON").shouldRun()).isFalse()
        assertThat(trigger("", "").shouldRun()).isFalse()
    }

    @Test
    fun falseWhileStatesAreOff() = runTest {
        whenever(preferences.get(BooleanKey.AutomationStatesEnabled)).thenReturn(false)
        assertThat(trigger("MJ", "MJ3").shouldRun()).isFalse()
    }

    @Test
    fun editorListsComeFromTheSavedStates() {
        val t = TriggerAutomationState(triggerDeps)
        assertThat(t.stateNames()).containsExactly("LowBG", "MJ").inOrder()
        assertThat(t.stateValues("MJ")).containsExactly("NOMJremains", "MJ active", "MJ3").inOrder()
    }

    /** A rule stored by the v3 fork (full class name, same data) loads as this trigger, not as an unknown one. */
    @Test
    fun loadsV3StoredRule() = runTest {
        val v3 = """{"data":{"stateName":"LowBG","stateValue":"NO50rec"},"type":"app.aaps.plugins.automation.triggers.TriggerAutomationState"}"""
        val t = triggerFactory.instantiate(v3.asJsonObject())
        assertThat(t).isInstanceOf(TriggerAutomationState::class.java)
        t as TriggerAutomationState
        assertThat(t.stateName.value).isEqualTo("LowBG")
        assertThat(t.stateValue.value).isEqualTo("NO50rec")
        assertThat(t.shouldRun()).isTrue()
    }
}
