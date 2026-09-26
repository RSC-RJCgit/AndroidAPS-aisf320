package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs
import kotlin.math.round

// A hand-set temp target used as a remote switch. The values are 0.002 mmol apart,
// so the match window is 0.0001 mmol. A wider window would hit the neighbour.
internal enum class RemoteToggleCode {
    SMB_DOWN,
    SMB_UP,
    SENSOR_AGE,
    BOOST,
    PP_DOWN,
    PP_UP,
    PP_HIGH_DOWN,
    PP_HIGH_UP,
    ACCE_DOWN,
    ACCE_UP,
    ACCE_HIGH_DOWN,
    ACCE_HIGH_UP,
    HIGH_ISF_DOWN,
    HIGH_ISF_UP,
    MAX_LOW_DOWN,
    MAX_LOW_UP,
    MAX_DOWN,
    MAX_UP,
    TOD_0002_DOWN,
    TOD_0002_UP,
    TOD_0204_DOWN,
    TOD_0204_UP,
    TOD_0406_DOWN,
    TOD_0406_UP,
    TOD_0609_DOWN,
    TOD_0609_UP,
    TOD_0912_DOWN,
    TOD_0912_UP,
    TOD_1218_DOWN,
    TOD_1218_UP,
    TOD_1822_DOWN,
    TOD_1822_UP,
    TOD_2200_DOWN,
    TOD_2200_UP,
    GRAPH2,
    CLOUD_LOGS,
    MJ_NO,
    MJ3,
    MJ_ACTIVE,
    MJ2,
    PROFILE_STANDARD,
    PROFILE_LOW,
    SENSOR_AGE_CODE,
    LIBRE_UKF1,
    MJ_START,
    MJ_RESTORE,
    STEROID_START,
    STEROID_130,
    STEROID_150,
    STEROID_190,
    STEROID_250,
    STEROID_OFF,
    TIER3_BOOST,
    PROFILE_BATCH_AUTO,
    PROFILE_BATCH_REVERT,
    PROFILE_BATCH_REVERT_C,
    TIER_SET_A,
    TIER_SET_B,
    TIER_SET_C,
    FAST_RISE,
    LOW_REBOUND,
    T3_UNRESTRICTED,
    BOOST_SCALE_DOWN,
    BOOST_SCALE_UP,
    BOOST_MAX_DOWN,
    BOOST_MAX_UP,
    BOOST_IOB_DOWN,
    BOOST_IOB_UP,
    DURA_WEIGHT_DOWN,
    DURA_WEIGHT_UP,
    LIBRE_SLOPE_DOWN,
    LIBRE_SLOPE_UP,
}

internal fun remoteToggleCode(ttMgdl: Double): RemoteToggleCode? = when {
    ttNear(ttMgdl, 5.002, 0.0001) -> RemoteToggleCode.SMB_DOWN
    ttNear(ttMgdl, 5.004, 0.0001) -> RemoteToggleCode.SMB_UP
    ttNear(ttMgdl, 5.006, 0.0001) -> RemoteToggleCode.SENSOR_AGE
    ttNear(ttMgdl, 5.008, 0.0001) -> RemoteToggleCode.BOOST
    ttNear(ttMgdl, 5.012, 0.0001) -> RemoteToggleCode.PP_DOWN
    ttNear(ttMgdl, 5.014, 0.0001) -> RemoteToggleCode.PP_UP
    ttNear(ttMgdl, 5.056, 0.0001) -> RemoteToggleCode.PP_HIGH_DOWN
    ttNear(ttMgdl, 5.058, 0.0001) -> RemoteToggleCode.PP_HIGH_UP
    ttNear(ttMgdl, 5.016, 0.0001) -> RemoteToggleCode.ACCE_DOWN
    ttNear(ttMgdl, 5.018, 0.0001) -> RemoteToggleCode.ACCE_UP
    ttNear(ttMgdl, 5.062, 0.0001) -> RemoteToggleCode.ACCE_HIGH_DOWN
    ttNear(ttMgdl, 5.064, 0.0001) -> RemoteToggleCode.ACCE_HIGH_UP
    ttNear(ttMgdl, 5.068, 0.0001) -> RemoteToggleCode.HIGH_ISF_DOWN
    ttNear(ttMgdl, 5.070, 0.0001) -> RemoteToggleCode.HIGH_ISF_UP
    ttNear(ttMgdl, 5.080, 0.0001) -> RemoteToggleCode.MAX_LOW_DOWN
    ttNear(ttMgdl, 5.082, 0.0001) -> RemoteToggleCode.MAX_LOW_UP
    ttNear(ttMgdl, 5.086, 0.0001) -> RemoteToggleCode.MAX_DOWN
    ttNear(ttMgdl, 5.088, 0.0001) -> RemoteToggleCode.MAX_UP
    ttNear(ttMgdl, 5.092, 0.0001) -> RemoteToggleCode.TOD_0002_DOWN
    ttNear(ttMgdl, 5.094, 0.0001) -> RemoteToggleCode.TOD_0002_UP
    ttNear(ttMgdl, 5.098, 0.0001) -> RemoteToggleCode.TOD_0204_DOWN
    ttNear(ttMgdl, 5.100, 0.0001) -> RemoteToggleCode.TOD_0204_UP
    ttNear(ttMgdl, 5.104, 0.0001) -> RemoteToggleCode.TOD_0406_DOWN
    ttNear(ttMgdl, 5.106, 0.0001) -> RemoteToggleCode.TOD_0406_UP
    ttNear(ttMgdl, 5.110, 0.0001) -> RemoteToggleCode.TOD_0609_DOWN
    ttNear(ttMgdl, 5.112, 0.0001) -> RemoteToggleCode.TOD_0609_UP
    ttNear(ttMgdl, 5.116, 0.0001) -> RemoteToggleCode.TOD_0912_DOWN
    ttNear(ttMgdl, 5.118, 0.0001) -> RemoteToggleCode.TOD_0912_UP
    ttNear(ttMgdl, 5.122, 0.0001) -> RemoteToggleCode.TOD_1218_DOWN
    ttNear(ttMgdl, 5.124, 0.0001) -> RemoteToggleCode.TOD_1218_UP
    ttNear(ttMgdl, 5.128, 0.0001) -> RemoteToggleCode.TOD_1822_DOWN
    ttNear(ttMgdl, 5.130, 0.0001) -> RemoteToggleCode.TOD_1822_UP
    ttNear(ttMgdl, 5.134, 0.0001) -> RemoteToggleCode.TOD_2200_DOWN
    ttNear(ttMgdl, 5.136, 0.0001) -> RemoteToggleCode.TOD_2200_UP
    ttNear(ttMgdl, 5.138, 0.0001) -> RemoteToggleCode.GRAPH2
    ttNear(ttMgdl, 5.140, 0.0001) -> RemoteToggleCode.CLOUD_LOGS
    ttNear(ttMgdl, 5.144, 0.0001) -> RemoteToggleCode.MJ_NO
    ttNear(ttMgdl, 5.146, 0.0001) -> RemoteToggleCode.MJ3
    ttNear(ttMgdl, 5.222, 0.0001) -> RemoteToggleCode.MJ_ACTIVE
    ttNear(ttMgdl, 5.224, 0.0001) -> RemoteToggleCode.MJ2
    ttNear(ttMgdl, 5.148, 0.0001) -> RemoteToggleCode.PROFILE_STANDARD
    ttNear(ttMgdl, 5.150, 0.0001) -> RemoteToggleCode.PROFILE_LOW
    ttNear(ttMgdl, 5.156, 0.0001) -> RemoteToggleCode.SENSOR_AGE_CODE
    ttNear(ttMgdl, 5.152, 0.0001) -> RemoteToggleCode.LIBRE_UKF1
    ttNear(ttMgdl, 5.158, 0.0001) -> RemoteToggleCode.MJ_START
    ttNear(ttMgdl, 5.160, 0.0001) -> RemoteToggleCode.MJ_RESTORE
    ttNear(ttMgdl, 5.162, 0.0001) -> RemoteToggleCode.STEROID_START
    ttNear(ttMgdl, 5.168, 0.0001) -> RemoteToggleCode.STEROID_130
    ttNear(ttMgdl, 5.170, 0.0001) -> RemoteToggleCode.STEROID_150
    ttNear(ttMgdl, 5.172, 0.0001) -> RemoteToggleCode.STEROID_190
    ttNear(ttMgdl, 5.174, 0.0001) -> RemoteToggleCode.STEROID_250
    ttNear(ttMgdl, 5.176, 0.0001) -> RemoteToggleCode.STEROID_OFF
    ttNear(ttMgdl, 5.194, 0.0001) -> RemoteToggleCode.TIER3_BOOST
    ttNear(ttMgdl, 5.210, 0.0001) -> RemoteToggleCode.PROFILE_BATCH_AUTO
    ttNear(ttMgdl, 5.212, 0.0001) -> RemoteToggleCode.PROFILE_BATCH_REVERT
    ttNear(ttMgdl, 5.214, 0.0001) -> RemoteToggleCode.PROFILE_BATCH_REVERT_C
    ttNear(ttMgdl, 5.216, 0.0001) -> RemoteToggleCode.TIER_SET_A
    ttNear(ttMgdl, 5.218, 0.0001) -> RemoteToggleCode.TIER_SET_B
    ttNear(ttMgdl, 5.220, 0.0001) -> RemoteToggleCode.TIER_SET_C
    ttNear(ttMgdl, 5.226, 0.0001) -> RemoteToggleCode.FAST_RISE
    ttNear(ttMgdl, 5.228, 0.0001) -> RemoteToggleCode.LOW_REBOUND
    ttNear(ttMgdl, 5.230, 0.0001) -> RemoteToggleCode.T3_UNRESTRICTED
    ttNear(ttMgdl, 5.182, 0.0001) -> RemoteToggleCode.BOOST_SCALE_DOWN
    ttNear(ttMgdl, 5.184, 0.0001) -> RemoteToggleCode.BOOST_SCALE_UP
    ttNear(ttMgdl, 5.186, 0.0001) -> RemoteToggleCode.BOOST_MAX_DOWN
    ttNear(ttMgdl, 5.188, 0.0001) -> RemoteToggleCode.BOOST_MAX_UP
    ttNear(ttMgdl, 5.190, 0.0001) -> RemoteToggleCode.BOOST_IOB_DOWN
    ttNear(ttMgdl, 5.192, 0.0001) -> RemoteToggleCode.BOOST_IOB_UP
    ttNear(ttMgdl, 5.022, 0.0001) -> RemoteToggleCode.DURA_WEIGHT_DOWN
    ttNear(ttMgdl, 5.024, 0.0001) -> RemoteToggleCode.DURA_WEIGHT_UP
    ttNear(ttMgdl, 5.026, 0.0001) -> RemoteToggleCode.LIBRE_SLOPE_DOWN
    ttNear(ttMgdl, 5.028, 0.0001) -> RemoteToggleCode.LIBRE_SLOPE_UP
    else -> null
}

internal fun nudgeDown(value: Double, floor: Double, step: Double = 0.01): Double = (value - step).coerceAtLeast(floor)

internal fun nudgeUp(value: Double, cap: Double, step: Double = 0.01): Double = (value + step).coerceAtMost(cap)

// Same 0.001 gap the 3.2.1 live-weight check uses.
internal fun liveMatchesBaseline(live: Double, baseline: Double): Boolean = abs(live - baseline) <= 0.001

// Short care-portal label. A leading zero can be dropped, and trailing zeros are cut once the label is longer than 5.
internal fun compactSettingNote(prefix: String, value: Double, places: Int, omitLeadingZero: Boolean = false): String {
    var formatted = fixedDecimals(value, places)
    if (omitLeadingZero) formatted = formatted.removePrefix("0")
    var note = prefix + formatted
    while (note.length > 5 && note.endsWith("0") && note.contains('.')) note = note.dropLast(1)
    return note
}

internal fun todOffsetNote(value: Double): String {
    val text = fixedDecimals(value, 1)
    return if (text.startsWith("-")) "T$text" else "T+$text"
}

internal fun fixedDecimals(value: Double, places: Int): String {
    var scale = 1.0
    repeat(places) { scale *= 10.0 }
    val scaled = round(value * scale).toLong()
    val sign = if (scaled < 0) "-" else ""
    val absScaled = abs(scaled)
    val factor = scale.toLong()
    val whole = absScaled / factor
    val frac = (absScaled % factor).toString().padStart(places, '0')
    return "$sign$whole.$frac"
}
