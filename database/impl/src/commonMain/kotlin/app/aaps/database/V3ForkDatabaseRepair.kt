package app.aaps.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.aaps.database.entities.TABLE_AUTO_ISF_VALUES
import app.aaps.database.entities.TABLE_BOLUSES

/**
 * Fork (2026-11-08): lets v4 open, in place, a database written by the v3 AutoISF fork (3426).
 *
 * Both lines number their schema up to 40, but they only agree up to 31. From there the v3 fork's own steps
 * (31 to 40) build its AutoISF history table, `autoIsfValues`, and change nothing else. v4's steps 31 to 41 rebuild
 * boluses and profile switches with the insulin columns, change daily totals, calibrations and carbs, and build
 * v4's own `autoIsfValues`. A v3 fork database says "40", so Room ran only 40 to 41 and then refused the boluses
 * table ("Migration didn't properly handle: boluses"), and the app stopped at "Initialization failed".
 *
 * The repair, in two parts:
 * - [repairBeforeOpen], before Room opens the file: move the v3 fork's `autoIsfValues` aside as [KEPT_TABLE] (its
 *   indexes dropped first, as their names are the ones v4 creates) and set the version back to [REWIND_VERSION].
 *   Room then runs v4's own steps 31 to 41, so every other table is converted the normal v4 way.
 * - [copyKeptRowsAfterOpen], once Room has opened it: copy the kept rows into v4's `autoIsfValues`, by every column
 *   the two tables share, and drop [KEPT_TABLE]. A column only v4 has keeps its default (0).
 *
 * A v4 database is never touched: from version 33 on, v4's boluses table has `concentration`, and below 35 a v4
 * database has no `autoIsfValues` table.
 */
internal object V3ForkDatabaseRepair {

    /** The last schema version v4 and the v3 fork share. */
    const val REWIND_VERSION = 31

    /** Where the v3 fork's AutoISF history waits while Room runs v4's steps. */
    const val KEPT_TABLE = "autoIsfValues_v3"

    /** True when [connection] holds a database written by the v3 AutoISF fork, which v4 cannot open as it is. */
    fun isV3ForkDatabase(connection: SQLiteConnection): Boolean {
        if (userVersion(connection) !in (REWIND_VERSION + 1)..40) return false
        if (!tableExists(connection, TABLE_AUTO_ISF_VALUES) || !tableExists(connection, TABLE_BOLUSES)) return false
        return "concentration" !in columns(connection, TABLE_BOLUSES)
    }

    /**
     * Run before Room opens the database. Returns true when it found a v3 fork database and set it back to
     * [REWIND_VERSION]. All or nothing: on any error nothing is changed and the error is thrown.
     */
    fun repairBeforeOpen(connection: SQLiteConnection): Boolean {
        if (!isV3ForkDatabase(connection)) return false
        val indexes = buildList {
            connection.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND sql IS NOT NULL").use {
                it.bindText(1, TABLE_AUTO_ISF_VALUES)
                while (it.step()) add(it.getText(0))
            }
        }
        connection.execSQL("BEGIN IMMEDIATE")
        try {
            indexes.forEach { connection.execSQL("DROP INDEX IF EXISTS `$it`") }
            connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` RENAME TO `$KEPT_TABLE`")
            connection.execSQL("PRAGMA user_version = $REWIND_VERSION")
            connection.execSQL("COMMIT")
        } catch (e: Throwable) {
            connection.execSQL("ROLLBACK")
            throw e
        }
        return true
    }

    /**
     * Run when Room has opened the database. Copies the kept v3 AutoISF rows into v4's table and drops [KEPT_TABLE].
     * Returns the number of rows copied, or null when there was nothing to do or the copy failed. On a failure the
     * kept table stays, so the rows are not lost and the next start tries again; the app opens either way.
     */
    fun copyKeptRowsAfterOpen(connection: SQLiteConnection): Int? {
        if (!tableExists(connection, KEPT_TABLE)) return null
        val target = columns(connection, TABLE_AUTO_ISF_VALUES).toSet()
        val shared = columns(connection, KEPT_TABLE).filter { it in target }
        connection.execSQL("SAVEPOINT v3_fork_repair")
        return try {
            var copied = 0
            if (shared.isNotEmpty()) {
                val list = shared.joinToString { "`$it`" }
                connection.execSQL("INSERT OR IGNORE INTO `$TABLE_AUTO_ISF_VALUES` ($list) SELECT $list FROM `$KEPT_TABLE`")
                copied = connection.prepare("SELECT changes()").use { it.step(); it.getLong(0).toInt() }
            }
            connection.execSQL("DROP TABLE `$KEPT_TABLE`")
            connection.execSQL("RELEASE v3_fork_repair")
            copied
        } catch (e: Exception) {
            connection.execSQL("ROLLBACK TO v3_fork_repair")
            connection.execSQL("RELEASE v3_fork_repair")
            null
        }
    }

    private fun userVersion(connection: SQLiteConnection): Int =
        connection.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0).toInt() else 0 }

    private fun tableExists(connection: SQLiteConnection, table: String): Boolean =
        connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use {
            it.bindText(1, table)
            it.step()
        }

    private fun columns(connection: SQLiteConnection, table: String): List<String> = buildList {
        connection.prepare("PRAGMA table_info(`$table`)").use { while (it.step()) add(it.getText(1)) }
    }
}
