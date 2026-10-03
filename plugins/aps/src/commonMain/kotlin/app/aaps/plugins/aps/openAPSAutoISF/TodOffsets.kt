package app.aaps.plugins.aps.openAPSAutoISF

// Which time-of-day offsets may be cleared. A missing prediction does not count as low or high.
internal data class TodOffsetClear(
    val clearNegative: Boolean,
    val clearPositive: Boolean,
)

internal fun todOffsetClearSignals(
    nomjRemains: Boolean,
    hp: Double?,
    shortDeltaMgdl: Double,
    steps60: Int,
): TodOffsetClear {
    val shortMmol = shortDeltaMgdl / 18.0182
    val clearNegative = !nomjRemains || (hp != null && hp < 4.0 && shortMmol < 0.1) || steps60 >= 1000
    val clearPositive = nomjRemains && steps60 <= 200 && hp != null && hp > 6.0 && shortMmol < 0.1
    return TodOffsetClear(clearNegative, clearPositive)
}
