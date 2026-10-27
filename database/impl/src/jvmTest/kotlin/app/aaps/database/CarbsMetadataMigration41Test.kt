package app.aaps.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.aaps.database.di.databaseMigrations
import app.aaps.database.entities.Carbs
import app.aaps.database.transactions.mergeImportedMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarbsMetadataMigration41Test {
    @Test
    fun `upgrade preserves existing carbs and adds nullable metadata`() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            connection.execSQL("CREATE TABLE carbs (id INTEGER PRIMARY KEY, amount REAL NOT NULL, duration INTEGER NOT NULL, notes TEXT)")
            connection.execSQL("INSERT INTO carbs VALUES (1, 25.0, 60000, 'Existing meal')")
            databaseMigrations.single { it.startVersion == 40 && it.endVersion == 41 }.migrate(connection)
            connection.prepare("SELECT amount, duration, notes, fatGrams, proteinGrams, sourceId, fpuId FROM carbs").use {
                assertTrue(it.step())
                assertEquals(25.0, it.getDouble(0))
                assertEquals(60000L, it.getLong(1))
                assertEquals("Existing meal", it.getText(2))
                for (column in 3..6) assertTrue(it.isNull(column))
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `metadata resync keeps amount duration notes and omitted fields`() {
        val stored = Carbs(timestamp = 1L, duration = 60000L, amount = 25.0, notes = "Existing meal", fatGrams = 10.0)
        val incoming = Carbs(timestamp = 1L, duration = 0L, amount = 1.0, proteinGrams = 12.5, sourceId = "source", fpuId = "group")
        assertTrue(stored.mergeImportedMetadata(incoming))
        assertEquals(25.0, stored.amount)
        assertEquals(60000L, stored.duration)
        assertEquals("Existing meal", stored.notes)
        assertEquals(10.0, stored.fatGrams)
        assertEquals(12.5, stored.proteinGrams)
        assertEquals("source", stored.sourceId)
        assertEquals("group", stored.fpuId)
        assertFalse(stored.mergeImportedMetadata(incoming))
    }
}
