package app.aaps.core.objects.notes

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CareportalNoteStatusTest {

    private val now = 1_750_000_000_000L

    @Test
    fun encodeSharesOnlyTheNewestMatchingNotesInsideTheWindow() {
        val texts = (1L..34L).associate { now - it to "note $it" } +
            (now - CareportalNoteStatus.WINDOW_MS - 1 to "too old") + (now to "no matching note")
        val status = assertNotNull(CareportalNoteStatus.encode(texts, texts.keys - now, now))

        assertEquals((1L..32L).associate { now - it to "note $it" }, CareportalNoteStatus.decode(status, now))
        assertNull(CareportalNoteStatus.encode(texts, emptySet(), now))
    }

    @Test
    fun decodeIgnoresMalformedEntriesAndKeepsRetainedHistory() {
        val old = now - 24L * 60 * 60 * 1000
        val report = buildJsonObject {
            put(old.toString(), "note from yesterday")
            put((now - 1).toString(), "x".repeat(1025))
            put((now - 2).toString(), true)
            put((now - 3).toString(), " ")
            putJsonObject((now - 4).toString()) { put("text", "wrong type") }
            put("+${now - 5}", "wrong key")
            put((now - NoteSmsTexts.KEEP_MS - 1).toString(), "past retention")
            put((now + 6 * 60_000).toString(), "future")
        }

        assertEquals(mapOf(old to "note from yesterday"), CareportalNoteStatus.decode(report, now))
    }
}
