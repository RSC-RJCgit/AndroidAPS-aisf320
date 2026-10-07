package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoleLadderTest {

    private val lowRungs = listOf("Profile70", "Profile80", "Profile100")
    private val standardRungs = listOf("Profile100s", "Profile105", "Profile110")

    @Test
    fun runningLowCStillCountsWhenLowCurrentIsOlder() {
        assertTrue(runningOnLowLadder("Profile100", "Profile80", lowRungs))
        assertEquals(
            2,
            sourceRoleRung("Profile100s", "Profile80", "Profile100", standardRungs, lowRungs),
        )
    }

    @Test
    fun tierReadingNamesTheLetterOrShowsEveryDisagreement() {
        val std = listOf("Profile100", "Profile130", "Profile150")
        val low = listOf("Profile70", "Profile80", "Profile90")
        val same = app.aaps.core.keys.readTier("Profile130", "Profile80", "Profile80", std, low)
        assertTrue(same.agrees)
        assertEquals("Tier B: Std Profile130, Low Profile80", app.aaps.core.keys.tierSummary(same, "Profile130", "Profile80", "Profile80"))
        // 13:18 today: Standard on A, Low on B.
        val mixed = app.aaps.core.keys.readTier("Profile100", "Profile80", "Profile80", std, low)
        assertFalse(mixed.agrees)
        assertEquals(
            "Mixed: Std A (Profile100), Low B (Profile80), running Profile80 (B)",
            app.aaps.core.keys.tierSummary(mixed, "Profile100", "Profile80", "Profile80"),
        )
        // A running profile off both ladders does not break agreement.
        assertTrue(app.aaps.core.keys.readTier("Profile130", "Profile80", "Profile95", std, low).agrees)
    }

    @Test
    fun alarmRecentExpiresWhenMissingOldOrOvernight() {
        val hour = 3_600_000L
        val now = 100 * hour
        assertTrue(alarmRecentExpired(now, alarmAt = 0L, alarmMinuteOfDay = 12 * 60))
        assertTrue(alarmRecentExpired(now, alarmAt = now - 25 * hour, alarmMinuteOfDay = 12 * 60))
        assertTrue(alarmRecentExpired(now, alarmAt = now - hour, alarmMinuteOfDay = 5 * 60 + 35))
        assertFalse(alarmRecentExpired(now, alarmAt = now - 23 * hour, alarmMinuteOfDay = 12 * 60))
    }

    @Test
    fun alarmRevertRunsOncePerAlarm() {
        assertTrue(alarmRevertDue(alarmAt = 2000L, handledAt = 1000L))
        assertFalse(alarmRevertDue(alarmAt = 2000L, handledAt = 2000L))
        assertFalse(alarmRevertDue(alarmAt = 0L, handledAt = 0L))
    }

    @Test
    fun hypoTierDropBlockedBetween0100And0700OrOnHighSteps() {
        assertTrue(hypoTierDropBlocked(eventMinuteOfDay = 60, steps60 = 0))
        assertTrue(hypoTierDropBlocked(eventMinuteOfDay = 6 * 60 + 59, steps60 = 0))
        assertFalse(hypoTierDropBlocked(eventMinuteOfDay = 7 * 60, steps60 = 0))
        assertFalse(hypoTierDropBlocked(eventMinuteOfDay = 59, steps60 = 0))
        assertFalse(hypoTierDropBlocked(eventMinuteOfDay = 12 * 60, steps60 = 1000))
        assertTrue(hypoTierDropBlocked(eventMinuteOfDay = 12 * 60, steps60 = 1001))
    }

    @Test
    fun tierCRestoresOnlyWhenRoleStillHoldsTierCName() {
        assertTrue(tierCRoleRestorable("Profile100", "Profile150", "Profile150", savedInStore = true))
        // Changed by hand since Tier C switched on: keep that value.
        assertFalse(tierCRoleRestorable("Profile110", "Profile100", "Profile150", savedInStore = true))
        // Saved name is no longer a stored profile.
        assertFalse(tierCRoleRestorable("Profile110", "Profile150", "Profile150", savedInStore = false))
        assertFalse(tierCRoleRestorable("", "Profile150", "Profile150", savedInStore = true))
        // Tier C rung blank: the role was left alone, so live equals saved.
        assertTrue(tierCRoleRestorable("Profile100", "Profile100", "", savedInStore = true))
    }

    @Test
    fun sharedRungFollowsThatLetter() {
        val names = sharedRungNames(
            index = 2,
            lowRungs = lowRungs,
            standardRungs = standardRungs,
            lowCurrent = "Profile80",
            standardAnchor = "Profile100s",
            standardCurrent = "Profile100s",
        )
        assertEquals("Profile100" to "Profile110", names)
    }

    @Test
    fun bandChangeNudgesOnceAndBToCDoesNot() {
        val up = roleTierDeliveryNudge(0, 1, 0.14, 0.20)
        assertEquals(0.15, up?.smbBaseline ?: 0.0, 0.0001)
        assertEquals(0.45, up?.mildRatio ?: 0.0, 0.0001)
        assertNull(roleTierDeliveryNudge(1, 1, 0.14, 0.20))
    }

    @Test
    fun aBlankRunningNameIsNotOnTheLowLadder() {
        assertFalse(runningOnLowLadder("", "Profile70", lowRungs))
    }
}
