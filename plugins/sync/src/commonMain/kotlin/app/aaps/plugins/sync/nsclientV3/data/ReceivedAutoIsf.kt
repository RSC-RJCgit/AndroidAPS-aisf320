package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.data.model.AIV
import app.aaps.core.interfaces.aps.RT

private val bgAcceRegex = Regex("""\bbg_acce:\s*(-?[0-9.]+)""")
private val deltaRegex = Regex("""\bDelta:\s*(-?[0-9.]+)""")
private val sDeltaRegex = Regex("""\bSDelta:\s*(-?[0-9.]+)""")
private val lDeltaRegex = Regex("""\bLDelta:\s*(-?[0-9.]+)""")

/**
 * One history row from a loop result the live phone already uploaded.
 * Returns null when that result has no AutoISF fields and no delta text.
 * [displayDeltaToMgdl] converts Delta, SDelta and LDelta. bg_acce stays in mg/dL.
 */
internal fun receivedAutoIsfRow(
    timestamp: Long,
    rt: RT,
    displayDeltaToMgdl: (Double) -> Double,
): AIV? {
    val reason = rt.reason.toString()
    val hasFactors = rt.autoIsfAcce != null || rt.autoIsfBg != null || rt.autoIsfPp != null ||
        rt.autoIsfDura != null || rt.autoIsfFinal != null || rt.autoIsfUkfRawBgl != null
    val delta = deltaRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull()
    val shortDelta = sDeltaRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull()
    val longDelta = lDeltaRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull()
    val bgAcce = bgAcceRegex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull()
    if (!hasFactors && delta == null && shortDelta == null && longDelta == null && bgAcce == null) return null
    return AIV(
        timestamp = timestamp,
        acceIsf = rt.autoIsfAcce ?: 1.0,
        bgIsf = rt.autoIsfBg ?: 1.0,
        ppIsf = rt.autoIsfPp ?: 1.0,
        duraIsf = rt.autoIsfDura ?: 1.0,
        finalIsf = rt.autoIsfFinal ?: 1.0,
        glucose = rt.bg ?: 0.0,
        delta = delta?.let(displayDeltaToMgdl) ?: 0.0,
        shortAvgDelta = shortDelta?.let(displayDeltaToMgdl) ?: 0.0,
        longAvgDelta = longDelta?.let(displayDeltaToMgdl) ?: 0.0,
        bgAcceleration = bgAcce ?: 0.0,
        iob = rt.IOB ?: 0.0,
        smbDelivered = rt.units ?: 0.0,
        ukfRawBgl = rt.autoIsfUkfRawBgl ?: 0.0,
    )
}
