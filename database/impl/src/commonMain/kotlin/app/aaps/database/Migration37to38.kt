package app.aaps.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.aaps.database.entities.TABLE_AUTO_ISF_VALUES

/**
 * Adds the IOB threshold column. Older rows stay 0, and the graph skips those.
 * Android, iOS and desktop all use this same step.
 */
internal val migration37to38 = object : Migration(37, 38) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `iobThEffective` REAL NOT NULL DEFAULT 0"
        )
    }
}
