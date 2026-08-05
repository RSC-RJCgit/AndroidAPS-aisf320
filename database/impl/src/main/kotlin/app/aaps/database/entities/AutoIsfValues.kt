package app.aaps.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.DBEntryWithTime
import app.aaps.database.entities.interfaces.TraceableDBEntry
import java.util.TimeZone

/** AutoISF key values for plotting in subgraph. */
@Entity(
    tableName = TABLE_AUTOISF_VALUES,
    indices = [Index("id"), Index("timestamp")]
)
data class AutoIsfValues(
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
    /** Milliseconds since the epoch. End of the sampling period, i.e. the value is
     *  sampled from timestamp-duration to timestamp. */
    override var timestamp: Long,
    var acceIsf: Double,
    var bgIsf: Double,
    var ppIsf: Double,
    val driftIsf: Double,       // place holder
    val duraIsf: Double,
    var finalIsf: Double,
    var iobThEffective: Double,
    var glucose: Double = 0.0,
    var insulinReq: Double = 0.0,
    var tbrRate: Double = 0.0,
    var smbDelivered: Double = 0.0,
    var delta: Double = 0.0,
    var shortAvgDelta: Double = 0.0,
    var bgAcceleration: Double = 0.0,
    var smbDeliveryRatio: Double = 0.0,
    var iob: Double = 0.0,
    var acceIsfWeight: Double = 0.0,
    var fslCalSlope: Double = 0.0,
    /** UAM Carb Impact (uci) -- deviation-derived BG-impact rate, mg/dL per 5min. See RT.autoIsfUamCarbImpact. */
    var uamCarbImpact: Double = 0.0,
    override var utcOffset: Long = TimeZone.getDefault().getOffset(timestamp).toLong(),
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    @Embedded
    override var interfaceIDs_backing: InterfaceIDs? = null,
) : TraceableDBEntry, DBEntryWithTime