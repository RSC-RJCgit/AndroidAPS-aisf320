package app.aaps.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.aaps.database.entities.TABLE_AUTO_ISF_VALUES

/**
 * Adds the UKF glucose column. Older rows stay 0.
 * Android, iOS and desktop all use this same step.
 */
internal val migration36to37 = object : Migration(36, 37) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `ukfRawBgl` REAL NOT NULL DEFAULT 0"
        )
        connection.execSQL("DROP INDEX IF EXISTS `index_temporaryBasals_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_extendedBoluses_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_temporaryTargets_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_carbs_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_runningModes_end`")
    }
}
