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
 * [activity] (see [activityFactor]) multiplies the increment.
 */
internal fun boostedDeliveryRatio(mildBase: Double, strong: Boolean, caution: Boolean, activity: Double = 1.0): Double {
    val increment = when {
        strong && caution -> 0.015
        strong -> 0.03
        caution -> 0.075
        else -> 0.15
    }
    return (mildBase + increment * activity).coerceIn(0.1, 1.0)
}

/**
 * Activity factor, added 2026-10-18 per explicit request: 0 when IOB is at or above 40% of max IOB, 0.5 while walking (the
 * same movement test as StuckHighRescue: S5 > 20, S30 > 100 or S60 > 300), 1 otherwise. It scales the Tier 3 UAM boost
 * scale, the UAM Tier3 pseudo-wizard FPU and BMild's ratio boost. 17 Oct Live: about 3 U went in between 17:00 and 17:15
 * while walking, and BG fell from 9.9 to 3.6.
 */
internal fun activityFactor(iob: Double, maxIob: Double, steps5: Int, steps30: Int, steps60: Int): Double = when {
    iob >= 0.40 * maxIob -> 0.0
    steps5 > 20 || steps30 > 100 || steps60 > 300 -> 0.5
    else -> 1.0
}

/** The careportal note for an activity factor below 1, or null at 1. */
internal fun activityFactorNote(factor: Double): String? = when {
    factor >= 1.0 -> null
    factor <= 0.0 -> "ActF0"
    else -> "ActF.5"
}
