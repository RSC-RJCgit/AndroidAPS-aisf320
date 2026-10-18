package app.aaps.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The AutoISF values of one loop (AutoISF 3.2.1). Stored on this phone only, not sent to Nightscout. */
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
    var driftIsf: Double,
    var duraIsf: Double,
    var finalIsf: Double,
    var iobThEffective: Double,
)
