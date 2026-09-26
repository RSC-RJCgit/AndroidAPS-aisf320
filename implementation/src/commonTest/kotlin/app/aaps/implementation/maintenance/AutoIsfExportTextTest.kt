package app.aaps.implementation.maintenance

import app.aaps.core.data.model.AIV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoIsfExportTextTest {

    @Test
    fun aNeutralFactorAndAZeroInsulinAmountAreADash() {
        val cells = autoIsfExportCells(
            row = sample(acce = 1.0, smb = 0.0),
            timeText = { "10:00" },
            glucoseText = { "6.5" },
            deltaText = { "+0.2" },
            format2 = { it.toString() }
        )
        assertEquals("--", cells[3])
        assertEquals("--", cells[12])
        assertEquals("6.5", cells[1])
    }

    @Test
    fun aCommaInTheTimeIsQuotedInTheCsv() {
        val csv = autoIsfExportCsv(
            listOf(
                autoIsfExportCells(
                    row = sample(acce = 1.4, smb = 0.2),
                    timeText = { "23/09/2026, 10:00" },
                    glucoseText = { "6.5" },
                    deltaText = { "+0.2" },
                    format2 = { "1.40" }
                )
            )
        )
        assertTrue(csv.startsWith("Time,BGL,Final,acce,bg,pp,dura,Accel,Delta,Short,Long,IOB,SMB\n"))
        assertTrue(csv.contains("\"23/09/2026, 10:00\""))
        assertTrue(csv.contains("1.40"))
    }

    @Test
    fun settingsLinesAreSortedByKey() {
        val text = autoIsfSettingsText(listOf("b" to "2", "a" to "1"))
        assertEquals("a = 1\nb = 2", text)
    }

    private fun sample(acce: Double, smb: Double) = AIV(
        timestamp = 0L,
        acceIsf = acce,
        bgIsf = 1.0,
        ppIsf = 1.0,
        duraIsf = 1.0,
        finalIsf = 1.2,
        glucose = 120.0,
        delta = 4.0,
        shortAvgDelta = 3.0,
        longAvgDelta = 2.0,
        bgAcceleration = 0.5,
        iob = 1.0,
        smbDelivered = smb
    )
}
