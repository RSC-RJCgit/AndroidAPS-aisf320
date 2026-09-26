package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorNotesTest {

    @Test
    fun stepsSteroidsNeedsARiseAndNoCarbs() {
        assertTrue(stepsSteroidsOffShouldFire(ready = true, steps60 = 1000, iob = 3.0, bg = 140.0, delta = 9.0, cob = 0.0))
        assertFalse(stepsSteroidsOffShouldFire(ready = true, steps60 = 1000, iob = 3.0, bg = 140.0, delta = 9.0, cob = 1.0))
    }

    @Test
    fun preSoakHitsTheTwoWindows() {
        assertEquals("14.0", preSoakBlock(ready = true, livePump = true, sensorHours = 336.05, podHours = 10.0))
        assertEquals("14.5", preSoakBlock(ready = true, livePump = true, sensorHours = 348.0, podHours = null))
        assertNull(preSoakBlock(ready = true, livePump = true, sensorHours = 336.05, podHours = 81.0))
    }

    @Test
    fun sensorRemindersUseNarrowHours() {
        assertTrue(sensorHourHit(ready = true, livePump = true, sensorHours = 359.05, podHours = 10.0, low = 359.0, high = 359.1))
        assertFalse(sensorHourHit(ready = true, livePump = false, sensorHours = 359.05, podHours = 10.0, low = 359.0, high = 359.1))
    }

    @Test
    fun sensorAgeTurnsOffWhenThePodIsOld() {
        assertTrue(sensorAgeShouldTurnOff(codeEnabled = true, podHours = 80.1, sensorDays = 2.0))
        assertFalse(sensorAgeShouldTurnOff(codeEnabled = false, podHours = 90.0, sensorDays = 20.0))
        assertFalse(sensorAgeShouldTurnOff(codeEnabled = true, podHours = null, sensorDays = null))
    }

    @Test
    fun sensorAgeTurnsBackOnOnlyAfterBothRecover() {
        assertTrue(sensorAgeShouldTurnOn(codeEnabled = false, latched = true, podHours = 10.0, sensorDays = 4.0))
        assertFalse(sensorAgeShouldTurnOn(codeEnabled = false, latched = true, podHours = 90.0, sensorDays = 4.0))
        assertFalse(sensorAgeShouldTurnOn(codeEnabled = false, latched = false, podHours = 10.0, sensorDays = 4.0))
    }

    @Test
    fun oldSensorDay0UsesTheSteepestTier() {
        val tier = oldSensorTier(sensorDays = 0.5, podHours = 70.0, slopeBase = 0.72, offsetBase = 1.4)
        assertEquals("NewDay1", tier?.name)
        assertEquals(0.65, tier?.slope ?: 0.0, 0.0001)
    }

    @Test
    fun oldPodBoostNeedsASmallRequestAfterTwoHours() {
        val start = 1_000_000L
        val held = oldPodHighSince(now = start, highNow = true, previous = 0L)
        assertTrue(oldPodBoostShouldStart(
            active = false,
            podHours = 70.0,
            highSince = held,
            now = held + 2 * 3_600_000L,
            insulinReq = 0.5,
            maxIob = 9.5,
        ))
        assertTrue(oldPodBoostShouldStop(active = true, bg = 140.0, delta = -1.0, podHours = 70.0))
    }
}
