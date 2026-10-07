package app.aaps.core.keys

/**
 * The tier letter (A, B or C) of the Standard role, the Low role and the running profile, read the same way everywhere.
 * A letter is null when that name is not on its ladder. The ladders are the A, B, C profile names, in that order.
 */
data class TierReading(
    val standardLetter: String?,
    val lowLetter: String?,
    val runningLetter: String?,
) {
    /** Standard and Low name the same letter, and the running profile is off the ladders or on that letter too. */
    val agrees: Boolean
        get() = standardLetter != null && standardLetter == lowLetter && (runningLetter == null || runningLetter == standardLetter)
}

private const val LETTERS = "ABC"

private fun letterOf(name: String, rungs: List<String>): String? {
    val clean = name.trim()
    if (clean.isEmpty()) return null
    val index = rungs.indexOfFirst { it.isNotBlank() && it.trim() == clean }
    return LETTERS.getOrNull(index)?.toString()
}

fun readTier(
    standardName: String,
    lowName: String,
    runningName: String,
    standardRungs: List<String>,
    lowRungs: List<String>,
): TierReading = TierReading(
    standardLetter = letterOf(standardName, standardRungs),
    lowLetter = letterOf(lowName, lowRungs),
    runningLetter = letterOf(runningName, standardRungs) ?: letterOf(runningName, lowRungs),
)

/** One line for a screen. Shows the letter when all agree, and every reading when they do not. */
fun tierSummary(reading: TierReading, standardName: String, lowName: String, runningName: String): String =
    if (reading.agrees) "Tier ${reading.standardLetter}: Std ${standardName.trim()}, Low ${lowName.trim()}"
    else "Mixed: Std ${reading.standardLetter ?: "off ladder"} (${standardName.trim()}), " +
        "Low ${reading.lowLetter ?: "off ladder"} (${lowName.trim()}), " +
        "running ${runningName.trim().ifEmpty { "none" }} (${reading.runningLetter ?: "off ladder"})"
