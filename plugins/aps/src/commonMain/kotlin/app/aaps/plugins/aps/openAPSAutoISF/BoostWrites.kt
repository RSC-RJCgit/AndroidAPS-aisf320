package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Settings a strong or mild boost raises.
 * A strong boost sets the IOB threshold to 71 and, unless [caution] is set, the profile percent to 110 for 2 minutes.
 * Both boosts raise the post-meal weight. Neither raises the acceleration weight.
 * The SMB delivery ratio is a separate number from [boostedDeliveryRatio].
 * A mild mark also starts a 2-minute 5.0 target when no temp target is already active.
 */
internal data class BoostRaise(
    val iobTh: Int?,
    val profilePercent: Int?,
    val profileMinutes: Int,
    val raisePpWeight: Boolean,
)

internal fun boostRaises(strong: Boolean, caution: Boolean): BoostRaise = if (strong) {
    BoostRaise(
        iobTh = 71,
        profilePercent = if (caution) null else 110,
        profileMinutes = 2,
        raisePpWeight = true,
    )
} else {
    BoostRaise(
        iobTh = null,
        profilePercent = null,
        profileMinutes = 0,
        raisePpWeight = true,
    )
}

/** A target is applied only when it sits above the baseline. A lower target would not be put back. */
internal fun raiseAbove(target: Int?, baseline: Int): Int? = target?.takeIf { it > baseline }

/**
 * SMB delivery ratio for a rise boost, from the mild base.
 * A strong rise adds 0.03, or 0.015 when [caution] is set.
 * A mild rise adds 0.15, or 0.075 when [caution] is set.
 * A mild failsafe passes [caution] false, so it keeps the full 0.15.
 */
internal fun boostedDeliveryRatio(mildBase: Double, strong: Boolean, caution: Boolean): Double {
    val increment = when {
        strong && caution -> 0.015
        strong -> 0.03
        caution -> 0.075
        else -> 0.15
    }
    return (mildBase + increment).coerceIn(0.1, 1.0)
}
