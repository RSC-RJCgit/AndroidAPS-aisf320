package app.aaps.ui.compose.maintenance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportedFileHasAutomationStatesTest {

    @Test
    fun aStoredListCounts() {
        assertTrue(importedFileHasAutomationStates(mapOf("automation_state_values" to """{"Sleeping":["True","False"]}""")))
        assertTrue(importedFileHasAutomationStates(mapOf("automation_state_service" to """{"Sleeping":"True"}""")))
    }

    @Test
    fun emptyOrMissingDoesNotCount() {
        assertFalse(importedFileHasAutomationStates(emptyMap()))
        assertFalse(importedFileHasAutomationStates(mapOf("automation_state_values" to "{}", "automation_state_service" to " {} ")))
        assertFalse(importedFileHasAutomationStates(mapOf("automation_state_values" to "")))
    }
}
