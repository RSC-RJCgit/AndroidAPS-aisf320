package app.aaps.ui.compose.maintenance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportedFileHasAutomationStatesTest {

    @Test
    fun emptyFileHasNoStates() {
        assertFalse(importedFileHasAutomationStates(emptyMap()))
    }

    @Test
    fun emptyJsonHasNoStates() {
        assertFalse(importedFileHasAutomationStates(mapOf("automation_state_values" to "{}")))
    }

    @Test
    fun aNamedStateCounts() {
        assertTrue(
            importedFileHasAutomationStates(
                mapOf("automation_state_service" to "{\"Steroids\":\"Steroids Off\"}")
            )
        )
    }
}
