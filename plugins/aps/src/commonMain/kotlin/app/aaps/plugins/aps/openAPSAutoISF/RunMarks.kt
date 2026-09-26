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
    const val OVERNIGHT_DURA_RESCUE = "OvernightDuraRescue"
    const val HIGH_NIGHT = "HighNight00AM"
    const val BASAL_UP = "BasalUp"
    const val PERSISTENT_RISE = "PersistentRiseRelease"
    const val HIGH_EVE_NIGHT_BRAKE = "HighEveNightBrake"
    const val HIGH_DAYTIME_BRAKE = "HighDaytimeBrake"
    const val HI_BRK_TWILIGHT = "HiBrkTwilight"
    const val HIGH_6_PP = "High6PP"
    const val HIGH_6_PP_OFF = "High6PPoff"
    const val HIGH_OLD_POD = "HighOldPod"
    const val POD_CHANGE_HIGH_PP130 = "PodChangeHighPP130"
    const val HIGH_PP130_OFF = "HighPP130Off"
    const val RECENT_POD = "RecentPod"
    const val RECENT_POD_OFF = "RecentPodOff"
    const val OLD_POD2 = "OldPod2"
    const val POD1 = "Pod1"
    const val POD2 = "Pod2"
    const val SHOWER12 = "Shower12"
    const val BOLUS2 = "Bolus2"
    const val CONNECT_POD = "ConnectPod"
    const val GENTLE_HYPO_RISK = "GentleHypoRisk"
    const val SKITTLES_HYPO_RISK = "SkittlesHypoRisk"
    const val SET50_RECENT = "50SetRecent"
    const val FIFTY_PC_MAKES_57 = "50pcMakes5.7"
    const val PREPARE_SET50 = "PrepareSet50"
    const val PP50_OFF = "PP50Off"
    const val MORE_MJ = "MoreMJ"
    const val MORNING_ROLE_SWAP = "MorningRoleSwap"
    const val MJ_RECENT = "MJrecentCurrProfAcce"
    const val MJ2_OLD = "MJ2old"
    const val MJ3_OLD = "MJ3old"
    const val MJ_OFF = "MJoff"
    const val MJ4 = "MJ4"
    const val MJ5 = "MJ5"
    const val EARLY_DAWN = "EarlyDawnSlowRise"
    const val EVENING_IOB_CEILING = "EveningIobCeiling"
    const val EVENING_TH = "EveningTH"
    const val NIGHT_IOB_CEILING = "NightIobCeiling"
    const val NIGHT_ACCE = "NightAcce"
    const val SEMI_TWILIGHT = "SemiTwilightAcce"
    const val TWILIGHT_TH15 = "TwilightTH15Acce"
    const val EXERCISE_LIMIT = "ExerciseLimitAcce"
    const val ACCE_UP = "AcceUp0.5"
    const val STUCK_HIGH = "StuckHighRescue"
    const val STUCK_RISING = "StuckRisingSlowly"
    const val POOR_RESPONSE_1 = "PoorResponseRescueStage1"
    const val POOR_RESPONSE_2 = "PoorResponseRescueStage2"
    const val UNEXPLAINED_HIGH = "UnexplainedHighTierC"
    const val OFF_HIGH = "OffHighProf"
    const val LOW_BG_TIER_A_SCAN = "LowBgTierAResetScan"
    const val LOW_BG_TIER_A = "LowBgTierAReset"
    const val PROFILE_BATCH_STEP = "ProfileBatchStep"
    const val PROFILE_BATCH_REVERT = "ProfileBatchRevert"
    const val PROFILE_BATCH_REVERT_C = "ProfileBatchRevertC"
    const val PROFILE_ROLE_SANITY = "ProfileRoleSanityCheck"
    const val BATTERY_1 = "Battery1pc"
    const val BATTERY_OVER_1 = "BatteryOver1pc"
    const val CARBS_STOP_TT1 = "CarbsStopTT1"
    const val CARBS_STOP_TT57 = "CarbsStopTT57"
    const val CARBS_TH_OFF = "CarbsTHoff"
    const val TT57_REVERSAL = "TT57Reversal"
    const val ACTIVITY_TT_REVERSAL = "ActivityTTReversal"
    const val T80_OFF = "T80Off3ok"
    const val STEPS_STEROIDS_OFF = "StepsSteroidsOff"
    const val PRESOAK_SENSOR = "PreSoakSensor24hrs"
    const val SENSOR_S1 = "SensorS1hr"
    const val SENSOR_S2 = "SensorS2hr"
    const val SENSOR_AGE_AUTO_OFF = "SensorAgeAutoOff"
    const val SENSOR_AGE_AUTO_ON = "SensorAgeAutoOn"
    const val LIBRE_OVER_12 = "LibreOver12Backfill"
    const val ALARM_HYPO_ROLE_REVERT = "AlarmHypoRoleRevert"
    const val SENSOR_AGE_TOGGLE = "SensorAgeToggleTT"
    const val BOOST_TOGGLE = "BoostToggleTT"
    const val SMB_DELIVERY_DOWN = "SmbDeliveryDownTT"
    const val SMB_DELIVERY_UP = "SmbDeliveryUpTT"
    const val PP_WEIGHT_DOWN = "PpWeightDownTT"
    const val PP_WEIGHT_UP = "PpWeightUpTT"
    const val PP_WEIGHT_HIGH_DOWN = "PpWeightHighDownTT"
    const val PP_WEIGHT_HIGH_UP = "PpWeightHighUpTT"
    const val ACCE_WEIGHT_DOWN = "AcceWeightDownTT"
    const val ACCE_WEIGHT_UP = "AcceWeightUpTT"
    const val ACCE_WEIGHT_HIGH_DOWN = "AcceWeightHighDownTT"
    const val ACCE_WEIGHT_HIGH_UP = "AcceWeightHighUpTT"
    const val HIGH_ISF_DOWN = "HigherIsfRangeWeightDownTT"
    const val HIGH_ISF_UP = "HigherIsfRangeWeightUpTT"
    const val MAX_LOW_DOWN = "AutoIsfMaxLowDownTT"
    const val MAX_LOW_UP = "AutoIsfMaxLowUpTT"
    const val MAX_DOWN = "AutoIsfMaxNormalDownTT"
    const val MAX_UP = "AutoIsfMaxNormalUpTT"
    const val TOD_0002_DOWN = "TodOffset0002DownTT"
    const val TOD_0002_UP = "TodOffset0002UpTT"
    const val TOD_0204_DOWN = "TodOffset0204DownTT"
    const val TOD_0204_UP = "TodOffset0204UpTT"
    const val TOD_0406_DOWN = "TodOffset0406DownTT"
    const val TOD_0406_UP = "TodOffset0406UpTT"
    const val ACTIVITY_PROF_50 = "ActivityProf50"
    const val ACTIVITY_OFF = "ActivityOff"
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
