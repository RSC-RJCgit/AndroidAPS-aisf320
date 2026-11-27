package app.aaps.core.objects.wizard

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileIcResizeTest {

    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 0.01, "expected $expected, was $actual")

    @Test
    fun aStrongerProfileMakesThePartBigger() {
        // Standard Profile150 IC 3.91 -> Profile190 IC 2.77 (Live, 28 Nov): x1.41
        near(1.41, profileIcResizeFactor("Profile150", 3.91, "Profile190", 2.77))
    }

    @Test
    fun aWeakerProfileMakesItSmallerUnlessUpOnly() {
        near(0.68, profileIcResizeFactor("Profile150", 3.91, "Profile90", 5.72))
        // delayed checks sized at the Standard IC stay as they are on a Low profile
        assertEquals(1.0, profileIcResizeFactor("Profile150", 3.91, "Profile100", 5.08, upOnly = true))
        near(1.41, profileIcResizeFactor("Profile150", 3.91, "Profile190", 2.77, upOnly = true))
    }

    @Test
    fun keptWithinTheLimits() {
        assertEquals(IC_RESIZE_MAX, profileIcResizeFactor("A", 10.0, "B", 2.0))
        assertEquals(IC_RESIZE_MIN, profileIcResizeFactor("A", 2.0, "B", 10.0))
    }

    @Test
    fun noResizeOnTheSameProfileOrMissingValues() {
        assertEquals(1.0, profileIcResizeFactor("Profile150", 3.91, "Profile150", 3.0))
        assertEquals(1.0, profileIcResizeFactor("", 0.0, "Profile190", 2.77))      // part saved before this change
        assertEquals(1.0, profileIcResizeFactor("Profile150", 0.0, "Profile190", 2.77))
        assertEquals(1.0, profileIcResizeFactor("Profile150", 3.91, "Steroid150", 3.91))
        assertEquals("", profileIcResizeNote(1.0, "A", "B"))
        assertTrue(profileIcResizeNote(1.41, "Profile150", "Profile190").contains("x1.41"))
    }
}
