package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Hypo50Test {

    @Test
    fun connectPodFiresWhenThePumpHasBeenQuiet() {
        assertTrue(
            connectPodShouldFire(
                ready = true,
                livePump = true,
                minutesSinceConnection = 25,
                minuteOfDay = 14 * 60,
                cannulaHours = 10.0,
            )
        )
    }

    @Test
    fun connectPodStaysClosedBeforeEight() {
        assertFalse(
            connectPodShouldFire(
                ready = true,
                livePump = true,
                minutesSinceConnection = 25,
                minuteOfDay = 7 * 60,
                cannulaHours = 10.0,
            )
        )
    }

    @Test
    fun gentleHypoFiresWhenRawIsLowAndFalling() {
        assertEquals(
            "1",
            gentleHypoBlock(
                ready = true,
                acceWeight = 0.07,
                bg = 90.0,
                delta = -2.0,
                shortDelta = -2.0,
                profilePercent = 100,
                ukfGlucose = 70.0,
                ukfDelta1 = -1.0,
                ukfDelta5 = -1.0,
                hp = 5.0,
                steps60 = 0,
            )
        )
    }

    @Test
    fun gentleHypoStaysClosedOnceAccelIsAlreadyAtSkittles() {
        assertNull(
            gentleHypoBlock(
                ready = true,
                acceWeight = 0.02,
                bg = 90.0,
                delta = -2.0,
                shortDelta = -2.0,
                profilePercent = 100,
                ukfGlucose = 70.0,
                ukfDelta1 = -1.0,
                ukfDelta5 = -1.0,
                hp = 3.0,
                steps60 = 0,
            )
        )
    }

    @Test
    fun skittlesEmergencyFloorIgnoresTheProfileGate() {
        assertEquals(
            "C",
            skittlesBlock(
                ready = true,
                bg = 60.0,
                delta = -1.0,
                shortDelta = 0.0,
                longDelta = 0.0,
                iob = 0.0,
                cob = 40.0,
                profilePercent = 50,
                minutesSinceBolus = 1,
                steroidsOff = false,
            )
        )
    }

    @Test
    fun skittlesBlockAStaysClosedUnder65Percent() {
        assertNull(
            skittlesBlock(
                ready = true,
                bg = 80.0,
                delta = -1.0,
                shortDelta = -1.0,
                longDelta = -1.0,
                iob = 0.0,
                cob = 0.0,
                profilePercent = 50,
                minutesSinceBolus = 30,
                steroidsOff = true,
            )
        )
    }

    @Test
    fun fiftySetRecentFiresOnA50PercentProfile() {
        assertTrue(
            fiftySetRecentShouldFire(
                ready = true,
                pp50OffReady = true,
                profilePercent = 50,
                lowBgClear = true,
            )
        )
    }

    @Test
    fun fiftyPcFiresUnder5MmolOnA50PercentProfile() {
        assertTrue(
            fiftyPcMakes57ShouldFire(
                ready = true,
                profilePercent = 50,
                ttActive = false,
                bg = 88.0,
                delta = -1.0,
            )
        )
    }

    @Test
    fun prepareSet50FiresUnder5Mmol() {
        assertEquals(
            "3",
            prepareSet50Block(
                ready = true,
                profilePercent = 100,
                bg = 85.0,
                delta = -1.0,
                shortDelta = 0.0,
                iob = 0.0,
                cob = 5.0,
                steps30 = 0,
                minuteOfDay = 15 * 60,
            )
        )
    }

    @Test
    fun prepareSet50StaysClosedWhenTheProfileIsNot100() {
        assertNull(
            prepareSet50Block(
                ready = true,
                profilePercent = 50,
                bg = 85.0,
                delta = -1.0,
                shortDelta = 0.0,
                iob = 0.0,
                cob = 0.0,
                steps30 = 0,
                minuteOfDay = 15 * 60,
            )
        )
    }
}
