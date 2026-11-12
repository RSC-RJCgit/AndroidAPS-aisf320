package app.aaps.core.objects.wizard

import kotlin.math.roundToInt

/**
 * Fat and protein that replace extended carbs.
 *
 * The grams are later hourly doses. They are not added to the bolus taken now.
 * Extended carbs are turned off.
 */
data class MealExtra(
    val fatGrams: Int,
    val proteinGrams: Int,
    val percentage: Int,
    val extendedCarbs: Int,
    val useSavedMaxBolus: Boolean,
    /** Null keeps the meal type's share of carbs in the bolus now. */
    val immediateCarbPercent: Int? = null,
)

const val FAT_PER_CARB = 0.35
const val PROTEIN_PER_CARB = 0.4

fun mealExtra(
    fpuInstead: Boolean,
    unreliableSmb: Boolean,
    carbs: Int,
    typedFat: Int,
    typedProtein: Int,
    percentage: Int,
    extendedCarbPercent: Int,
    smallMeal: Boolean = false,
): MealExtra {
    val safeCarbs = carbs.coerceAtLeast(0)
    val scheduled = safeCarbs * extendedCarbPercent.coerceAtLeast(0) / 100
    if (!fpuInstead && !unreliableSmb) {
        return MealExtra(typedFat, typedProtein, percentage, scheduled, useSavedMaxBolus = false)
    }
    // An average mixed meal carries about 0.35 g fat and 0.4 g protein per g of carbs. Unreliable SMB, a bad sensor or
    // a high protein meal doubles that. A small meal halves it. Both together give the average again.
    // 2026-10-22: the meal type's extended carbs share scales it too (cake x1.2, pizza x1.35, bread x1). The slow part
    // of a cake or pizza meal then comes back as bigger fat and protein doses, so it no longer gets less insulin than bread.
    val mealTypeScale = 1.0 + extendedCarbPercent.coerceAtLeast(0) / 100.0
    val scale = (if (unreliableSmb) 2.0 else 1.0) * (if (smallMeal) 0.5 else 1.0) * mealTypeScale
    val fatTimes = FAT_PER_CARB * scale
    val proteinTimes = PROTEIN_PER_CARB * scale
    return MealExtra(
        fatGrams = (safeCarbs * fatTimes).roundToInt().coerceIn(0, 250),
        proteinGrams = (safeCarbs * proteinTimes).roundToInt().coerceIn(0, 250),
        percentage = if (unreliableSmb) 90 else percentage,
        extendedCarbs = 0,
        useSavedMaxBolus = unreliableSmb,
        immediateCarbPercent = if (unreliableSmb) 100 else null,
    )
}
