package app.aaps.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.aaps.database.entities.TABLE_AUTO_ISF_VALUES

/**
 * Stores the loop values the history table shows beside the ISF factors.
 * Older rows stay 0 or blank. Android, iOS and desktop all use this same step.
 */
internal val migration38to39 = object : Migration(38, 39) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `targetMgdl` REAL NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `uamCarbImpact` REAL NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `smbDeliveryRatio` REAL NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `acceIsfWeight` REAL NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `ppIsfWeight` REAL NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `fslCalSlope` REAL NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `cob` REAL NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `basal` REAL NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `$TABLE_AUTO_ISF_VALUES` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''")
        // These indexes are added again after the database opens. Room rejects the upgrade if they
        // are still present when it checks the schema.
        connection.execSQL("DROP INDEX IF EXISTS `index_temporaryBasals_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_extendedBoluses_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_temporaryTargets_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_carbs_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_runningModes_end`")
    }
}
