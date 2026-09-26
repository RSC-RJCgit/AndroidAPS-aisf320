package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlankRoleFillsTest {

    @Test
    fun aSingleLowNumberFillsThatTierAndTheBlankLowCurrent() {
        val fills = blankRoleFills(
            roleValues = emptyMap(),
            profileNames = listOf("Evening 80"),
        )
        assertEquals("Evening 80", fills["autoisf_low80_profile_name"])
        assertEquals("Evening 80", fills["autoisf_low_profile_name"])
    }

    @Test
    fun oneHundredTenDoesNotAlsoFillOneHundred() {
        val fills = blankRoleFills(
            roleValues = emptyMap(),
            profileNames = listOf("Current Profile110"),
        )
        assertEquals("Current Profile110", fills["autoisf_standard110_profile_name"])
        assertNull(fills["autoisf_standard100_profile_name"])
        assertEquals("Current Profile110", fills["autoisf_standard_profile_name"])
    }

    @Test
    fun aSteroidNameIsNotWrittenIntoALowOrStandardSlot() {
        val fills = blankRoleFills(
            roleValues = emptyMap(),
            profileNames = listOf("Steroid100", "Boost 80%"),
        )
        assertEquals(emptyMap(), fills)
    }

    @Test
    fun twoNamesForTheSameNumberAreLeftBlank() {
        val fills = blankRoleFills(
            roleValues = emptyMap(),
            profileNames = listOf("Low 80 a", "Low 80 b"),
        )
        assertNull(fills["autoisf_low80_profile_name"])
        assertNull(fills["autoisf_low_profile_name"])
    }

    @Test
    fun aRoleThatAlreadyHasANameIsNotReplaced() {
        val fills = blankRoleFills(
            roleValues = mapOf("autoisf_low_profile_name" to "Kept"),
            profileNames = listOf("Evening 80"),
        )
        assertEquals("Evening 80", fills["autoisf_low80_profile_name"])
        assertNull(fills["autoisf_low_profile_name"])
    }
}
