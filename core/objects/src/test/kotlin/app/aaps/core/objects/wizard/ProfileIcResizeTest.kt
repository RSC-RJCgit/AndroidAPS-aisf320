package app.aaps.core.objects.wizard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProfileIcResizeTest {

    @Test
    fun aStrongerProfileMakesThePartBigger() {
        // Standard Profile150 IC 3.91 -> Profile190 IC 2.77 (Live, 28 Nov): x1.41
        assertThat(profileIcResizeFactor("Profile150", 3.91, "Profile190", 2.77)).isWithin(0.01).of(1.41)
    }

    @Test
    fun aWeakerProfileMakesItSmallerUnlessUpOnly() {
        // Profile150 3.91 -> Low Profile90 5.72: x0.68
        assertThat(profileIcResizeFactor("Profile150", 3.91, "Profile90", 5.72)).isWithin(0.01).of(0.68)
        // delayed checks sized at the Standard IC stay as they are on a Low profile
        assertThat(profileIcResizeFactor("Profile150", 3.91, "Profile100", 5.08, upOnly = true)).isEqualTo(1.0)
        assertThat(profileIcResizeFactor("Profile150", 3.91, "Profile190", 2.77, upOnly = true)).isWithin(0.01).of(1.41)
    }

    @Test
    fun keptWithinTheLimits() {
        assertThat(profileIcResizeFactor("A", 10.0, "B", 2.0)).isEqualTo(IC_RESIZE_MAX)
        assertThat(profileIcResizeFactor("A", 2.0, "B", 10.0)).isEqualTo(IC_RESIZE_MIN)
    }

    @Test
    fun noResizeOnTheSameProfileOrMissingValues() {
        assertThat(profileIcResizeFactor("Profile150", 3.91, "Profile150", 3.0)).isEqualTo(1.0)
        assertThat(profileIcResizeFactor("", 0.0, "Profile190", 2.77)).isEqualTo(1.0)      // part saved before this change
        assertThat(profileIcResizeFactor("Profile150", 0.0, "Profile190", 2.77)).isEqualTo(1.0)
        assertThat(profileIcResizeFactor("Profile150", 3.91, "Steroid150", 3.91)).isEqualTo(1.0)
        assertThat(profileIcResizeNote(1.0, "A", "B")).isEmpty()
        assertThat(profileIcResizeNote(1.41, "Profile150", "Profile190")).contains("x1.41")
    }
}
