package app.aaps.plugins.aps.openAPSAutoISF

internal object RunMark {
    const val SUB75 = "Sub75HeavyDelivery"
    const val UAM_BST = "UamBst"
    const val BOLUS_GIVEN = "BolusGiven"
    const val BOLUS_GIVEN_MILD = "BolusGivenMild"
    const val BOLUS_GIVEN_BG3 = "BolusGivenBg3"
    const val BOLUS_GIVEN_MILD_FAILSAFE = "BolusGivenMildFailsafe"
    const val NIGHT_FR_SKIP = "NightFrSkip"
    const val PP_WEIGHT_REVERT = "PpWeightRevertUnder8_5"
    const val IOB_PROFILE_REVERT = "IobProfileRevert"
    const val USUAL2 = "Usual2forTH"
    const val ALARM_HYPO_1 = "AlarmHypo1"
    const val ALARM_HYPO_2 = "AlarmHypo2"
    const val NOT50_RECENTLY = "Not50Recently"
    const val IOB_TH_DAYTIME_FLOOR = "iobTHDaytimeFloor"
    const val EXTRA50 = "Extra50"
}

/**
 * Short memory of the last time a named action ran.
 * The times are forgotten when the app process stops, same as 3.2.1.
 */
internal class RunMarks {
    private val at = mutableMapOf<String, Long>()

    fun mark(key: String, now: Long) {
        at[key] = now
    }

    fun clear(key: String) {
        at.remove(key)
    }

    fun ready(key: String, minutes: Int, now: Long): Boolean =
        (at[key] ?: 0L) <= now - minutes * 60_000L

    fun recent(key: String, minutes: Int, now: Long): Boolean = !ready(key, minutes, now)

    fun minutesAgo(key: String, now: Long): Int? {
        val marked = at[key] ?: return null
        if (marked <= 0L) return null
        return ((now - marked) / 60_000L).toInt().coerceAtLeast(0)
    }
}

/**
 * Arms a 10 minute pause after more than 1.5 U of SMB while glucose is under 7.5 mmol/L.
 * Clears that pause when glucose is back over 7.5 and still rising.
 * Returns "arm", "clear", or an empty string.
 */
internal fun updateSub75Mark(marks: RunMarks, now: Long, bg: Double, delta: Double, smbSum10: Double): String {
    if (bg < 135.1 && smbSum10 > 1.5 && marks.ready(RunMark.SUB75, 10, now)) {
        marks.mark(RunMark.SUB75, now)
        return "arm"
    }
    if (marks.recent(RunMark.SUB75, 10, now) && bg > 135.1 && delta > 5.4) {
        marks.clear(RunMark.SUB75)
        return "clear"
    }
    return ""
}
