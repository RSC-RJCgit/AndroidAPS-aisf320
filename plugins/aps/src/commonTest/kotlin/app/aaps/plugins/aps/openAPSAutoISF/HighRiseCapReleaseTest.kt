package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighRiseCapReleaseTest {

    @Test
    fun releasesOnTodaysRise() {
        // 26 Nov Live 11:16: BG 10.5, Delta 0.62, SDelta 0.46, minPredBG 11.7, IOB 3.72, max IOB 9.5
        assertTrue(highRiseCapRelease(189.2, 11.2, 8.3, 210.8, 3.72, 9.5, 676))
    }

    @Test
    fun keepsCapsOutsideTheConditions() {
        assertFalse(highRiseCapRelease(178.0, 11.2, 8.3, 210.8, 3.72, 9.5, 676)) // BG under 10.0
        assertFalse(highRiseCapRelease(189.2, 5.0, 8.3, 210.8, 3.72, 9.5, 676)) // Delta under 0.3
        assertFalse(highRiseCapRelease(189.2, 11.2, 3.0, 210.8, 3.72, 9.5, 676)) // SDelta under 0.2
        assertFalse(highRiseCapRelease(189.2, 11.2, 8.3, 140.0, 3.72, 9.5, 676)) // minPredBG under 8.0
        assertFalse(highRiseCapRelease(189.2, 11.2, 8.3, 210.8, 5.8, 9.5, 676)) // IOB 60% of max or more
        assertFalse(highRiseCapRelease(189.2, 11.2, 8.3, 210.8, 3.72, 9.5, 300)) // before 06:00
    }

    @Test
    fun tenMinuteCapIs25UOnRelease() {
        assertEquals(1.0, smbCap10Min(2.0, 1.5, 676, 189.2, highRiseCapRelease = true).microBolus, 0.0001)
        assertEquals(0.0, smbCap10Min(2.0, 1.5, 676, 189.2).microBolus, 0.0001)
    }
}
