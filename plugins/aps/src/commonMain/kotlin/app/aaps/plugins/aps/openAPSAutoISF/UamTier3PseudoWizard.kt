package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.BCR
import kotlin.math.round

/** Fixed carb label on the Tier 3 calc row. It is not a meal and it does not change the dose. */
internal const val UAM_TIER3_PLACEHOLDER_CARBS_G = 10.0

/** Same 1.0 and 1.5 ratios the wizard uses for 10 g. Text only. */
internal const val UAM_TIER3_PLACEHOLDER_FAT_G = 10
internal const val UAM_TIER3_PLACEHOLDER_PROTEIN_G = 15

/**
 * A calc row for a Tier 3 SMB that is already requested.
 *
 * Returns null below 1.0 U. The row does not insert a bolus or carbs.
 * 10 g, 10 g fat and 15 g protein are a fixed note. They are not used in the insulin sum.
 */
internal fun uamTier3PseudoWizardEntry(
    timestamp: Long,
    smbDelivered: Double,
    glucoseMgdl: Double,
    targetLowMgdl: Double,
    targetHighMgdl: Double,
    isfMgdl: Double,
    ic: Double,
    profileName: String,
): BCR? {
    if (smbDelivered < 1.0) return null
    val carbs = UAM_TIER3_PLACEHOLDER_CARBS_G
    val fat = UAM_TIER3_PLACEHOLDER_FAT_G
    val protein = UAM_TIER3_PLACEHOLDER_PROTEIN_G
    return BCR(
        timestamp = timestamp,
        targetBGLow = targetLowMgdl,
        targetBGHigh = targetHighMgdl,
        isf = isfMgdl,
        ic = ic,
        bolusIOB = 0.0,
        wasBolusIOBUsed = false,
        basalIOB = 0.0,
        wasBasalIOBUsed = false,
        glucoseValue = glucoseMgdl,
        wasGlucoseUsed = glucoseMgdl > 0.0,
        glucoseDifference = 0.0,
        glucoseInsulin = 0.0,
        glucoseTrend = 0.0,
        wasTrendUsed = false,
        trendInsulin = 0.0,
        cob = 0.0,
        wasCOBUsed = false,
        cobInsulin = 0.0,
        carbs = carbs,
        wereCarbsUsed = false,
        carbsInsulin = 0.0,
        otherCorrection = 0.0,
        wasSuperbolusUsed = false,
        superbolusInsulin = 0.0,
        wasTempTargetUsed = false,
        totalInsulin = smbDelivered,
        percentageCorrection = 100,
        profileName = profileName,
        note = "UAM Tier 3 pseudo-wizard: placeholder ${carbs.toInt()}g carbs, " +
            "FPU fat=${fat}g protein=${protein}g. Not entered. Loop SMB ${twoDecimals(smbDelivered)}U."
    )
}

private fun twoDecimals(value: Double): String {
    val hundredths = round(value * 100.0).toInt()
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toString().padStart(2, '0')
    return "$whole.$fraction"
}
