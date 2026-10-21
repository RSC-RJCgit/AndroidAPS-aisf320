package app.aaps.core.objects.wizard

import kotlin.math.roundToInt

/**
 * Fat and protein guessed from carbs, and the wiz% for "Unreliable SMBs / bad sensor / high protein".
 * Shared by the Bolus Wizard dialog and QuickWizard buttons, so both give the same dose.
 *
 * An average mixed meal is about 0.35 g fat and 0.4 g protein per g of carbs (2026-10-05). Unreliable SMBs
 * doubles it, Small meal halves it, both together give the average again.
 */
object MealFpuFromCarbs {

    const val FAT_PER_CARB = 0.35
    const val PROTEIN_PER_CARB = 0.4

    /** Wiz% used for one calculation while "Unreliable SMBs" is ticked. */
    const val UNRELIABLE_WIZ_PERCENT = 90

    /** Fat and protein in grams (0-200 each), as (fat, protein). */
    fun fatProtein(carbs: Double, unreliable: Boolean, smallMeal: Boolean): Pair<Int, Int> {
        val scale = (if (unreliable) 2.0 else 1.0) * (if (smallMeal) 0.5 else 1.0)
        val fat = (carbs * FAT_PER_CARB * scale).roundToInt().coerceIn(0, 200)
        val protein = (carbs * PROTEIN_PER_CARB * scale).roundToInt().coerceIn(0, 200)
        return fat to protein
    }
}
