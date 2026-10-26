package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rule A (2026-10-26): no SMB while HP2 is below the limit; a limit of 0 turns it off. */
class Hp2NoSmbTest {

    @Test
    fun belowTheLimitBlocksSmb() {
        // 26 Oct 17:01: HP2 about 5.1 at first, 4.4 by 17:06
        assertTrue(hp2BlocksSmb(4.4, 5.0))
        assertTrue(hp2BlocksSmb(4.99, 5.0))
    }

    @Test
    fun atOrAboveTheLimitAllowsSmb() {
        assertFalse(hp2BlocksSmb(5.0, 5.0))
        assertFalse(hp2BlocksSmb(7.2, 5.0))
    }

    @Test
    fun zeroTurnsItOff() {
        assertFalse(hp2BlocksSmb(2.0, 0.0))
    }
}
