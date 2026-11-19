package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live, 20 Nov 2026: a textbook compression (raw 109 to 68 mg/dL from 01:09 to 01:13 after a flat quarter hour, no carbs, almost
 * no insulin on board) gave no CompSusp, because the check ran on the UKF-smoothed series. Since 2026-11-20 it runs on the raw
 * readings and needs two loop cycles in a row. These are Live's raw (noise) values from its log, one per minute.
 */
class CompressionLive20NovTest {

    private val raw = listOf(
        1795096820994L to 117.0, 1795096882220L to 121.0, 1795096941503L to 121.0, 1795097000787L to 122.0, 1795097061629L to 122.0,
        1795097121293L to 122.0, 1795097180966L to 123.0, 1795097241021L to 123.0, 1795097300695L to 122.0, 1795097361926L to 121.0,
        1795097421205L to 120.0, 1795097482427L to 119.0, 1795097541325L to 120.0, 1795097602150L to 120.0, 1795097661051L to 121.0,
        1795097721111L to 121.0, 1795097781948L to 119.0, 1795097840822L to 119.0, 1795097901685L to 118.0, 1795097961355L to 117.0,
        1795098022191L to 116.0, 1795098081081L to 116.0, 1795098141922L to 115.0, 1795098200809L to 114.0, 1795098262431L to 113.0,
        1795098322098L to 112.0, 1795098380991L to 111.0, 1795098441825L to 110.0, 1795098500740L to 109.0, 1795098561943L to 108.0,
        1795098621617L to 108.0, 1795098681676L to 107.0, 1795098740956L to 106.0, 1795098801798L to 106.0, 1795098861074L to 107.0,
        1795098920747L to 107.0, 1795098981586L to 107.0, 1795099042424L to 108.0, 1795099101926L to 110.0, 1795099161769L to 109.0,
        1795099222606L to 101.0, 1795099280716L to 87.0, 1795099340776L to 76.0, 1795099401612L to 68.0, 1795099461285L to 65.0,
        1795099522515L to 61.0, 1795099581794L to 55.0, 1795099642634L to 53.0, 1795099702290L to 53.0, 1795099762749L to 53.0,
        1795099820865L to 53.0,
    )

    /** About what the insulin on board explained per 5 minutes then: activity ~0.006 U/min x ISF ~150 mg/dL x 5. */
    private val explained = 4.5

    private fun clock(t: Long): String {
        val m = ((t / 60_000L) + 10 * 60 + 30) % (24 * 60) // Adelaide summer time, UTC+10:30
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    private fun window(i: Int): List<Pair<Long, Double>> = raw.filter { it.first in (raw[i].first - 30 * 60_000L)..raw[i].first }

    @Test fun theRawDropIsFlaggedFrom0111To0115() {
        val flagged = raw.indices.mapNotNull { i ->
            clock(raw[i].first).takeIf { it in "01:09".."01:20" && CompressionDetector.suspect(window(i), explained) != null }
        }
        assertEquals(listOf("01:11", "01:12", "01:13", "01:14", "01:15"), flagged)
    }

    @Test fun theNoteComesOnTheSecondCycleInARow() {
        // Replays the caller's two-cycles rule: the first flag (01:11) only marks, the next one (01:12) adds the note
        var firstFlagAt = 0L
        var noteAt: String? = null
        for (i in raw.indices) {
            val now = raw[i].first
            if (CompressionDetector.suspect(window(i), explained) == null) { firstFlagAt = 0L; continue }
            if (!CompressionDetector.secondCycle(firstFlagAt, now)) { firstFlagAt = now; continue }
            noteAt = clock(now)
            break
        }
        assertEquals("01:12", noteAt)
    }

    @Test fun oneStrayReadingIsNotEnough() {
        // The same flat quarter hour, then one reading 2 mmol down and straight back
        val flat = List(25) { it * 60_000L to 110.0 }
        val spike = flat + ((25 * 60_000L) to 74.0)
        val back = spike + ((26 * 60_000L) to 110.0)
        assertNotNull(CompressionDetector.suspect(spike, 0.0))
        assertNull(CompressionDetector.suspect(back, 0.0))
    }

    @Test fun aSecondFlagMustFollowWithinSixMinutes() {
        assertFalse(CompressionDetector.secondCycle(0L, 1_000_000L))
        assertTrue(CompressionDetector.secondCycle(1_000_000L, 1_060_000L))
        assertTrue(CompressionDetector.secondCycle(1_000_000L, 1_000_000L + 6 * 60_000L))
        assertFalse(CompressionDetector.secondCycle(1_000_000L, 1_000_000L + 7 * 60_000L))
    }
}
