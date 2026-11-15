package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

/**
 * The AutoISF factors of one loop run, stored in [RT.autoIsf] and so in the APSResult of that run.
 * The overview graphs read them back from there.
 *
 * A factor of 1.0 is neutral. [acceIsf] is the raw factor from the BG acceleration (parabola fit) before
 * the AutoISF limits, so it can be far from 1.0. [finalIsf] is the factor that was applied.
 * [iobThEffective] is the effective IOB threshold in U.
 */
@Serializable
data class AutoIsfFactors(
    var acceIsf: Double? = null,
    var bgIsf: Double? = null,
    var ppIsf: Double? = null,
    var duraIsf: Double? = null,
    var finalIsf: Double? = null,
    var iobThEffective: Double? = null,
    // AIV table (2026-11-15, ported from the AaAPS v4 fork): the rest of one AIV row, so a client builds the same row
    // from the loop result it receives. Deltas, acceleration and target in mg/dL.
    var delta: Double? = null,
    var shortAvgDelta: Double? = null,
    var longAvgDelta: Double? = null,
    var bgAcceleration: Double? = null,
    var smbDeliveryRatio: Double? = null,
    var targetMgdl: Double? = null,
    var steps5: Int? = null,
    /** Only for the clients' step rows; the AIV table has no 10 min column */
    var steps10: Int? = null,
    var steps15: Int? = null,
    var steps30: Int? = null,
    var steps60: Int? = null
) {

    /**
     * Copy with every value that is not finite replaced by null. The device status JSON throws on NaN and
     * Infinity, so a broken factor must never reach [RT].
     */
    fun finiteOnly(): AutoIsfFactors = AutoIsfFactors(
        acceIsf = acceIsf.finiteOrNull(),
        bgIsf = bgIsf.finiteOrNull(),
        ppIsf = ppIsf.finiteOrNull(),
        duraIsf = duraIsf.finiteOrNull(),
        finalIsf = finalIsf.finiteOrNull(),
        iobThEffective = iobThEffective.finiteOrNull(),
        delta = delta.finiteOrNull(),
        shortAvgDelta = shortAvgDelta.finiteOrNull(),
        longAvgDelta = longAvgDelta.finiteOrNull(),
        bgAcceleration = bgAcceleration.finiteOrNull(),
        smbDeliveryRatio = smbDeliveryRatio.finiteOrNull(),
        targetMgdl = targetMgdl.finiteOrNull(),
        steps5 = steps5,
        steps10 = steps10,
        steps15 = steps15,
        steps30 = steps30,
        steps60 = steps60
    )

    private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }
}

/**
 * The AIV table row of one loop result (2026-11-15, ported from the AaAPS v4 fork). The master builds it from its own
 * result, a client from the result it received, so both tables hold the same rows. Null without AutoISF factors.
 */
fun RT.autoIsfRow(timestamp: Long): app.aaps.core.data.model.AIV? {
    val f = autoIsf ?: return null
    return app.aaps.core.data.model.AIV(
        timestamp = timestamp,
        acceIsf = f.acceIsf ?: 1.0,
        bgIsf = f.bgIsf ?: 1.0,
        ppIsf = f.ppIsf ?: 1.0,
        duraIsf = f.duraIsf ?: 1.0,
        finalIsf = f.finalIsf ?: 1.0,
        glucose = bg ?: 0.0,
        delta = f.delta ?: 0.0,
        shortAvgDelta = f.shortAvgDelta ?: 0.0,
        longAvgDelta = f.longAvgDelta ?: 0.0,
        bgAcceleration = f.bgAcceleration ?: 0.0,
        iob = IOB ?: 0.0,
        smbDelivered = units ?: 0.0,
        iobThEffective = f.iobThEffective ?: 0.0,
        targetMgdl = f.targetMgdl ?: 0.0,
        smbDeliveryRatio = f.smbDeliveryRatio ?: 0.0,
        cob = COB ?: 0.0,
        basal = rate ?: 0.0,
        steps5 = f.steps5 ?: 0,
        steps15 = f.steps15 ?: 0,
        steps30 = f.steps30 ?: 0,
        steps60 = f.steps60 ?: 0
    )
}
