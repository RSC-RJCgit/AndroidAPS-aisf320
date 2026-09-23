package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Settings a strong or mild boost raises.
 * A strong boost sets the IOB threshold to 71 and, unless [caution] is set, the profile percent to 110 for 2 minutes.
 * Both boosts raise the post-meal weight. Neither raises the acceleration weight.
 * The SMB delivery ratio is not included. Nothing puts that ratio back yet.
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
