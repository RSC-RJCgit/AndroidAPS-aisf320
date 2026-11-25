package app.aaps.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Fork (2026-11-25): adds [column] to [table] only when the table does not have it yet.
 *
 * The fork's own AutoISF steps (36 to 39) each add columns to `autoIsfValues`. A database whose table already had one
 * of them (the iPlay tablet's master at version 36 already had `ukfRawBgl`) stopped the app at every start with
 * "duplicate column name", so these steps now skip a column that is already there.
 */
internal fun SQLiteConnection.addColumnIfMissing(table: String, column: String, definition: String) {
    val exists = prepare("PRAGMA table_info(`$table`)").use { statement ->
        var found = false
        while (!found && statement.step()) found = statement.getText(1).equals(column, ignoreCase = true)
        found
    }
    if (!exists) execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
}
