package app.aaps.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.aaps.database.di.createCustomIndexes
import app.aaps.database.di.databaseMigrations
import app.aaps.database.entities.TABLE_AUTO_ISF_VALUES
import app.aaps.database.entities.TABLE_BOLUSES
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Fork (2026-11-08): installing v4 over the v3 AutoISF fork (3426) stopped at "Initialization failed" with
 * "Migration didn't properly handle: boluses". These tests build the v3 fork's real version-40 database from its own
 * exported schema (`v3fork/40.json`, copied from the 3426 repo), then open it the way the phone does: repair, then
 * Room with every v4 migration. Room checks the whole schema when it opens, so a database that opens here is one
 * the phone will open.
 */
class V3ForkDatabaseRepairTest {

    private val directory = createTempDirectory("aaps-v3fork-repair-test")
    private val path = "$directory/aaps.db"
    private var database: AppDatabase? = null

    @AfterTest
    fun tearDown() {
        database?.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a v3 fork database is recognised and set back to 31`() {
        createV3ForkDatabase()

        withConnection { connection ->
            assertThat(V3ForkDatabaseRepair.isV3ForkDatabase(connection)).isTrue()
            assertThat(V3ForkDatabaseRepair.repairBeforeOpen(connection)).isTrue()
            assertThat(userVersion(connection)).isEqualTo(V3ForkDatabaseRepair.REWIND_VERSION)
            assertThat(tableExists(connection, V3ForkDatabaseRepair.KEPT_TABLE)).isTrue()
            assertThat(tableExists(connection, TABLE_AUTO_ISF_VALUES)).isFalse()
            // A second look finds nothing to do, so a later start never repeats it.
            assertThat(V3ForkDatabaseRepair.repairBeforeOpen(connection)).isFalse()
        }
    }

    @Test
    fun `after the repair v4 opens the v3 fork database and keeps its rows`() {
        createV3ForkDatabase()
        withConnection { V3ForkDatabaseRepair.repairBeforeOpen(it) }

        val db = openWithV4Migrations()

        runBlocking {
            db.useReaderConnection { connection ->
                assertThat(connection.usePrepared("SELECT COUNT(*), MAX(amount) FROM `$TABLE_BOLUSES`") { it.step(); it.getLong(0) to it.getDouble(1) })
                    .isEqualTo(1L to 2.5)
                // The bolus now has v4's insulin columns, which is what Room refused before.
                assertThat(connection.usePrepared("SELECT concentration FROM `$TABLE_BOLUSES`") { it.step(); it.getDouble(0) }).isEqualTo(1.0)
                assertThat(connection.usePrepared("SELECT COUNT(*), MAX(finalIsf), MAX(timestamp) FROM `$TABLE_AUTO_ISF_VALUES`") {
                    it.step(); Triple(it.getLong(0), it.getDouble(1), it.getLong(2))
                }).isEqualTo(Triple(1L, 4.2, AIV_TIME))
                assertThat(connection.usePrepared("SELECT COUNT(*) FROM sqlite_master WHERE name = '${V3ForkDatabaseRepair.KEPT_TABLE}'") {
                    it.step(); it.getLong(0)
                }).isEqualTo(0L)
            }
        }
    }

    @Test
    fun `a database v4 made itself is left alone`() {
        // Room creates the current v4 schema on first open.
        openWithV4Migrations().also { db -> runBlocking { db.useReaderConnection { } } }.close()
        database = null

        withConnection { connection ->
            assertThat(V3ForkDatabaseRepair.isV3ForkDatabase(connection)).isFalse()
            assertThat(V3ForkDatabaseRepair.repairBeforeOpen(connection)).isFalse()
            assertThat(userVersion(connection)).isEqualTo(DATABASE_VERSION)
        }
    }

    @Test
    fun `an older database without the v3 fork table is left alone`() {
        withConnection { connection ->
            connection.execSQL("CREATE TABLE `$TABLE_BOLUSES` (`id` INTEGER PRIMARY KEY, `amount` REAL NOT NULL)")
            connection.execSQL("PRAGMA user_version = 32")
            assertThat(V3ForkDatabaseRepair.isV3ForkDatabase(connection)).isFalse()
        }
    }

    /** Every table, index and Room row exactly as the v3 fork's `40.json` declares them, plus one bolus and one AIV row. */
    private fun createV3ForkDatabase() {
        val schema = Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader.getResource("v3fork/40.json")).readText()
        ).jsonObject.getValue("database").jsonObject
        withConnection { connection ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                connection.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    connection.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
                if (table == TABLE_AUTO_ISF_VALUES) insertAivRow(connection, entity.getValue("fields").jsonArray.map { it.jsonObject })
            }
            schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL(
                "INSERT INTO `$TABLE_BOLUSES` (version, dateCreated, isValid, timestamp, utcOffset, amount, type, isBasalInsulin) " +
                    "VALUES (1, 0, 1, 1790000000000, 0, 2.5, 'NORMAL', 0)"
            )
            connection.execSQL("PRAGMA user_version = 40")
        }
    }

    /** One AIV row: every NOT NULL column filled (0 or ''), with a known timestamp and final ISF to look for afterwards. */
    private fun insertAivRow(connection: SQLiteConnection, fields: List<kotlinx.serialization.json.JsonObject>) {
        val required = fields.filter { it["notNull"]?.jsonPrimitive?.boolean == true && it.getValue("columnName").jsonPrimitive.content != "id" }
        val names = required.map { it.getValue("columnName").jsonPrimitive.content }
        val values = required.map { field ->
            when (field.getValue("columnName").jsonPrimitive.content) {
                "timestamp" -> AIV_TIME.toString()
                "finalIsf"  -> "4.2"
                "isValid"   -> "1"
                else        -> if (field.getValue("affinity").jsonPrimitive.content == "TEXT") "''" else "0"
            }
        }
        connection.execSQL("INSERT INTO `$TABLE_AUTO_ISF_VALUES` (${names.joinToString { "`$it`" }}) VALUES (${values.joinToString()})")
    }

    /** The phone's own builder, minus the Android context: same driver, every v4 migration, same open callback. */
    private fun openWithV4Migrations(): AppDatabase =
        Room.databaseBuilder<AppDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*databaseMigrations)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    super.onOpen(connection)
                    createCustomIndexes(connection)
                    V3ForkDatabaseRepair.copyKeptRowsAfterOpen(connection)
                }
            })
            .fallbackToDestructiveMigration(false)
            .build()
            .also { database = it }

    private fun <T> withConnection(block: (SQLiteConnection) -> T): T {
        val connection = BundledSQLiteDriver().open(path)
        try {
            return block(connection)
        } finally {
            connection.close()
        }
    }

    private fun userVersion(connection: SQLiteConnection): Int =
        connection.prepare("PRAGMA user_version").use { it.step(); it.getLong(0).toInt() }

    private fun tableExists(connection: SQLiteConnection, table: String): Boolean =
        connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { it.step() }

    private companion object {
        const val AIV_TIME = 1790000300000L
    }
}
