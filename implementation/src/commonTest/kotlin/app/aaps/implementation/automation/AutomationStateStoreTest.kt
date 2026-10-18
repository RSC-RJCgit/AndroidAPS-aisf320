package app.aaps.implementation.automation

import app.aaps.core.keys.StringNonKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationStateStoreTest {

    private val storage = mutableMapOf<StringNonKey, String>()
    private fun store() = AutomationStateStore(read = { storage[it] ?: it.defaultValue }, save = { key, value -> storage[key] = value })

    @Test
    fun setAndReadAState() {
        val states = store()
        states.setStateValues("Sleeping", listOf("True", "False"))
        states.setState("Sleeping", " True ")
        assertTrue(states.inState("Sleeping", "True"))
        assertEquals("True", states.getState("Sleeping"))
        assertEquals(listOf("Sleeping" to "True"), states.getAllStates())
    }

    @Test
    fun unknownStateOrValueIsRefused() {
        val states = store()
        assertFailsWith<IllegalArgumentException> { states.setState("Sleeping", "True") }
        states.setStateValues("Sleeping", listOf("True", "False"))
        assertFailsWith<IllegalArgumentException> { states.setState("Sleeping", "Maybe") }
        assertEquals("", states.getState("Sleeping"))
    }

    @Test
    fun removedValueClearsTheCurrentState() {
        val states = store()
        states.setStateValues("Mode", listOf("A", "B"))
        states.setState("Mode", "B")
        states.setStateValues("Mode", listOf("A"))
        assertFalse(states.inState("Mode", "B"))
        assertEquals("", states.getState("Mode"))
    }

    @Test
    fun statesSurviveARestart() {
        store().apply {
            setStateValues("Sleeping", listOf("True", "False"))
            setState("Sleeping", "False")
        }
        assertTrue(store().inState("Sleeping", "False"))
        assertEquals("{\"Sleeping\":\"False\"}", storage[StringNonKey.AutomationCurrentStates])
    }

    @Test
    fun deletedStateIsGone() {
        val states = store()
        states.setStateValues("Mode", listOf("A"))
        states.setState("Mode", "A")
        states.deleteState("Mode")
        assertFalse(states.hasStateValues("Mode"))
        assertEquals("", states.getState("Mode"))
    }
}
