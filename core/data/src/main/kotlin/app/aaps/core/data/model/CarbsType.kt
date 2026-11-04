package app.aaps.core.data.model

/**
 * What kind of meal the carbs of a bolus calculator entry are (2026-11-05, per explicit request: copied from v4's
 * upstream Bread / Cake / Pizza meal type). The type splits the entered grams into carbs eaten now and extended
 * carbs (eCarbs) that start later and are spread over some hours.
 *
 * Bread is the plain case: all carbs now, no eCarbs. Cake and pizza move part of the carbs to eCarbs, because fat
 * and protein slow the rise. With split FPU doses ticked there are no eCarbs: with auto fat/protein ticked, the slow
 * share comes back as bigger fat and protein doses instead (see MealFpuFromCarbs.fatProtein).
 */
enum class CarbsType(
    val carbsPercent: Int,
    val eCarbsPercent: Int,
    val eCarbsDelayMinutes: Int,
    val eCarbsDurationHours: Int
) {

    BREAD(carbsPercent = 100, eCarbsPercent = 0, eCarbsDelayMinutes = 0, eCarbsDurationHours = 0),
    CAKE(carbsPercent = 90, eCarbsPercent = 20, eCarbsDelayMinutes = 60, eCarbsDurationHours = 2),
    PIZZA(carbsPercent = 80, eCarbsPercent = 35, eCarbsDelayMinutes = 60, eCarbsDurationHours = 3);

    /** The part of [totalCarbs] that is eaten now and goes into the calculation. */
    fun carbsNow(totalCarbs: Int): Int = totalCarbs * carbsPercent / 100

    /** The part of [totalCarbs] that is entered as eCarbs, 0 for bread. */
    fun eCarbs(totalCarbs: Int): Int = totalCarbs * eCarbsPercent / 100

    companion object {

        /** A stored name; anything unknown (or a QuickWizard button saved before meal types) is [BREAD], which changes nothing. */
        fun fromName(name: String?): CarbsType = entries.firstOrNull { it.name == name } ?: BREAD
    }
}
