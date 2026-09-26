package app.aaps.plugins.aps.openAPSAutoISF

// Steps over the last hour, IOB between 2 and 4, glucose at or under 8.0 mmol, rising, no carbs.
internal fun stepsSteroidsOffShouldFire(
    ready: Boolean,
    steps60: Int,
    iob: Double,
    bg: Double,
    delta: Double,
    cob: Double,
): Boolean = ready && steps60 >= 1000 && iob in 2.0..4.0 && bg <= 144.1 && delta >= 9.0 && cob == 0.0

// A missing sensor age is 0 hours, so the narrow windows stay closed. A missing pod does not block.
internal fun preSoakBlock(ready: Boolean, livePump: Boolean, sensorHours: Double, podHours: Double?): String? {
    if (!ready || !livePump) return null
    val podOk = podHours == null || podHours <= 80.0
    if (!podOk) return null
    return when {
        sensorHours in 336.0..336.1 -> "14.0"
        sensorHours in 348.0..348.1 -> "14.5"
        else -> null
    }
}

internal fun sensorHourHit(ready: Boolean, livePump: Boolean, sensorHours: Double, podHours: Double?, low: Double, high: Double): Boolean {
    if (!ready || !livePump) return false
    val podOk = podHours == null || podHours <= 80.0
    return podOk && sensorHours >= low && sensorHours <= high
}

// Turns the sensor-age code off. A missing pod or sensor age does not count as too old.
internal fun sensorAgeShouldTurnOff(codeEnabled: Boolean, podHours: Double?, sensorDays: Double?): Boolean =
    codeEnabled && ((podHours != null && podHours > 80.0) || (sensorDays != null && sensorDays > 15.0))
