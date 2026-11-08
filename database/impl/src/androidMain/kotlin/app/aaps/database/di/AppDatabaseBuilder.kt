package app.aaps.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase.Callback
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.aaps.database.AppDatabase
import app.aaps.database.V3ForkDatabaseRepair

/**
 * Builds the Room database. Not a DI module any more - `DatabaseBindings` provides the instance and
 * this class only holds the builder, so the instrumented tests in this module can open a database
 * exactly the way the app does.
 *
 * The migrations are not here. They live in `DatabaseMigrations` in commonMain, because every
 * platform needs the same ones - see the note there.
 */
open class AppDatabaseBuilder {

    internal fun provideAppDatabase(context: Context, fileName: String): AppDatabase {
        repairV3ForkDatabase(context, fileName)
        return Room
            .databaseBuilder(context, AppDatabase::class.java, fileName)
            // Bundled SQLite driver: ships its own SQLite compiled from source instead of the
            // device's framework SQLite. This is Google's recommended driver (consistent engine
            // across all devices) and, crucially, it does not allocate the framework CursorWindow
            // mem buffer, eliminating CursorWindowAllocationException on memory-constrained devices.
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*databaseMigrations)
            .addCallback(object : Callback() {
                // Driver mode delivers an SQLiteConnection (not a SupportSQLiteConnection), so the
                // SupportSQLiteDatabase overload of onOpen never fires here — the connection overload must.
                override fun onOpen(connection: SQLiteConnection) {
                    super.onOpen(connection)
                    createCustomIndexes(connection)
                    // Fork: bring the v3 AutoISF history across after a v3 fork database was upgraded in place.
                    V3ForkDatabaseRepair.copyKeptRowsAfterOpen(connection)
                }
            })
            .fallbackToDestructiveMigration(false)
            .build()
    }

    /**
     * Fork (2026-11-08): a database left by the v3 AutoISF fork (3426) is set back to the version v4 shares with it,
     * so Room can upgrade it with v4's own steps. See [V3ForkDatabaseRepair]. Any error here is only reported: Room
     * then opens the file as before and shows its own error.
     */
    private fun repairV3ForkDatabase(context: Context, fileName: String) {
        val file = context.getDatabasePath(fileName)
        if (!file.exists()) return
        try {
            val connection = BundledSQLiteDriver().open(file.absolutePath)
            try {
                if (V3ForkDatabaseRepair.repairBeforeOpen(connection))
                    android.util.Log.i("AAPS-DB", "v3 fork database set back to version ${V3ForkDatabaseRepair.REWIND_VERSION} for v4's upgrade")
            } finally {
                connection.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("AAPS-DB", "v3 fork database check failed", e)
        }
    }

    /**
     * In-memory copy of the same database, used by the instrumented tests.
     *
     * With the same bundled SQLite as [provideAppDatabase]. It used to run on the device's own SQLite,
     * which on the CI emulator (Android 12) is far older than the bundled one, so the tests checked an
     * engine the app does not use. A `json_extract` query over results holding `NaN` worked in the app and
     * failed there, and with it the graph phase of every calculation in the instrumented tests.
     */
    internal fun provideInMemoryAppDatabase(context: Context) =
        Room
            .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .addCallback(object : Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    super.onOpen(connection)
                    createCustomIndexes(connection)
                }
            })
            .fallbackToDestructiveMigration(false)
            .build()
}
