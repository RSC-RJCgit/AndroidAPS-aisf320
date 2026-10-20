package app.aaps.implementation.maintenance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoIsfExportTextTest {

    @Test
    fun aCommaInTheTimeIsQuotedInTheCsv() {
        val csv = autoIsfExportCsv(listOf(listOf("23/09/2026, 10:00", "6.5", "1.40")))
        assertTrue(csv.startsWith("Time,BGL,UKF,"))
        assertTrue(csv.contains("\"23/09/2026, 10:00\""))
        assertTrue(csv.contains("1.40"))
    }

    @Test
    fun theTextFileSeparatesCellsWithABar() {
        val text = autoIsfExportText(listOf(listOf("10:00", "6.5")))
        assertEquals("10:00 | 6.5", text.lines()[1])
    }

    @Test
    fun settingsLinesAreSortedByKey() {
        val text = autoIsfSettingsText(listOf("b" to "2", "a" to "1"))
        assertEquals("a = 1
b = 2", text)
    }
}
