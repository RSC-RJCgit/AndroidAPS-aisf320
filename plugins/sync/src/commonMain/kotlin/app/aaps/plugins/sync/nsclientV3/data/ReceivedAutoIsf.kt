package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.data.model.AIV
import app.aaps.core.interfaces.aps.RT

private val bgAcceRegex = Regex("""\bbg_acce:\s*(-?[0-9.]+)""")
private val deltaRegex = Regex("""\bDelta:\s*(-?[0-9.]+)""")
private val sDeltaRegex = Regex("""\bSDelta:\s*(-?[0-9.]+)""")
private val lDeltaRegex = Regex("""\bLDelta:\s*(-?[0-9.]+)""")
private val smbDeliveryRatioRegex = Regex("""SMB delivery ratio:\s*([0-9.]+)""")
private val iobThEffectiveRegex = Regex("""iobThEffectiveU:\s*([0-9.]+)""")
private val acceIsfWeightRegex = Regex("""AcceIsfWeight:\s*([0-9.]+)""")
private val ppIsfWeightRegex = Regex("""ppIsfWeight:\s*([0-9.]+)""")
private val fslCalSlopeRegex = Regex("""FslCalSlope:\s*([0-9.]+)""")
private val uamCarbImpactRegex = Regex("""uamCarbImpact:\s*([0-9.]+)""")
private val mealCobRegex = Regex("""mealCOB:\s*([0-9.]+)""")
private val basalRateRegex = Regex("""basalRate:\s*([0-9.]+)""")
private val targetMgdlRegex = Regex("""targetMgdl:\s*([0-9.]+)""")

/**
 * One history row from a loop result the live phone already uploaded.
 * Returns null when that result has no AutoISF fields and no delta text.
 * [displayDeltaToMgdl] converts Delta, SDelta and LDelta. bg_acce and targetMgdl stay in mg/dL.
 *
 * The 3426 loop phone (Live) writes no targetMgdl, mealCOB, basalRate or uamCarbImpact text lines, so those come from
 * the result's own fields when the line is missing (2026-10-21, per explicit request: they were always empty).
 * [enactedRate] is the temp basal the loop phone set, from the enacted result, as the 3426 client reads it.
 */
internal fun receivedAutoIsfRow(
    timestamp: Long,
    rt: RT,
    enactedRate: Double? = null,
    displayDeltaToMgdl: (Double) -> Double,
): AIV? {
    val reason = rt.reason.toString()
    val hasFactors = rt.autoIsfAcce != null || rt.autoIsfBg != null || rt.autoIsfPp != null ||
        rt.autoIsfDura != null || rt.autoIsfFinal != null || rt.autoIsfUkfRawBgl != null
    val delta = firstNumber(deltaRegex, reason)
    val shortDelta = firstNumber(sDeltaRegex, reason)
    val longDelta = firstNumber(lDeltaRegex, reason)
    val bgAcce = firstNumber(bgAcceRegex, reason) ?: rt.autoIsfBgAcceleration
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
        iobThEffective = firstNumber(iobThEffectiveRegex, reason) ?: rt.autoIsfIobThEffective ?: 0.0,
        targetMgdl = firstNumber(targetMgdlRegex, reason) ?: rt.targetBG ?: 0.0,
        uamCarbImpact = firstNumber(uamCarbImpactRegex, reason) ?: rt.autoIsfUamCarbImpact ?: 0.0,
        smbDeliveryRatio = firstNumber(smbDeliveryRatioRegex, reason) ?: rt.autoIsfSmbDeliveryRatio ?: 0.0,
        acceIsfWeight = firstNumber(acceIsfWeightRegex, reason) ?: 0.0,
        ppIsfWeight = firstNumber(ppIsfWeightRegex, reason) ?: 0.0,
        fslCalSlope = firstNumber(fslCalSlopeRegex, reason) ?: 0.0,
        cob = firstNumber(mealCobRegex, reason) ?: rt.COB ?: 0.0,
        basal = firstNumber(basalRateRegex, reason) ?: enactedRate ?: rt.rate ?: 0.0,
    )
}

private fun firstNumber(regex: Regex, text: String): Double? =
    regex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
