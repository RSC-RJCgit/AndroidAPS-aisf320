package app.aaps.core.data.model

/**
 * One AutoISF loop result. The four factors and the final factor are what the history table
 * and the graph colours read. 1.0 means that factor did not move the ISF.
 */
data class AIV(
    val timestamp: Long,
    val acceIsf: Double,
    val bgIsf: Double,
    val ppIsf: Double,
    val duraIsf: Double,
    val finalIsf: Double,
    val glucose: Double,
    val delta: Double,
    val shortAvgDelta: Double,
    val longAvgDelta: Double,
    val bgAcceleration: Double,
    val iob: Double,
    val smbDelivered: Double,
    /** UKF-smoothed raw glucose, mg/dL. 0 means this row has none. */
    val ukfRawBgl: Double = 0.0,
    /**
     * Effective IOB threshold in units. When the percent is 100 this is max IOB.
     * 0 means this row was stored before the line existed.
     */
    val iobThEffective: Double = 0.0,
)
