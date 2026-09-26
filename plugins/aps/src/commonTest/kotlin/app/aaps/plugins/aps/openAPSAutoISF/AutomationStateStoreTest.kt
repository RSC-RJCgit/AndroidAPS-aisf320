package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationStateStoreTest {

    private class Memory {
        var current = "{}"
        var values = "{}"
        fun store() = AutomationStateStore(current, values, { current = it }, { values = it })
    }

    @Test
    fun aNewStoreCanRememberOneAllowedValue() {
        val memory = Memory()
        val store = memory.store()
        store.setStateValues("MJ", listOf("NOMJremains", "MJ active"))
        store.setState("MJ", "MJ active")
        val again = memory.store()
        assertTrue(again.inState("MJ", "MJ active"))
        assertFalse(again.inState("MJ", "NOMJremains"))
        assertEquals("MJ active", again.getState("MJ"))
    }

    @Test
    fun anUnknownValueIsRejected() {
        val store = Memory().store()
        store.setStateValues("LowBG", listOf("50recent", "NO50rec"))
        assertFailsWith<IllegalArgumentException> { store.setState("LowBG", "other") }
        assertFailsWith<IllegalArgumentException> { store.setState("Missing", "NO50rec") }
    }

    @Test
    fun ensureDeclaredSetsTheOffValueOnce() {
        val memory = Memory()
        val store = memory.store()
        requiredAutomationStates.forEach { (name, required) ->
            store.ensureDeclared(name, required.values, required.defaultValue)
        }
        assertTrue(store.inState("MJ", "NOMJremains"))
        assertTrue(store.inState("Steroids", "Steroids Off"))
        assertTrue(store.inState("LowBG", "NO50rec"))
        assertTrue(store.inState("AlarmHypo", "NoAlarmRecent"))
        assertTrue(store.inState("Profile", "AllOK"))
        assertTrue(store.hasStateValues("Sleeping"))
        assertEquals("", store.getState("Sleeping"))
        store.setState("MJ", "MJ2")
        memory.store().ensureDeclared("MJ", requiredAutomationStates.getValue("MJ").values, "NOMJremains")
        assertTrue(memory.store().inState("MJ", "MJ2"))
    }

    @Test
    fun nightSkipFiresOnlyInsideItsWindowAndSafetyGates() {
        val fire = nightFrSkipShouldFire(
            ready = true, profilePercent = 100, tempTargetSet = false, boostAutomationsOn = true,
            minuteOfDay = 3 * 60, bg = 130.0, delta = 5.0, shortDelta = 3.0, rawDelta5 = 5.0,
            iob = 0.8, smbSum10 = 0.2, lowBgRecent = false, mjActive = false, steps5 = 10, steps30 = 20,
        )
        assertTrue(fire)
        assertFalse(fire.let {
            nightFrSkipShouldFire(
                ready = true, profilePercent = 100, tempTargetSet = false, boostAutomationsOn = true,
                minuteOfDay = 20, bg = 130.0, delta = 5.0, shortDelta = 3.0, rawDelta5 = 5.0,
                iob = 0.8, smbSum10 = 0.2, lowBgRecent = false, mjActive = false, steps5 = 10, steps30 = 20,
            )
        })
        assertFalse(
            nightFrSkipShouldFire(
                ready = true, profilePercent = 100, tempTargetSet = false, boostAutomationsOn = true,
                minuteOfDay = 3 * 60, bg = 130.0, delta = 5.0, shortDelta = 3.0, rawDelta5 = 5.0,
                iob = 0.8, smbSum10 = 0.2, lowBgRecent = true, mjActive = false, steps5 = 10, steps30 = 20,
            )
        )
    }

    @Test
    fun droppingTheActiveValueClearsIt() {
        val store = Memory().store()
        store.setStateValues("Profile", listOf("AllOK", "Bolus"))
        store.setState("Profile", "Bolus")
        store.setStateValues("Profile", listOf("AllOK"))
        assertEquals("", store.getState("Profile"))
    }
}
