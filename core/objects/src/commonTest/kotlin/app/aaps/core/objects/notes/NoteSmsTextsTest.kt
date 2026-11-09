package app.aaps.core.objects.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteSmsTextsTest {

    private val now = 100L * 24 * 60 * 60 * 1000

    @Test
    fun textsOlderThanThirtyDaysAreRemoved() {
        val texts = mapOf(now - NoteSmsTexts.KEEP_MS - 1 to "old", now - 1000L to "new")
        assertEquals(mapOf(now - 1000L to "new"), NoteSmsTexts.prune(texts, now))
    }

    @Test
    fun onlyTheNewestAreKept() {
        val texts = (0 until NoteSmsTexts.MAX_ENTRIES + 5).associate { (now - it * 1000L) to "t$it" }
        val kept = NoteSmsTexts.prune(texts, now)
        assertEquals(NoteSmsTexts.MAX_ENTRIES, kept.size)
        assertTrue(kept.containsKey(now))
        assertFalse(kept.containsKey(now - (NoteSmsTexts.MAX_ENTRIES + 4) * 1000L))
    }

    @Test
    fun parseReadsSavedTextsAndIgnoresBadKeys() {
        val parsed = NoteSmsTexts.parse("""{"1000":"Shower12: g=7.8 iobTH=12","x":"bad"}""")
        assertEquals(mapOf(1000L to "Shower12: g=7.8 iobTH=12"), parsed)
    }

    @Test
    fun brokenJsonGivesNoTexts() {
        assertTrue(NoteSmsTexts.parse("not json").isEmpty())
    }
}
