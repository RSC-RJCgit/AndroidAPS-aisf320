package app.aaps.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.aaps.database.entities.TABLE_AUTO_ISF_VALUES

/**
 * Adds the AutoISF loop table. Existing treatment rows are left as they are.
 * Android, iOS and desktop all use this same step.
 */
internal val migration35to36 = object : Migration(35, 36) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `$TABLE_AUTO_ISF_VALUES` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`acceIsf` REAL NOT NULL, " +
                "`bgIsf` REAL NOT NULL, " +
                "`ppIsf` REAL NOT NULL, " +
                "`duraIsf` REAL NOT NULL, " +
                "`finalIsf` REAL NOT NULL, " +
                "`glucose` REAL NOT NULL, " +
                "`delta` REAL NOT NULL, " +
                "`shortAvgDelta` REAL NOT NULL, " +
                "`longAvgDelta` REAL NOT NULL, " +
                "`bgAcceleration` REAL NOT NULL, " +
                "`iob` REAL NOT NULL, " +
                "`smbDelivered` REAL NOT NULL)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_autoIsfValues_timestamp` ON `$TABLE_AUTO_ISF_VALUES` (`timestamp`)"
        )
        // These indexes are added after the database opens. They are not part of the Room
        // tables. Room compares the live database to the tables after this step, so they
        // must be gone here. The next open creates them again.
        connection.execSQL("DROP INDEX IF EXISTS `index_temporaryBasals_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_extendedBoluses_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_temporaryTargets_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_carbs_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_runningModes_end`")
    }
}
