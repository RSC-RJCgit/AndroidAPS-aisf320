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

fun mealExtra(
    fpuInstead: Boolean,
    unreliableSmb: Boolean,
    carbs: Int,
    typedFat: Int,
    typedProtein: Int,
    percentage: Int,
    extendedCarbPercent: Int,
): MealExtra {
    val safeCarbs = carbs.coerceAtLeast(0)
    val scheduled = safeCarbs * extendedCarbPercent.coerceAtLeast(0) / 100
    if (!fpuInstead && !unreliableSmb) {
        return MealExtra(typedFat, typedProtein, percentage, scheduled, useSavedMaxBolus = false)
    }
    val fatTimes = if (unreliableSmb) 1.5 else 1.0
    val proteinTimes = if (unreliableSmb) 2.0 else 1.5
    return MealExtra(
        fatGrams = (safeCarbs * fatTimes).roundToInt().coerceIn(0, 250),
        proteinGrams = (safeCarbs * proteinTimes).roundToInt().coerceIn(0, 250),
        percentage = if (unreliableSmb) 90 else percentage,
        extendedCarbs = 0,
        useSavedMaxBolus = unreliableSmb,
        immediateCarbPercent = if (unreliableSmb) 100 else null,
    )
}
