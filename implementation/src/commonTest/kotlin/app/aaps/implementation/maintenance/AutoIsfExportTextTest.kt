package app.aaps.implementation.maintenance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoIsfExportTextTest {

    @Test
    fun aCommaInTheTimeIsQuotedInTheCsv() {
        val csv = autoIsfExportCsv(listOf(listOf("23/09/2026, 10:00", "6.5", "1.40")))
        // The 3426 app's columns since 2026-11-20
        assertTrue(csv.startsWith("Time,BGL,Target,Final,acce,bg,pp,dura,UAMci,SMB,FastRise,"))
        assertTrue(csv.contains("\"23/09/2026, 10:00\""))
        assertTrue(csv.contains("1.40"))
    }

    @Test
    fun theTextFileIsThe3426PaddedTableAndMarksSmbRows() {
        // 2026-11-20: was one " | " line per row
        val text = autoIsfExportText(listOf(listOf("10:00", "6.5", "5.4", "1.00", "1.00", "1.00", "1.00", "1.00", "0.00", "0.30")))
        assertTrue(text.lines()[0].startsWith("Time   BGL  Target  Final"))
        assertTrue(text.lines()[1].startsWith("10:00  6.5  5.4     1.00"))
        assertTrue(text.lines()[1].endsWith("  SMB"))
    }

    @Test
    fun settingsLinesAreSortedByKey() {
        val text = autoIsfSettingsText(listOf("b" to "2", "a" to "1"))
        assertEquals("a = 1\nb = 2", text)
    }
}
