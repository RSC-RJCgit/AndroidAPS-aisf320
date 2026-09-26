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
}
