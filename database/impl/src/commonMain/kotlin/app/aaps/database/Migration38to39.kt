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
        connection.addColumnIfMissing(TABLE_AUTO_ISF_VALUES, "targetMgdl", "REAL NOT NULL DEFAULT 0")
        connection.addColumnIfMissing(TABLE_AUTO_ISF_VALUES, "uamCarbImpact", "REAL NOT NULL DEFAULT 0")
        connection.addColumnIfMissing(TABLE_AUTO_ISF_VALUES, "smbDeliveryRatio", "REAL NOT NULL DEFAULT 0")
        connection.addColumnIfMissing(TABLE_AUTO_ISF_VALUES, "acceIsfWeight", "REAL NOT NULL DEFAULT 0")
        connection.addColumnIfMissing(TABLE_AUTO_ISF_VALUES, "ppIsfWeight", "REAL NOT NULL DEFAULT 0")
        connection.addColumnIfMissing(TABLE_AUTO_ISF_VALUES, "fslCalSlope", "REAL NOT NULL DEFAULT 0")
        connection.addColumnIfMissing(TABLE_AUTO_ISF_VALUES, "cob", "REAL NOT NULL DEFAULT 0")
        connection.addColumnIfMissing(TABLE_AUTO_ISF_VALUES, "basal", "REAL NOT NULL DEFAULT 0")
        connection.addColumnIfMissing(TABLE_AUTO_ISF_VALUES, "note", "TEXT NOT NULL DEFAULT ''")
        // These indexes are added again after the database opens. Room rejects the upgrade if they
        // are still present when it checks the schema.
        connection.execSQL("DROP INDEX IF EXISTS `index_temporaryBasals_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_extendedBoluses_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_temporaryTargets_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_carbs_end`")
        connection.execSQL("DROP INDEX IF EXISTS `index_runningModes_end`")
    }
}
