package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.data.model.AIV
import app.aaps.core.interfaces.aps.RT

/** The live 3.4-based phone writes the effective IOB threshold only into its reason text. */
private val iobThEffectiveRegex = Regex("""iobThEffectiveU:\s*([0-9.]+)""")

/**
 * One AutoISF row from a loop result the master uploaded, for the graph of a client (taken from the v4 fork, 2026-10-20).
 *
 * Returns null when the result carries no AutoISF factors, for example a master that does not run AutoISF. A missing
 * factor is 1.0, which is "no change". A missing IOB threshold is 0.0, which the graph does not draw.
 */
internal fun receivedAutoIsfRow(timestamp: Long, rt: RT): AIV? {
    val hasFactors = rt.autoIsfAcce != null || rt.autoIsfBg != null || rt.autoIsfPp != null ||
        rt.autoIsfDura != null || rt.autoIsfFinal != null
    if (!hasFactors) return null
    return AIV(
        timestamp = timestamp,
        acceIsf = rt.autoIsfAcce ?: 1.0,
        bgIsf = rt.autoIsfBg ?: 1.0,
        ppIsf = rt.autoIsfPp ?: 1.0,
        driftIsf = 1.0,
        duraIsf = rt.autoIsfDura ?: 1.0,
        finalIsf = rt.autoIsfFinal ?: 1.0,
        iobThEffective = rt.autoIsfIobTh
            ?: iobThEffectiveRegex.find(rt.reason.toString())?.groupValues?.get(1)?.toDoubleOrNull()
            ?: 0.0
    )
}
