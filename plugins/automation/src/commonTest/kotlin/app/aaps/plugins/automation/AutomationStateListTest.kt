package app.aaps.plugins.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationStateListTest {

    @Test
    fun declaredStatesShowTheCurrentValueInNameOrder() {
        val rows = automationStateRows(
            currentJson = """{"MJ":"MJ active","Steroids":"Steroids Off"}""",
            valuesJson = """{"Steroids":["Steroids Off","SteroidsON"],"MJ":["NOMJremains","MJ active"],"Sleeping":["True"]}"""
        )
        assertEquals(
            listOf(
                "MJ" to "MJ active",
                "Sleeping" to "",
                "Steroids" to "Steroids Off"
            ),
            rows
        )
    }

    @Test
    fun aValueThatWasNeverDeclaredIsLeftOut() {
        val rows = automationStateRows(
            currentJson = """{"Extra":"yes"}""",
            valuesJson = """{"MJ":["NOMJremains"]}"""
        )
        assertEquals(listOf("MJ" to ""), rows)
    }

    @Test
    fun brokenTextIsAnEmptyList() {
        assertEquals(emptyList(), automationStateRows(currentJson = "nope", valuesJson = "{}"))
        assertEquals(emptyList(), automationStateRows(currentJson = "{}", valuesJson = ""))
    }
}
