package app.aaps.plugins.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodedLocationNoteTest {

    @Test
    fun addressAndAirportSlotsGetTheirOwnInAndOutNotes() {
        assertEquals("L1in", codedLocationNoteCode("automation_address_1", arriving = true))
        assertEquals("L1out", codedLocationNoteCode("automation_address_1", arriving = false))
        assertEquals("L5out", codedLocationNoteCode("automation_address_5", arriving = false))
        assertEquals("A1in", codedLocationNoteCode("automation_airport_1", arriving = true))
        assertEquals("A3out", codedLocationNoteCode("automation_airport_3", arriving = false))
    }

    @Test
    fun anUnknownSlotHasNoNoteCode() {
        assertNull(codedLocationNoteCode("automation_address_9", arriving = true))
        assertNull(codedLocationNoteCode("other", arriving = false))
    }
}
