package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BasalUpTest {

    @Test
    fun afternoonRiseOnALowProfileFires() {
        assertTrue(
            basalUpShouldFire(
                ready = true,
                bg = 110.0,
                delta = 4.0,
                profilePercent = 100,
                minuteOfDay = 13 * 60,
                steps5 = 0,
                steps15 = 10,
                steps60 = 100,
                steps30 = 40,
                podHours = 20.0,
                onLowFamily = true,
                mj3 = false,
                noMjRemains = false,
            )
        )
    }

    @Test
    fun beforeSevenAndAFlatDeltaStayClosed() {
        assertFalse(
            basalUpShouldFire(
                ready = true,
                bg = 110.0,
                delta = 4.0,
                profilePercent = 100,
                minuteOfDay = 6 * 60 + 59,
                steps5 = 0,
                steps15 = 0,
                steps60 = 0,
                steps30 = 0,
                podHours = 80.0,
                onLowFamily = true,
                mj3 = false,
                noMjRemains = true,
            )
        )
        assertFalse(
            basalUpShouldFire(
                ready = true,
                bg = 110.0,
                delta = 3.5,
                profilePercent = 100,
                minuteOfDay = 8 * 60,
                steps5 = 0,
                steps15 = 0,
                steps60 = 0,
                steps30 = 0,
                podHours = 1.0,
                onLowFamily = true,
                mj3 = false,
                noMjRemains = true,
            )
        )
    }

    @Test
    fun aQuietRecentWindowIgnoresTheHourCount() {
        assertTrue(fire(steps5 = 0, steps15 = 20, steps30 = 150, steps60 = 1500))
        assertFalse(fire(steps5 = 1, steps15 = 20, steps30 = 150, steps60 = 1500))
        assertFalse(fire(steps5 = 0, steps15 = 31, steps30 = 150, steps60 = 1500))
        assertFalse(fire(steps5 = 0, steps15 = 20, steps30 = 201, steps60 = 1500))
        assertFalse(fire(steps5 = 0, steps15 = 0, steps30 = 601, steps60 = 100))
    }

    @Test
    fun onlyTheMjGateHoldingItBackIsReported() {
        // 11:20, MJ left on "MJ active", pod 30 h old, everything else open.
        assertTrue(held(minuteOfDay = 11 * 60 + 20, mj3 = false, noMjRemains = false))
        assertFalse(held(minuteOfDay = 11 * 60 + 20, mj3 = true, noMjRemains = false))
        assertFalse(held(minuteOfDay = 13 * 60, mj3 = false, noMjRemains = false))
        // Any other closed test is not reported: profile not at 100%, steps brake, before 07:00.
        assertFalse(held(minuteOfDay = 11 * 60 + 20, mj3 = false, noMjRemains = false, profilePercent = 50))
        assertFalse(held(minuteOfDay = 11 * 60 + 20, mj3 = false, noMjRemains = false, steps30 = 601))
        assertFalse(held(minuteOfDay = 6 * 60 + 30, mj3 = false, noMjRemains = false))
    }

    private fun held(
        minuteOfDay: Int,
        mj3: Boolean,
        noMjRemains: Boolean,
        profilePercent: Int = 100,
        steps30: Int = 40,
    ): Boolean = basalUpHeldByMjGate(
        ready = true, bg = 110.0, delta = 4.0, profilePercent = profilePercent, minuteOfDay = minuteOfDay,
        steps5 = 0, steps15 = 10, steps60 = 100, steps30 = steps30, podHours = 30.0,
        onLowFamily = true, mj3 = mj3, noMjRemains = noMjRemains,
    )

    private fun fire(steps5: Int, steps15: Int, steps30: Int, steps60: Int): Boolean =
        basalUpShouldFire(
            ready = true,
            bg = 110.0,
            delta = 4.0,
            profilePercent = 100,
            minuteOfDay = 13 * 60,
            steps5 = steps5,
            steps15 = steps15,
            steps60 = steps60,
            steps30 = steps30,
            podHours = 20.0,
            onLowFamily = true,
            mj3 = false,
            noMjRemains = false,
        )
}
