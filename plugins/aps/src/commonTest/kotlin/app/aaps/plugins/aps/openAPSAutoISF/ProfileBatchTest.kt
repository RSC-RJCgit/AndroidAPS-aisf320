package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileBatchTest {

    @Test
    fun tenMinutesUnder4Qualifies() {
        val start = 0L
        val series = (0..12).map { start + it * 60_000L to 70.0 }
        assertTrue(sustainedLowEpisode(series, emptyList(), sustainedMinutes = 10, maxMgdl = 72.1, maxSteps60 = 1000))
    }

    @Test
    fun highStepsBlockTheLowEpisode() {
        val series = (0..12).map { it * 60_000L to 70.0 }
        val steps = listOf(6 * 60_000L to 1500)
        assertFalse(sustainedLowEpisode(series, steps, sustainedMinutes = 10, maxMgdl = 72.1, maxSteps60 = 1000))
    }

    @Test
    fun lowCStepsUpOntoStandard() {
        val step = profileBatchStep(ProfileBatchSlot.LOW_C, up = true)
        assertEquals(0, step?.index)
        assertEquals(false, step?.switchRunning)
        assertEquals(true, step?.thenStandard)
    }

    @Test
    fun standardAStepsDownOntoLow() {
        val step = profileBatchStep(ProfileBatchSlot.STD_A, up = false)
        assertEquals(2, step?.index)
        assertEquals(true, step?.thenLow)
    }

    @Test
    fun standardCDoesNotStepUp() {
        assertNull(profileBatchStep(ProfileBatchSlot.STD_C, up = true))
    }

    @Test
    fun downWinsOverUp() {
        assertEquals(
            BatchChoice.STEP_DOWN,
            profileBatchChoice(
                holdA = false,
                holdC = false,
                autoOn = true,
                bgl12For2h = true,
                ukf14For2h = false,
                poorResponseRecent = false,
                gentleHypoRecent = true,
                morningStreak = 0,
            )
        )
    }

    @Test
    fun bothHoldsDoNothing() {
        assertEquals(BatchChoice.BOTH_HOLDS, profileBatchChoice(true, true, true, true, true, true, true, 2))
    }

    @Test
    fun batteryFiresAtOnePercentOnALivePump() {
        assertTrue(battery1ShouldFire(ready = true, running = "Profile100", safetyName = "Current Profile50", batteryPercent = 1, livePump = true))
        assertFalse(battery1ShouldFire(ready = true, running = "Profile100", safetyName = "Current Profile50", batteryPercent = 1, livePump = false))
    }

    @Test
    fun missingRolesNamesTheBlankOnes() {
        assertEquals(listOf("Safety"), missingProfileRoles(standardFound = true, lowFound = true, safetyFound = false))
    }
}
