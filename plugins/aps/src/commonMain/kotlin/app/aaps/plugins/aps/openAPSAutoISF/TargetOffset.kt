package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.floor
import kotlin.math.min

internal data class TargetOffset(
    val varOffset: Double,
    val targetBgOrig: Double,
    val targetBgOffset: Double,
    val offsetSoZeroSmb: Boolean,
)

// Same hour table UKF uses when a temp target is already set. With no temp target, the profile minimum is the base.
internal fun targetBgOrigMgdl(tempTargetSet: Boolean, minBg: Double, hour: Int): Double = when {
    !tempTargetSet -> minBg
    hour >= 22 -> 5.2 * 18.0
    hour in 20 until 22 -> 5.2 * 18.0
    hour in 16 until 20 -> 5.0 * 18.0
    hour in 10 until 16 -> 5.0 * 18.0
    hour in 8 until 10 -> 5.0 * 18.0
    hour in 6 until 8 -> 5.0 * 18.0
    hour in 5 until 6 -> 5.0 * 18.0
    else -> 5.4 * 18.0
}

// Signed mmol added to the SMB offset. Each clock window has its own stored value.
internal fun todOffsetMmol(
    hour: Int,
    offset0002: Double,
    offset0204: Double,
    offset0406: Double,
    offset0609: Double,
    offset0912: Double,
    offset1218: Double,
    offset1822: Double,
    offset2200: Double,
): Double = when (hour) {
    in 0 until 2 -> offset0002
    in 2 until 4 -> offset0204
    in 4 until 6 -> offset0406
    in 6 until 9 -> offset0609
    in 9 until 12 -> offset0912
    in 12 until 18 -> offset1218
    in 18 until 22 -> offset1822
    else -> offset2200
}

// Base is smb_delivery_ratio_max * 18 mg/dL. A time-of-day nudge is added in mg/dL. MildOffsetZero replaces the sum with 0. Cap is 36.
internal fun varOffsetMgdl(smbDeliveryRatioMax: Double, todOffsetMgdl: Double, mildOffsetZero: Boolean): Double {
    var offset = smbDeliveryRatioMax * 18.0
    if (todOffsetMgdl != 0.0) offset += todOffsetMgdl
    if (mildOffsetZero) offset = 0.0
    return min(36.0, offset)
}

internal fun targetOffset(
    smbDeliveryRatioMax: Double,
    todOffsetMgdl: Double,
    mildOffsetZero: Boolean,
    tempTargetSet: Boolean,
    minBg: Double,
    hour: Int,
    bg: Double,
    cob: Double,
    carbAgeMin: Double,
): TargetOffset {
    val varOffset = varOffsetMgdl(smbDeliveryRatioMax, todOffsetMgdl, mildOffsetZero)
    val targetBgOrig = targetBgOrigMgdl(tempTargetSet, minBg, hour)
    val targetBgOffset = min(targetBgOrig + varOffset, 126.0)
    val lowCob = cob == 0.0 || (cob < 5.0 && carbAgeMin > 120.0)
    return TargetOffset(
        varOffset = varOffset,
        targetBgOrig = targetBgOrig,
        targetBgOffset = targetBgOffset,
        offsetSoZeroSmb = bg < targetBgOffset && lowCob,
    )
}

// Zero the SMB under the offset when carbs are old or absent.
// When carbs bypass that rule, the lower half of the band is still zero and the upper half is halved.
// A mild, bg3, or Tier 3 fire this cycle skips that half band.
internal fun applyTargetOffsetToSmb(
    microBolus: Double,
    bg: Double,
    offset: TargetOffset,
    roundSmbTo: Double,
    skipCarbBand: Boolean,
    mildOffsetZero: Boolean,
): Pair<Double, String> {
    var smb = microBolus
    val notes = StringBuilder()
    if (mildOffsetZero) notes.append(" MildOffsetZero: varOffset forced to 0 ")
    if (!offset.offsetSoZeroSmb && offset.varOffset > 0.0 && bg < offset.targetBgOffset && smb > 0.0 && !skipCarbBand) {
        val before = smb
        val zeroTop = min(offset.targetBgOrig + 0.5 * offset.varOffset, offset.targetBgOffset)
        if (bg < zeroTop) {
            smb = 0.0
            notes.append(" offsetBand: SMB ${before} -> 0 ")
        } else {
            smb *= 0.5
            notes.append(" offsetBand: SMB ${before} x0.5 ")
        }
    }
    if (roundSmbTo > 0.0) smb = floor(smb * roundSmbTo) / roundSmbTo
    if (offset.offsetSoZeroSmb) {
        smb = 0.0
        notes.append(" offsetSoZeroSMB Microbolusing := 0")
    } else if (smb <= 0.0) {
        smb = 0.0
    }
    return smb to notes.toString()
}
