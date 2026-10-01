package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UamTier3PseudoWizardTest {

    @Test
    fun belowOneUnitHasNoCalcRow() {
        assertNull(entry(0.99))
    }

    @Test
    fun oneUnitKeepsTheFixedPlaceholderOutOfTheSum() {
        val row = entry(1.0)!!
        assertEquals(10.0, row.carbs)
        assertFalse(row.wereCarbsUsed)
        assertEquals(0.0, row.carbsInsulin)
        assertEquals(1.0, row.totalInsulin)
        assertTrue(row.note.contains("10g carbs"))
        assertTrue(row.note.contains("fat=10g"))
        assertTrue(row.note.contains("protein=15g"))
        assertTrue(row.note.contains("Not entered"))
        assertTrue(row.note.contains("1.00U"))
    }

    @Test
    fun aLargerSmbStillUsesTenGrams() {
        val row = entry(1.25)!!
        assertEquals(10.0, row.carbs)
        assertEquals(1.25, row.totalInsulin)
        assertTrue(row.note.contains("1.25U"))
    }

    private fun entry(smb: Double) = uamTier3PseudoWizardEntry(
        timestamp = 1_700_000_000_000L,
        smbDelivered = smb,
        glucoseMgdl = 180.0,
        targetLowMgdl = 90.0,
        targetHighMgdl = 110.0,
        isfMgdl = 40.0,
        ic = 8.0,
        profileName = "Profile150",
    )
}
