package app.aaps.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One stored AutoISF loop. Local only. It is not sent to Nightscout. */
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
    var glucose: Double,
    var delta: Double,
    var shortAvgDelta: Double,
    var longAvgDelta: Double,
    var bgAcceleration: Double,
    var iob: Double,
    var smbDelivered: Double,
    /** UKF-smoothed raw glucose, mg/dL. Older rows are 0. */
    var ukfRawBgl: Double = 0.0,
    /** Effective IOB threshold in units. Older rows are 0. */
    var iobThEffective: Double = 0.0,
)
