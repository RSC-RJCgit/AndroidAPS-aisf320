package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants
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
    fun ignoredTimesNeverFormAnEpisode() {
        val series = (0..12).map { it * 60_000L to 70.0 }
        assertFalse(sustainedLowEpisode(series, emptyList(), sustainedMinutes = 10, maxMgdl = 72.1, maxSteps60 = 1000, ignoreAt = { true }))
        // A daytime episode after an ignored overnight stretch still counts.
        val mixed = (0..30).map { it * 60_000L to 70.0 }
        assertTrue(sustainedLowEpisode(mixed, emptyList(), sustainedMinutes = 10, maxMgdl = 72.1, maxSteps60 = 1000, ignoreAt = { it < 10 * 60_000L }))
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

    private fun battery1(batteryPercent: Int? = 1, livePump: Boolean = true, bgMmol: Double = 6.0, deltaMmol: Double = 0.0, shortDeltaMmol: Double = 0.0) =
        battery1ShouldFire(
            ready = true, running = "Profile100", safetyName = "Current Profile50", batteryPercent = batteryPercent, livePump = livePump,
            bg = bgMmol * Constants.MMOLL_TO_MGDL, delta = deltaMmol * Constants.MMOLL_TO_MGDL, shortDelta = shortDeltaMmol * Constants.MMOLL_TO_MGDL
        )

    @Test
    fun batteryFiresAtOnePercentOnALivePump() {
        assertTrue(battery1())
        assertFalse(battery1(livePump = false))
    }

    @Test
    fun anUnreportedBatteryIsNotLow() {
        assertFalse(battery1(batteryPercent = null))
        assertFalse(battery1ShouldWarn(warned = false, livePump = true, batteryPercent = null))
    }

    @Test
    fun batterySwitchWaitsForALowSteadyGlucose() {
        assertFalse(battery1(bgMmol = 7.0))
        assertFalse(battery1(deltaMmol = 0.2))
        assertFalse(battery1(shortDeltaMmol = 0.2))
        assertTrue(battery1(bgMmol = 6.9, deltaMmol = 0.1, shortDeltaMmol = 0.1))
        assertTrue(battery1(deltaMmol = -0.5, shortDeltaMmol = -0.5))
    }

    @Test
    fun batteryWarningGoesOutOncePerEpisode() {
        assertTrue(battery1ShouldWarn(warned = false, livePump = true, batteryPercent = 1))
        assertFalse(battery1ShouldWarn(warned = true, livePump = true, batteryPercent = 1))
        assertFalse(battery1ShouldWarn(warned = false, livePump = false, batteryPercent = 1))
        assertFalse(battery1ShouldWarn(warned = false, livePump = true, batteryPercent = 2))
    }

    @Test
    fun missingRolesNamesTheBlankOnes() {
        assertEquals(listOf("Safety"), missingProfileRoles(standardFound = true, lowFound = true, safetyFound = false))
    }
}
