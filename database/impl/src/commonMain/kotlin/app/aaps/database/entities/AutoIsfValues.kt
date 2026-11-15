package app.aaps.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One AutoISF loop run, for the AIV table (2026-11-15, ported from the AaAPS v4 fork). Local only, not uploaded:
 * a master writes one row per loop, a client writes one row per loop result it receives from the master.
 */
@Entity(
    tableName = TABLE_AUTO_ISF_VALUES,
    indices = [Index("timestamp")]
)
data class AutoIsfValues(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var timestamp: Long,
    var acceIsf: Double,
    var bgIsf: Double,
    var ppIsf: Double,
    var duraIsf: Double,
    var finalIsf: Double,
    /** mg/dL */
    var glucose: Double,
    /** mg/dL per 5 min */
    var delta: Double,
    var shortAvgDelta: Double,
    var longAvgDelta: Double,
    var bgAcceleration: Double,
    var iob: Double,
    var smbDelivered: Double,
    /** Effective IOB threshold in units */
    var iobThEffective: Double = 0.0,
    /** mg/dL */
    var targetMgdl: Double = 0.0,
    var smbDeliveryRatio: Double = 0.0,
    var cob: Double = 0.0,
    /** Temp basal rate the loop set, U/h */
    var basal: Double = 0.0,
    var steps5: Int = 0,
    var steps15: Int = 0,
    var steps30: Int = 0,
    var steps60: Int = 0,
)
