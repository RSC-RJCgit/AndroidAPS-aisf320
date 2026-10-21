package app.aaps.core.objects.wizard

/**
 * Meal type of the Bolus Wizard and of QuickWizard buttons: the share of the carbs given now, and the extended carbs
 * added on top (percent of the entered carbs, delay and duration). Moved here from the wizard screen on 2026-10-22,
 * so a QuickWizard button gives the same split as the wizard.
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

    companion object {

        /** A stored name; anything unknown (or a button saved before meal types) is [BREAD], which changes nothing. */
        fun fromName(name: String?): CarbsType = entries.firstOrNull { it.name == name } ?: BREAD
    }
}
