package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OvernightDuraRescueTest {

    @Test
    fun aFlatHighOnTheLowProfileFiresBetween2And4() {
        assertTrue(rescue())
        assertTrue(rescue(minuteOfDay = 2 * 60))
        assertTrue(rescue(minuteOfDay = 3 * 60 + 59))
    }

    @Test
    fun theWindowIsClosedAt4AndBefore2() {
        assertFalse(rescue(minuteOfDay = 4 * 60))
        assertFalse(rescue(minuteOfDay = 1 * 60 + 59))
    }

    @Test
    fun theDurationFactorMustLeadAndTheDeltasMustBeFlat() {
        assertFalse(rescue(duraIsf = 2.5))
        assertFalse(rescue(finalIsf = 3.0))
        assertFalse(rescue(shortDelta = -1.8))
        assertFalse(rescue(longDelta = 1.9))
        assertFalse(rescue(bg = 108.1))
    }

    @Test
    fun aRecentLowOrARecentSmbOrAnActiveRescueStaysClosed() {
        assertFalse(rescue(lowBgRecent = true))
        assertFalse(rescue(smbSum30 = 0.1))
        assertFalse(rescue(rescueActive = true))
        assertFalse(rescue(onLowProfile = false))
        assertFalse(rescue(ready = false))
    }

    private fun rescue(
        ready: Boolean = true,
        rescueActive: Boolean = false,
        minuteOfDay: Int = 2 * 60 + 30,
        onLowProfile: Boolean = true,
        bg: Double = 120.0,
        shortDelta: Double = 0.0,
        longDelta: Double = 0.0,
        duraIsf: Double = 3.0,
        finalIsf: Double = 2.6,
        acceIsf: Double = 1.2,
        bgIsf: Double = 1.1,
        ppIsf: Double = 1.0,
        smbSum30: Double = 0.0,
        lowBgRecent: Boolean = false,
    ) = overnightDuraRescueShouldFire(
        ready, rescueActive, minuteOfDay, onLowProfile, bg, shortDelta, longDelta,
        duraIsf, finalIsf, acceIsf, bgIsf, ppIsf, smbSum30, lowBgRecent,
    )
}
