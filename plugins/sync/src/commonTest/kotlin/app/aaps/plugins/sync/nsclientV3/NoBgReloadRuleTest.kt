package app.aaps.plugins.sync.nsclientV3

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoBgReloadRuleTest {

    private val start = 10_000_000L
    private val min = 60_000L

    @Test
    fun countsFromArrivalNotFromTheBgTime() {
        val rule = NoBgReloadRule()
        // The BG is already 10 minutes old when it arrives: no reload yet
        assertFalse(rule.reloadDue(newestBg = start - 10 * min, now = start))
        assertFalse(rule.reloadDue(newestBg = start - 10 * min, now = start + 119_000L))
        assertTrue(rule.reloadDue(newestBg = start - 10 * min, now = start + 2 * min))
    }

    @Test
    fun waitDoublesUpToFifteenMinutes() {
        val rule = NoBgReloadRule()
        val bg = start
        rule.reloadDue(bg, start)
        assertTrue(rule.reloadDue(bg, start + 2 * min))      // first after 2
        assertFalse(rule.reloadDue(bg, start + 5 * min))
        assertTrue(rule.reloadDue(bg, start + 6 * min))      // then 4 later
        assertFalse(rule.reloadDue(bg, start + 13 * min))
        assertTrue(rule.reloadDue(bg, start + 14 * min))     // then 8 later
        assertTrue(rule.reloadDue(bg, start + 29 * min))     // then 15 later
        assertFalse(rule.reloadDue(bg, start + 43 * min))
        assertTrue(rule.reloadDue(bg, start + 44 * min))     // and stays at 15
    }

    @Test
    fun newBgStartsAgainFromTwoMinutes() {
        val rule = NoBgReloadRule()
        rule.reloadDue(start, start)
        assertTrue(rule.reloadDue(start, start + 2 * min))
        assertFalse(rule.reloadDue(start + 3 * min, start + 3 * min)) // new BG arrived
        assertFalse(rule.reloadDue(start + 3 * min, start + 4 * min))
        assertTrue(rule.reloadDue(start + 3 * min, start + 5 * min))
    }

    @Test
    fun noBgAtAllDoesNotReload() {
        assertFalse(NoBgReloadRule().reloadDue(newestBg = null, now = start))
    }
}
