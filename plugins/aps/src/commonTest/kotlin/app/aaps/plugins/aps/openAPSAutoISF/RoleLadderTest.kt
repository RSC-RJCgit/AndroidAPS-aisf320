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
