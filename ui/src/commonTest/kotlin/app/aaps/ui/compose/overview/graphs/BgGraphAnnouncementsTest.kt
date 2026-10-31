package app.aaps.ui.compose.overview.graphs

import app.aaps.core.interfaces.overview.graph.TherapyEventGraphPoint
import app.aaps.core.interfaces.overview.graph.TherapyEventType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which announcements the BG graph labels at the BG line (2026-11-01). */
class BgGraphAnnouncementsTest {

    private fun event(timestamp: Long, label: String, type: TherapyEventType = TherapyEventType.ANNOUNCEMENT) =
        TherapyEventGraphPoint(timestamp = timestamp, eventType = type, label = label, duration = 0)

    private val events = listOf(
        event(3, "Batt1%"),
        event(1, "_____H4"),
        event(2, "________________Gentle5"),
        event(4, "B"),
        event(5, "Gntl5", TherapyEventType.GENERAL), // a note: stays on the notes graph
        event(6, " ")
    )

    /** Live's hypo alarms, gentle hypo and the rest, as Live's own BG graph shows them */
    @Test
    fun aClientLabelsEveryAnnouncement() {
        assertEquals(
            listOf("_____H4", "________________Gentle5", "Batt1%", "B"),
            bgGraphAnnouncements(events, isClient = true).map { it.label }
        )
    }

    /** The "B" mark is for clients only */
    @Test
    fun aMasterLabelsNoAnnouncementNotEvenB() {
        assertEquals(emptyList<TherapyEventGraphPoint>(), bgGraphAnnouncements(events, isClient = false))
    }
}
