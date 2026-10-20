package app.aaps.core.objects.autoisf

import app.aaps.core.data.model.AIV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoIsfHistoryTest {

    @Test
    fun noFactorWinsWhenAllStayNearOne() {
        assertEquals(AutoIsfFactor.NONE, dominantAutoIsfFactor(1.0, 1.005, 0.995, 1.0))
    }

    @Test
    fun largestFactorWinsAndAcceWinsATie() {
        assertEquals(AutoIsfFactor.BG, dominantAutoIsfFactor(1.02, 1.20, 0.90, 1.05))
        assertEquals(AutoIsfFactor.ACCE, dominantAutoIsfFactor(1.20, 0.80, 1.0, 1.0))
        assertEquals(AutoIsfFactor.PP, dominantAutoIsfFactor(1.0, 1.0, 0.70, 1.10))
        assertEquals(AutoIsfFactor.DURA, dominantAutoIsfFactor(1.0, 1.0, 1.0, 1.30))
    }

    @Test
    fun neutralFactorAndZeroInsulinShowADash() {
        assertEquals("--", autoIsfAdjustmentText(1.0) { "1.00" })
        assertEquals("1.20", autoIsfAdjustmentText(1.2) { "1.20" })
        assertEquals("--", autoIsfAmountText(0.0) { "0.00" })
        assertEquals("0.10", autoIsfAmountText(0.1) { "0.10" })
    }

    @Test
    fun rowKeepsSmbFlagAfterTheCellBecomesADash() {
        val rows = listOf(sample(smb = 0.0), sample(smb = 0.2)).autoIsfHistoryRows(
            timeText = { "10:00" },
            glucoseText = { "5.5" },
            deltaText = { "+0.1" },
            format2 = { "1.00" }
        )
        assertFalse(rows[0].hasSmb)
        assertEquals("--", rows[0].smb)
        assertTrue(rows[1].hasSmb)
        assertEquals(AutoIsfFactor.NONE, rows[0].smbFactor)
    }

    @Test
    fun aRowWithoutItsOwnNoteShowsTheNotesWrittenSinceTheRowBefore() {
        // 2026-10-21: a client's rows come from the loop phone's results and carry no note, so the column was empty
        val minute = 60_000L
        val rows = listOf(sample(smb = 0.0, time = 10 * minute), sample(smb = 0.0, time = 5 * minute))
        val notes = listOf(4 * minute to "Old", 6 * minute to "MoreMJ", 9 * minute to "Db10")
        assertEquals("MoreMJ Db10", notesForRow(rows, 0, notes))
        assertEquals("Old", notesForRow(rows, 1, notes))
        val shown = rows.autoIsfHistoryRows(timeText = { "" }, glucoseText = { "" }, deltaText = { "" }, format2 = { "" }, notes = notes)
        assertEquals("MoreMJ Db10", shown[0].note)
    }

    @Test
    fun theMjColumnShowsTheNewestMjNoteAtOrBeforeTheRow() {
        val minute = 60_000L
        val rows = listOf(sample(smb = 0.0, time = 30 * minute), sample(smb = 0.0, time = 10 * minute), sample(smb = 0.0, time = 2 * minute))
        val notes = listOf(5 * minute to "MJsAc", 8 * minute to "Db10", 20 * minute to "MJs3")
        val shown = rows.autoIsfHistoryRows(timeText = { "" }, glucoseText = { "" }, deltaText = { "" }, format2 = { "" }, notes = notes)
        assertEquals(listOf("MJ3", "MJa", "NOM"), shown.map { it.mj })
    }

    @Test
    fun theExportHasOneNameForEveryCell() {
        val row = listOf(sample(smb = 0.1)).autoIsfHistoryRows(timeText = { "10:00" }, glucoseText = { "" }, deltaText = { "" }, format2 = { "" }).single()
        assertEquals(AUTO_ISF_HISTORY_EXPORT_HEADER.size, row.cells().size)
        assertEquals("10:00", row.cells().first())
    }

    @Test
    fun mjNotesAreReadAndOtherNotesAreNot() {
        assertEquals("MJ2", mjStateFromNote("MJs2"))
        assertEquals("NOM", mjStateFromNote("MJoff 06:00"))
        assertEquals(null, mjStateFromNote("Db10"))
    }

    private fun sample(smb: Double, time: Long = 1L) = AIV(
        timestamp = time,
        acceIsf = 1.0,
        bgIsf = 1.0,
        ppIsf = 1.0,
        duraIsf = 1.0,
        finalIsf = 1.0,
        glucose = 100.0,
        delta = 1.0,
        shortAvgDelta = 1.0,
        longAvgDelta = 1.0,
        bgAcceleration = 0.0,
        iob = 0.0,
        smbDelivered = smb
    )
}
