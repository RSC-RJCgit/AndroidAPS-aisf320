package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MjRelayTest {

    @Test
    fun theButtonNotesAndTheManualStateNotesMapToAState() {
        assertEquals("MJ active", mjStateForRelayNote("MJ active"))
        assertEquals("MJ active", mjStateForRelayNote("MJsAc"))
        assertEquals("NOMJremains", mjStateForRelayNote("NOMJremains"))
        assertEquals("NOMJremains", mjStateForRelayNote("MJsNO"))
        assertEquals("MJ2", mjStateForRelayNote("MJs2"))
        assertEquals("MJ3", mjStateForRelayNote(" MJs3 "))
    }

    @Test
    fun anyOtherNoteIsNotAnMjState() {
        assertNull(mjStateForRelayNote("MJ"))
        assertNull(mjStateForRelayNote("BsUp"))
        assertNull(mjStateForRelayNote(""))
        assertNull(mjStateForRelayNote(null))
    }
}
