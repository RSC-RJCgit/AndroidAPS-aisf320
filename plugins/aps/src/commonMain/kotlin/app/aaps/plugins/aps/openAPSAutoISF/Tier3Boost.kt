package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class Tier3Result(
    val microBolus: Double,
    val enhanced: Boolean,
    val finalIobAllowance: Double?,
    val fastCarbObserved: Boolean,
    val reason: String,
)

// Raises the ordinary SMB when a mild or bg3 rise fired this cycle and the size checks pass.
// A recent UamBst, or a live delivery ratio above 0.50, skips the raise unless the unrestricted switch is on.
// The mark is written later, only if this raised amount is still delivered.
internal fun tier3BoostMicroBolus(
    enabled: Boolean,
    hour: Int,
    daytimeBypass: Boolean,
    unrestricted: Boolean,
    mildThisCycle: Boolean,
    bg3ThisCycle: Boolean,
    uamBoostRecent: Boolean,
    smbDeliveryRatio: Double,
    microBolus: Double,
    insulinReq: Double,
    basal: Double,
    maxBolusSetting: Double,
    maxIob: Double,
    maxIobPercent: Double,
    scaleSetting: Double,
    profilePercent: Int,
    bg: Double,
    targetBg: Double,
    iob: Double,
    cob: Double,
    delta: Double,
    longAvgDelta: Double,
    bgAcceleration: Double,
    recentLowBg: Double,
    roundSmbTo: Double,
): Tier3Result {
    val unchanged = Tier3Result(microBolus, false, null, false, "")
    if (!enabled) return unchanged
    val timeAllowed = hour in 9 until 21 || daytimeBypass
    if (!timeAllowed) return Tier3Result(microBolus, false, null, false, "Tier 3 blocked outside 09:00-21:00; ")
    if (!mildThisCycle && !bg3ThisCycle) return unchanged

    val boostMax = if (unrestricted) maxBolusSetting else min(maxBolusSetting, maxIob / 9.5)
    val boostMaxIob = maxIob * maxIobPercent / 100.0
    val allowance = (boostMaxIob - iob).coerceAtLeast(0.0)
    val scale = scaleSetting * (profilePercent / 100.0)
    if (scale >= 3.0 || bg <= 80.0) return unchanged
    if (!unrestricted && (iob >= boostMaxIob || allowance <= 0.0)) return unchanged
    if (!unrestricted && uamBoostRecent) return Tier3Result(microBolus, false, null, false, "T3skip<20min; ")
    if (!unrestricted && smbDeliveryRatio > 0.50) return Tier3Result(microBolus, false, null, false, "T3skip SMBdel>$smbDeliveryRatio; ")

    val ratio = smbDeliveryRatio.coerceAtLeast(0.05)
    val insulinDivisor = 1.0 / ratio
    val boostInsulinReq = min(scale * basal, boostMax)
    val includeMultipliedUsual = mildThisCycle && !bg3ThisCycle
    val basalScaleCandidate = if (includeMultipliedUsual) min(1.2 * boostInsulinReq, boostMax) else boostInsulinReq
    val baselineRatioCandidate = min(insulinReq / insulinDivisor, boostMax)
    val boostedUsual = min(microBolus * scale, boostMax)
    val uncapped = if (includeMultipliedUsual) {
        max(basalScaleCandidate, max(boostedUsual, baselineRatioCandidate))
    } else {
        max(basalScaleCandidate, baselineRatioCandidate)
    }
    val capped = if (unrestricted) uncapped else min(uncapped, allowance)
    val rounded = if (roundSmbTo > 0.0) floor(capped * roundSmbTo) / roundSmbTo else capped
    val notes = StringBuilder()
    var smb = microBolus
    var enhanced = false
    var finalAllowance: Double? = null
    if (rounded > microBolus) {
        smb = rounded
        enhanced = true
        finalAllowance = if (unrestricted) null else allowance
        notes.append("UAM Boost candidate $microBolus -> ${smb}U; ")
    }

    var fastCarb = false
    val reversalScore = if (longAvgDelta < 0.0 && delta > 0.0) delta * abs(longAvgDelta) else 0.0
    val lowTriggered = recentLowBg < 100.0
    val reversalTriggered = reversalScore > 30.0
    if ((lowTriggered || reversalTriggered) && cob == 0.0 && bgAcceleration > 0.90 * 18.0 && bg < 170.0) {
        val genuineSpike = delta > 15.0 && bg > targetBg + 20.0
        if (!genuineSpike) {
            fastCarb = true
            if (enhanced && smb > 0.0) {
                val scaleDown = if (bg < 120.0) 0.3 else 0.3 + 0.7 * (bg - 120.0) / 50.0
                val before = smb
                smb = if (roundSmbTo > 0.0) floor(smb * scaleDown * roundSmbTo) / roundSmbTo else smb * scaleDown
                notes.append("Tier3FastCarb $before -> $smb; ")
            }
        }
    }
    return Tier3Result(smb, enhanced, finalAllowance, fastCarb, notes.toString())
}
