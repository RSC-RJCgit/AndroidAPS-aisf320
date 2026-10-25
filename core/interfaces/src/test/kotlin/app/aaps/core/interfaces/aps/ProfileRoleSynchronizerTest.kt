package app.aaps.core.interfaces.aps

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class ProfileRoleSynchronizerTest {
    private val standard = listOf("StandardA", "StandardB", "StandardC")
    private val low = listOf("LowA", "LowB", "LowC")

    @Test
    fun theEffectiveBaseNameWinsOverStaleCurrentRoles() {
        assertEquals(2, configuredRoleRung("LowC", "StandardA", "LowA", standard, low))
        assertEquals(ProfileRoleMatch(2, true), configuredProfileRole("LowC", standard, low))
        assertEquals(ProfileRoleMatch(1, false), configuredProfileRole("StandardB", standard, low))
    }

    @Test
    fun aPercentageCannotIdentifyARole() {
        assertNull(configuredProfileRole("50%", standard, low))
        assertNull(configuredProfileRole("StandardA (50%)", standard, low))
        assertNull(configuredProfileRole("", standard, low))
        assertEquals(-1, configuredRoleRung("50%", "", "", standard, low))
    }

    @Test
    fun anUnknownProfileDoesNotBecomeTierA() {
        assertNull(configuredProfileRole("Custom", standard, low))
        assertEquals(-1, configuredRoleRung("Custom", "Deleted", "", standard, low))
    }

    @Test
    fun knownCurrentRolesCanSupplyTheRungForAnOffLadderProfile() {
        assertEquals(1, configuredRoleRung("Custom", "StandardB", "LowB", standard, low))
    }

    @Test
    fun duplicateNamesDoNotGuessAFamilyOrTier() {
        val ambiguous = listOf("StandardA", "LowB", "StandardC")
        assertNull(configuredProfileRole("LowB", ambiguous, low))
        assertEquals(-1, configuredRoleRung("LowB", "StandardA", "LowA", ambiguous, low))
        assertNull(configuredProfileRole("LowB", standard, listOf("LowB", "LowB", "")))
    }
}

