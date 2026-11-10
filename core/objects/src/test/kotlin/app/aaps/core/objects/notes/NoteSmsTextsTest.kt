package app.aaps.core.objects.notes

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

class NoteSmsTextsTest {

    private val now = 100L * 24 * 60 * 60 * 1000

    @Test
    fun textsOlderThanThirtyDaysAreRemoved() {
        val texts = mapOf(now - NoteSmsTexts.KEEP_MS - 1 to "old", now - 1000L to "new")
        assertThat(NoteSmsTexts.prune(texts, now)).containsExactly(now - 1000L, "new")
    }

    @Test
    fun onlyTheNewestAreKept() {
        val texts = (0 until NoteSmsTexts.MAX_ENTRIES + 5).associate { (now - it * 1000L) to "t$it" }
        val kept = NoteSmsTexts.prune(texts, now)
        assertThat(kept).hasSize(NoteSmsTexts.MAX_ENTRIES)
        assertThat(kept.keys.max()).isEqualTo(now)
        assertThat(kept).doesNotContainKey(now - (NoteSmsTexts.MAX_ENTRIES + 4) * 1000L)
    }

    @Test
    fun parseReadsSavedTextsAndIgnoresBadKeys() {
        val parsed = NoteSmsTexts.parse("""{"1000":"Shower12: g=7.8 iobTH=12","x":"bad"}""")
        assertThat(parsed).containsExactly(1000L, "Shower12: g=7.8 iobTH=12")
    }

    @Test
    fun brokenJsonGivesNoTexts() {
        assertThat(NoteSmsTexts.parse("not json")).isEmpty()
    }

    @Test
    fun taggedSmsGivesBackNoteTimeAndText() {
        val noteTime = 1794187459472L
        val sms = NoteSmsTexts.tagged("Shower12 raw +1.2: g=7.8 iobTH=12", noteTime)
        assertThat(NoteSmsTexts.untag(sms)).isEqualTo(noteTime to "Shower12 raw +1.2: g=7.8 iobTH=12")
    }

    @Test
    fun smsWithoutTagIsNotANoteSms() {
        assertThat(NoteSmsTexts.untag("BG 7.8 mmol/L")).isNull()
        assertThat(NoteSmsTexts.untag("#nmhb2k3fk")).isNull()
    }

    @Test
    fun deviceStatusOnlyIncludesRecentTextsWithMatchingNotes() {
        val old = now - NoteSmsTexts.DEVICE_STATUS_WINDOW_MS - 1
        val texts = mapOf(now to "Full text", now - 1 to "No matching note", old to "Old text")
        val payload = requireNotNull(NoteSmsTexts.forDeviceStatus(texts, setOf(now, old), now))
        assertThat(NoteSmsTexts.fromDeviceStatus(payload, now)).containsExactly(now, "Full text")
    }

    @Test
    fun deviceStatusRejectsMalformedOrFutureTexts() {
        val payload = JSONObject().put(now.toString(), "Full text")
            .put((now - 1).toString(), 123)
            .put((now + 6 * 60_000L).toString(), "Future text")
            .put("bad time", "Bad timestamp")
        assertThat(NoteSmsTexts.fromDeviceStatus(payload, now)).containsExactly(now, "Full text")
    }
}
