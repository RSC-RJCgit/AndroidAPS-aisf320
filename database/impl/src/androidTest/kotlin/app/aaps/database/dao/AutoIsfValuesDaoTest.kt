package app.aaps.database.dao

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.database.AppDatabase
import app.aaps.database.di.DatabaseModule
import app.aaps.database.entities.AutoIsfValues
import app.aaps.database.entities.TABLE_AUTOISF_VALUES
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoIsfValuesDaoTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun createDatabase() =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    private fun getDbObjects(supportDb: SupportSQLiteDatabase, type: String): Set<String> {
        val names = mutableSetOf<String>()
        supportDb.query("SELECT name FROM sqlite_master WHERE type = '$type'").use { c ->
            while (c.moveToNext()) names.add(c.getString(0))
        }
        return names
    }

    private fun getTableNames(db: SupportSQLiteDatabase) = getDbObjects(db, "table")
    private fun getIndexNames(db: SupportSQLiteDatabase) = getDbObjects(db, "index")

    private fun insertAndFind(database: AppDatabase) {
        val sc1 = createAutoIsfValues()
        val id = database.autoIsfValuesDao.insert(sc1)
        val sc2 = database.autoIsfValuesDao.findById(id)
        Assert.assertTrue(sc1.contentEqualsTo(sc2!!))
    }

    private fun AutoIsfValues.contentEqualsTo(other: AutoIsfValues): Boolean {
        return this === other || (
            timestamp == other.timestamp &&
                acceIsf == other.acceIsf &&
                bgIsf == other.bgIsf &&
                ppIsf == other.ppIsf &&
                driftIsf == other.driftIsf &&
                duraIsf == other.duraIsf &&
                finalIsf == other.finalIsf &&
                iobThEffective == other.iobThEffective &&
                isValid == other.isValid)
    }

    @Test
    fun new_insertAndFind() {
        createDatabase().also { db ->
            insertAndFind(db)
            db.close()
        }
    }

    @Test
    fun getFromTime() {
        createDatabase().also { db ->
            val dao = db.autoIsfValuesDao
            val timestamp = System.currentTimeMillis()
            val sc1 = createAutoIsfValues(timestamp = timestamp, isf = 8.0)
            val sc2 = createAutoIsfValues(timestamp = timestamp + 1, isf = 15.0)
            dao.insertNewEntry(sc1)
            dao.insertNewEntry(sc2)

            Assert.assertEquals(listOf(sc1, sc2), dao.getFromTime(timestamp).blockingGet())
            Assert.assertEquals(listOf(sc2), dao.getFromTime(timestamp + 1).blockingGet())
            Assert.assertTrue(dao.getFromTime(timestamp + 2).blockingGet().isEmpty())
            db.close()
        }
    }

    @Test
    fun getFromTimeToTime() {
        createDatabase().also { db ->
            val dao = db.autoIsfValuesDao
            val timestamp = System.currentTimeMillis()
            val hr1 = createAutoIsfValues(timestamp = timestamp, isf = 8.0)
            val hr2 = createAutoIsfValues(timestamp = timestamp + 1, isf = 1.50)
            val hr3 = createAutoIsfValues(timestamp = timestamp + 2, isf = 1.60)
            dao.insertNewEntry(hr1)
            dao.insertNewEntry(hr2)
            dao.insertNewEntry(hr3)

            Assert.assertEquals(listOf(hr1, hr2, hr3), dao.getFromTimeToTime(timestamp, timestamp + 2).blockingGet())
            Assert.assertEquals(listOf(hr1, hr2), dao.getFromTimeToTime(timestamp, timestamp + 1).blockingGet())
            Assert.assertEquals(listOf(hr2), dao.getFromTimeToTime(timestamp + 1, timestamp + 1).blockingGet())
            Assert.assertTrue(dao.getFromTimeToTime(timestamp + 3, timestamp + 10).blockingGet().isEmpty())
            db.close()
        }
    }

    companion object {

        private const val TEST_DB_NAME = "testDatabase"

        fun createAutoIsfValues(timestamp: Long? = null, isf: Double = 8.0) =
            AutoIsfValues(
                timestamp = timestamp ?: System.currentTimeMillis(),
                acceIsf = isf,
                bgIsf = isf+0.1,
                ppIsf = isf+0.2,
                driftIsf = isf+0.25,
                duraIsf = isf+0.3,
                finalIsf= isf+0.4,
                iobThEffective = isf+0.5
            )

    }
}