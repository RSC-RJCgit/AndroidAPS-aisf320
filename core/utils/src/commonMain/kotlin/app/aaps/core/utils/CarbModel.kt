package app.aaps.core.utils

import kotlin.math.exp

/**
 * Expected carb appearance for one meal, in the same units the graph scales as a 5 minute bucket.
 * Peak is exactly 90 minutes. Nothing is added before the meal or after 6 hours.
 */
fun carbModelRatePer5Min(carbsGrams: Double, minutesSinceMeal: Double): Double {
    if (carbsGrams <= 0.0 || minutesSinceMeal <= 0.0 || minutesSinceMeal > 360.0) return 0.0
    val k = 1.0 / 90.0
    val bioavailability = 0.9
    return 5.0 * carbsGrams * bioavailability * k * k * minutesSinceMeal * exp(-k * minutesSinceMeal)
}
