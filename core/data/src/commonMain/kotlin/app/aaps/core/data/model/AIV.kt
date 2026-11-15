package app.aaps.core.data.model

/**
 * One AutoISF loop run for the AIV table (2026-11-15, ported from the AaAPS v4 fork). Glucose, deltas and target are
 * mg/dL, iob, smbDelivered and iobThEffective units, basal U/h. Steps are the step counts used by that loop.
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
    val iobThEffective: Double = 0.0,
    val targetMgdl: Double = 0.0,
    val smbDeliveryRatio: Double = 0.0,
    val cob: Double = 0.0,
    val basal: Double = 0.0,
    val steps5: Int = 0,
    val steps15: Int = 0,
    val steps30: Int = 0,
    val steps60: Int = 0,
)
