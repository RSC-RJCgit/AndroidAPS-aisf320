package app.aaps.core.data.model

import java.util.TimeZone

/** AutoISF key values for plotting in subgraph. */
data class AIV(
    override var id: Long = 0,
    /** Milliseconds since the epoch. End of the sampling period, i.e. the value is
     *  sampled from timestamp-duration to timestamp. */
    var timestamp: Long,
    var acceIsf: Double,
    var bgIsf: Double,
    var ppIsf: Double,
    var driftIsf: Double,       // place bolder
    var duraIsf: Double,
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
    /** UAM Carb Impact (uci) -- deviation-derived carbs-equivalent, grams per 5min (converted from uci's
     *  native mg/dL/5min BG-impact via csf). See RT.autoIsfUamCarbImpact. */
    var uamCarbImpact: Double = 0.0,
    /** UKF-smoothed Raw BG, mg/dL -- computed once per cycle, reused by both the graph and the AIV
     *  history exporter/dialog's delta columns. See RT.autoIsfUkfRawBgl. */
    var ukfRawBgl: Double = 0.0,
    /** Average minutes between BG/Libre readings in the 5 min before [timestamp], computed on the
     *  device actually running AutoISF (the master) from its own locally-connected sensor feed — NOT
     *  recomputed from a follower's own local (NS-synced) GV table, since NS's own upload/sync cadence
     *  doesn't necessarily reflect the master's true underlying reading interval. */
    var avgReadingIntervalMin: Double = 0.0,
    var utcOffset: Long = TimeZone.getDefault().getOffset(timestamp).toLong(),
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    override var ids: IDs = IDs()
) : HasIDs {

    fun contentEqualsTo(other: AIV): Boolean {
        return this === other || (
            timestamp == other.timestamp &&
                acceIsf == other.acceIsf &&
                bgIsf == other.bgIsf &&
                ppIsf == other.ppIsf &&
                driftIsf == other.driftIsf &&
                driftIsf == other.driftIsf &&
                duraIsf == other.duraIsf &&
                finalIsf == other.finalIsf &&
                iobThEffective == other.iobThEffective &&
                delta == other.delta &&
                shortAvgDelta == other.shortAvgDelta &&
                glucose == other.glucose &&
                insulinReq == other.insulinReq &&
                tbrRate == other.tbrRate &&
                smbDelivered == other.smbDelivered &&
                bgAcceleration == other.bgAcceleration &&
                smbDeliveryRatio == other.smbDeliveryRatio &&
                iob == other.iob &&
                acceIsfWeight == other.acceIsfWeight &&
                fslCalSlope == other.fslCalSlope &&
                uamCarbImpact == other.uamCarbImpact &&
                ukfRawBgl == other.ukfRawBgl &&
                isValid == other.isValid)
    }
}