package app.aaps.ui.compose.wizardDialog

import app.aaps.core.interfaces.trio.TrioRemoteCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WizardTrioSendTest {

    private val now = 1_800_000_000_000L

    @Test
    fun mealAndBolusBothGo() {
        val send = wizardTrioSend(insulin = 0.9, carbs = 15, fat = 20, protein = 30, eCarbs = 0, carbTimeMinutes = 0, now = now)
        assertEquals(TrioRemoteCommand.Meal(carbs = 15, fat = 20, protein = 30, eatenAt = null), send.meal)
        assertEquals(TrioRemoteCommand.Bolus(0.9), send.bolus)
        assertFalse(send.futureCarbTimeIgnored)
    }

    @Test
    fun bolusIsSentWithTwoDecimals() {
        assertEquals(TrioRemoteCommand.Bolus(1.2), wizardTrioSend(1.2000000000000002, 0, 0, 0, 0, 0, now).bolus)
    }

    @Test
    fun noInsulinMeansNoBolus() {
        val send = wizardTrioSend(insulin = -0.3, carbs = 10, fat = 0, protein = 0, eCarbs = 0, carbTimeMinutes = 0, now = now)
        assertNull(send.bolus)
        assertEquals(10, send.meal?.carbs)
    }

    @Test
    fun correctionOnlyHasNoMeal() {
        val send = wizardTrioSend(insulin = 0.5, carbs = 0, fat = 0, protein = 0, eCarbs = 0, carbTimeMinutes = -20, now = now)
        assertNull(send.meal)
        assertFalse(send.futureCarbTimeIgnored)
    }

    @Test
    fun earlierCarbTimeIsEatenAtAndCappedAt120Minutes() {
        assertEquals(now - 30 * 60_000L, wizardTrioSend(0.0, 20, 0, 0, 0, -30, now).meal?.eatenAt)
        assertEquals(now - 120 * 60_000L, wizardTrioSend(0.0, 20, 0, 0, 0, -300, now).meal?.eatenAt)
    }

    @Test
    fun laterCarbTimeGoesAsNowAndIsNamed() {
        val send = wizardTrioSend(insulin = 1.0, carbs = 20, fat = 0, protein = 0, eCarbs = 0, carbTimeMinutes = 15, now = now)
        assertNull(send.meal?.eatenAt)
        assertTrue(send.futureCarbTimeIgnored)
    }

    @Test
    fun extendedCarbsGoInHourlyParts() {
        assertEquals(listOf(10, 10, 10), trioECarbsParts(30, 3))
        assertEquals(listOf(9, 8, 8), trioECarbsParts(25, 3))
        assertEquals(listOf(20), trioECarbsParts(20, 0))
        assertEquals(listOf(1, 1), trioECarbsParts(2, 4))
    }

    @Test
    fun extendedCarbsAreNotSent() {
        val send = wizardTrioSend(insulin = 0.0, carbs = 0, fat = 0, protein = 0, eCarbs = 25, carbTimeMinutes = 0, now = now)
        assertTrue(send.isEmpty)
        assertEquals(25, send.eCarbsNotSent)
    }
}
