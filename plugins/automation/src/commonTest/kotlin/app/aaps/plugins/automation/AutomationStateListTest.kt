package app.aaps.plugins.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutomationStateListTest {

    private val values = """{"Steroids":["Steroids Off","SteroidsON"],"MJ":["NOMJremains","MJ active"],"Sleeping":["True"]}"""

    @Test
    fun declaredStatesShowTheCurrentValueInNameOrder() {
        val rows = automationStateEntries(currentJson = """{"MJ":"MJ active","Steroids":"Steroids Off"}""", valuesJson = values)
        assertEquals(
            listOf(
                AutomationStateEntry("MJ", "MJ active", listOf("NOMJremains", "MJ active")),
                AutomationStateEntry("Sleeping", "", listOf("True")),
                AutomationStateEntry("Steroids", "Steroids Off", listOf("Steroids Off", "SteroidsON"))
            ),
            rows
        )
    }

    @Test
    fun aValueThatWasNeverDeclaredIsLeftOut() {
        val rows = automationStateEntries(currentJson = """{"Extra":"yes"}""", valuesJson = """{"MJ":["NOMJremains"]}""")
        assertEquals(listOf(AutomationStateEntry("MJ", "", listOf("NOMJremains"))), rows)
    }

    @Test
    fun brokenTextIsAnEmptyList() {
        assertEquals(emptyList(), automationStateEntries(currentJson = "nope", valuesJson = "{}"))
        assertEquals(emptyList(), automationStateEntries(currentJson = "{}", valuesJson = ""))
    }

    @Test
    fun currentValueMustBeDeclared() {
        val changed = setAutomationStateCurrent("""{"MJ":"NOMJremains"}""", values, "MJ", "MJ active")!!
        assertEquals("MJ active", automationStateEntries(changed.currentJson, changed.valuesJson).first { it.name == "MJ" }.current)
        assertNull(setAutomationStateCurrent("{}", values, "MJ", "MJ3"))
        assertNull(setAutomationStateCurrent("{}", values, "Unknown", "x"))
    }

    @Test
    fun newValuesDropACurrentValueThatIsNoLongerAllowed() {
        val changed = setAutomationStateValues("""{"MJ":"MJ active","Sleeping":"True"}""", values, "MJ", listOf("NOMJremains", "MJ2"))
        val rows = automationStateEntries(changed.currentJson, changed.valuesJson)
        assertEquals(AutomationStateEntry("MJ", "", listOf("NOMJremains", "MJ2")), rows.first { it.name == "MJ" })
        assertEquals("True", rows.first { it.name == "Sleeping" }.current)
    }

    @Test
    fun aNewStateIsAdded() {
        val changed = setAutomationStateValues("{}", "{}", "Sleeping", listOf("True", "False"))
        assertEquals(listOf(AutomationStateEntry("Sleeping", "", listOf("True", "False"))), automationStateEntries(changed.currentJson, changed.valuesJson))
    }

    @Test
    fun deleteRemovesNameAndValue() {
        val changed = deleteAutomationState("""{"MJ":"MJ active"}""", values, "MJ")
        assertEquals(listOf("Sleeping", "Steroids"), automationStateEntries(changed.currentJson, changed.valuesJson).map { it.name })
        assertEquals("{}", changed.currentJson)
    }
}
