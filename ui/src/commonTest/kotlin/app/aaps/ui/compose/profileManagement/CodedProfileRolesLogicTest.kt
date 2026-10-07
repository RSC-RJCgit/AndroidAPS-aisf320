package app.aaps.ui.compose.profileManagement

import app.aaps.core.data.model.data.Block
import app.aaps.core.data.model.data.TargetBlock
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.profile.SingleProfile
import app.aaps.core.keys.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodedProfileRolesLogicTest {

    @Test
    fun everySlotKeyIsARealPreference() {
        codedProfileSlots().forEach { slot ->
            assertEquals(slot.key, stringKeyForCodedRole(slot.key).key)
        }
    }

    @Test
    fun aLadderProfileWithNoRoleChosenBecomesTheCurrentRole() {
        val std = listOf("Profile100", "Profile130", "Profile150")
        val low = listOf("Profile70", "Profile80", "Profile90")
        assertEquals("autoisf_standard_profile_name", ladderCurrentRoleKey("Profile130", std, low))
        assertEquals("autoisf_low_profile_name", ladderCurrentRoleKey("Profile80", std, low))
        assertNull(ladderCurrentRoleKey("Profile95", std, low))
        assertNull(ladderCurrentRoleKey("Steroid130", listOf("Steroid130"), low))
        assertNull(ladderCurrentRoleKey("", std, low))
    }

    @Test
    fun steroidDefaultsMatchTheCodedNames() {
        assertEquals("", StringKey.ApsAutoIsfSteroid100ProfileName.defaultValue)
        assertEquals("Steroid Profile110", StringKey.ApsAutoIsfSteroid110ProfileName.defaultValue)
        assertEquals("Steroid Profile130", StringKey.ApsAutoIsfSteroid130ProfileName.defaultValue)
        assertEquals("Steroid Profile150", StringKey.ApsAutoIsfSteroid150ProfileName.defaultValue)
        assertEquals("Steroid190", StringKey.ApsAutoIsfSteroid190ProfileName.defaultValue)
        assertEquals("Steroid250", StringKey.ApsAutoIsfSteroid250ProfileName.defaultValue)
    }

    @Test
    fun steroidMarkerDetectsTheWordAndAPercentSign() {
        assertTrue(isSteroidMarkedProfileName("Steroid Profile110"))
        assertTrue(isSteroidMarkedProfileName("Day 110%"))
        assertFalse(isSteroidMarkedProfileName("Day"))
    }

    @Test
    fun steroidNamePicksTheSpecificTier() {
        assertEquals("autoisf_steroid_110_profile_name", steroidSlotKeyForName("Steroid Profile110"))
        assertEquals("autoisf_steroid_100_profile_name", steroidSlotKeyForName("Steroid100"))
        assertEquals("autoisf_steroid_250_profile_name", steroidSlotKeyForName("My%250"))
        assertNull(steroidSlotKeyForName("Day"))
        assertNull(steroidSlotKeyForName("Steroid"))
    }

    @Test
    fun bulkSaveRefusesANewSteroidNameOnStandard() {
        val slots = codedProfileSlots()
        val previous = slots.associate { it.key to "" }
        val selected = slots.map { if (it.key == "autoisf_standard_profile_name") "Steroid110" else "" }
        val plan = planCodedProfileSave(slots, selected, previous)
        assertEquals(1, plan.blocked)
        assertFalse(plan.writes.containsKey("autoisf_standard_profile_name"))
        assertFalse(plan.steroidsOff)
    }

    @Test
    fun bulkSaveDoesNotNagWhenTheStoredSteroidNameIsUnchanged() {
        val slots = codedProfileSlots()
        val previous = slots.associate { it.key to if (it.key == "autoisf_standard_profile_name") "Steroid110" else "" }
        val selected = slots.map { slot -> previous[slot.key].orEmpty() }
        val plan = planCodedProfileSave(slots, selected, previous)
        assertEquals(0, plan.blocked)
        assertFalse(plan.writes.containsKey("autoisf_standard_profile_name"))
    }

    @Test
    fun changingStandardTurnsSteroidsOff() {
        val slots = codedProfileSlots()
        val previous = slots.associate { it.key to "" }
        val selected = slots.map { if (it.key == "autoisf_standard_profile_name") "Day" else "" }
        val plan = planCodedProfileSave(slots, selected, previous)
        assertEquals("Day", plan.writes["autoisf_standard_profile_name"])
        assertTrue(plan.steroidsOff)
        assertEquals(0, plan.blocked)
    }

    @Test
    fun switchScreenRoutesASteroidNameAwayFromTheChosenRole() {
        val plan = planSwitchRole("Steroid Profile110", "autoisf_standard_profile_name")
        assertEquals(mapOf("autoisf_steroid_110_profile_name" to "Steroid Profile110"), plan.writes)
        assertFalse(plan.steroidsOff)
    }

    @Test
    fun switchScreenMarksSteroidsOffForAStandardRole() {
        val plan = planSwitchRole("Day", "autoisf_low_profile_name")
        assertEquals(mapOf("autoisf_low_profile_name" to "Day"), plan.writes)
        assertTrue(plan.steroidsOff)
    }

    @Test
    fun aPlainActivationClaimsEmptyStandardTierAAndTheBlankCurrentStandard() {
        val claim = claimStandardTierA(
            profileName = "Day",
            chosenKey = null,
            tierAName = "",
            currentStandardName = "",
            profileNames = setOf("Day"),
        )
        assertEquals("Day", claim?.tierA)
        assertEquals("Day", claim?.currentStandard)
    }

    @Test
    fun aPlainActivationDoesNotReplaceAStoredStandardTierA() {
        val claim = claimStandardTierA(
            profileName = "Day",
            chosenKey = null,
            tierAName = "Kept",
            currentStandardName = "",
            profileNames = setOf("Day", "Kept"),
        )
        assertNull(claim)
    }

    @Test
    fun aNumberedOrChosenNameIsNotClaimedAsStandardTierA() {
        assertNull(claimStandardTierA("Profile110", null, "", "", setOf("Profile110")))
        assertNull(claimStandardTierA("Evening 80", null, "", "", setOf("Evening 80")))
        assertNull(claimStandardTierA("Steroid100", null, "", "", setOf("Steroid100")))
        assertNull(claimStandardTierA("Day", "autoisf_low_profile_name", "", "", setOf("Day")))
    }

    @Test
    fun aPlainActivationLeavesAnExistingCurrentStandardAlone() {
        val claim = claimStandardTierA(
            profileName = "Day",
            chosenKey = null,
            tierAName = "Missing",
            currentStandardName = "Kept",
            profileNames = setOf("Day", "Kept"),
        )
        assertEquals("Day", claim?.tierA)
        assertNull(claim?.currentStandard)
    }

    @Test
    fun switchScreenWritesNothingWhenNoRoleIsChosen() {
        val plan = planSwitchRole("Day", null)
        assertTrue(plan.writes.isEmpty())
        assertFalse(plan.steroidsOff)
    }

    @Test
    fun tierFillNeedsStandardTierAInTheStore() {
        val plan = planTierFill(mapOf(STANDARD_TIER_A_KEY to "Day"), setOf("Other"))
        assertTrue(plan.sourceMissing)
        assertTrue(plan.creates.isEmpty())
    }

    @Test
    fun emptyTiersAreCreatedAtTheAgreedPercents() {
        val plan = planTierFill(mapOf(STANDARD_TIER_A_KEY to "Day"), setOf("Day"))
        assertFalse(plan.sourceMissing)
        assertEquals(11, plan.creates.size)
        assertTrue(plan.replaces.isEmpty())
        assertEquals(130, plan.creates.first { it.key == "autoisf_standard105_profile_name" }.percent)
        assertEquals("Standard tier B", plan.creates.first { it.key == "autoisf_standard105_profile_name" }.name)
        assertEquals(95, plan.creates.first { it.key == "autoisf_low90_profile_name" }.percent)
        assertEquals("Steroid Profile110", plan.creates.first { it.key == "autoisf_steroid_110_profile_name" }.name)
        assertEquals(250, plan.creates.first { it.key == "autoisf_steroid_250_profile_name" }.percent)
    }

    @Test
    fun aTierThatAlreadyHasAProfileIsOfferedForReplace() {
        val roles = mapOf(
            STANDARD_TIER_A_KEY to "Day",
            "autoisf_standard105_profile_name" to "Strong",
        )
        val plan = planTierFill(roles, setOf("Day", "Strong"))
        val replace = plan.replaces.single { it.key == "autoisf_standard105_profile_name" }
        assertEquals("Strong", replace.name)
        assertTrue(replace.replaceInPlace)
        assertTrue(plan.creates.none { it.key == "autoisf_standard105_profile_name" })
    }

    @Test
    fun aRolePointingAtTierAGetsANewCopyInsteadOfOverwritingIt() {
        val roles = mapOf(
            STANDARD_TIER_A_KEY to "Day",
            "autoisf_standard105_profile_name" to "Day",
        )
        val plan = planTierFill(roles, setOf("Day"))
        val replace = plan.replaces.single { it.key == "autoisf_standard105_profile_name" }
        assertEquals("Standard tier B", replace.name)
        assertFalse(replace.replaceInPlace)
    }

    @Test
    fun scaleRaisesBasalAndLowersSensitivityAndKeepsTargets() {
        val day = T.hours(24).msecs()
        val source = SingleProfile(
            name = "Day",
            mgdl = true,
            ic = listOf(Block(day, 10.0)),
            isf = listOf(Block(day, 50.0)),
            basal = listOf(Block(day, 1.0)),
            target = listOf(TargetBlock(day, 100.0, 120.0)),
        )
        val scaled = scaledTierProfile(source, 130, "Standard tier B")
        assertEquals("Standard tier B", scaled.name)
        assertEquals(1.3, scaled.basal.single().amount)
        assertEquals(38.5, scaled.isf.single().amount)
        assertEquals(7.7, scaled.ic.single().amount)
        assertEquals(source.target, scaled.target)
        assertEquals(source.mgdl, scaled.mgdl)
    }

    @Test
    fun scaleRoundsBasalToThePumpStepAndTheRestToOneDecimal() {
        val day = T.hours(24).msecs()
        val source = SingleProfile(
            name = "Day",
            mgdl = false,
            ic = listOf(Block(day, 10.04)),
            isf = listOf(Block(day, 5.26)),
            basal = listOf(Block(day, 1.03)),
            target = listOf(TargetBlock(day, 5.24, 5.26)),
        )
        val standard = scaledTierProfile(source, 130, "Standard tier B", "autoisf_standard105_profile_name")
        assertEquals(1.35, standard.basal.single().amount)
        assertEquals(4.0, standard.isf.single().amount)
        assertEquals(7.7, standard.ic.single().amount)
        assertEquals(5.2, standard.target.single().lowTarget)
        assertEquals(5.3, standard.target.single().highTarget)

        val low = scaledTierProfile(source, 80, "Low tier A", "autoisf_low70_profile_name")
        assertEquals(0.8, low.basal.single().amount)
    }
}
