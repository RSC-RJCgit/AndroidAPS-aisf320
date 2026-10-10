package app.aaps.plugins.aps.openAPSAutoISF

import kotlin.math.abs

internal data class HighBrakeSnapshot(
    val minuteOfDay: Int,
    val bg: Double,
    val delta: Double,
    val shortDelta: Double,
    val longDelta: Double,
    val ukfDelta5: Double?,
    val ukfDelta15: Double?,
    val factorsReady: Boolean,
    val duraIsf: Double,
    val acceIsf: Double,
    val bgIsf: Double,
    val ppIsf: Double,
    val hp1Mmol: Double?,
    val iobChange5: Double,
    val ttLowMgdl: Double?,
    val steroidsOff: Boolean,
    val nightReady30: Boolean,
    val dayReady30: Boolean,
    val twilightReady30: Boolean,
    val twilightReady15: Boolean,
    val nightMarkedWithin6: Boolean,
    val dayMarkedWithin6: Boolean,
    val twilightOwn: Boolean,
    val mj3OrNoMj: Boolean,
    val lowBg50Recent: Boolean,
    val daytimeBypass: Boolean,
)

internal data class HighBrakePlan(
    val cutNight: Boolean = false,
    val fireNight: Boolean = false,
    val cutDay: Boolean = false,
    val fireDayHigh: Boolean = false,
    val fireDayMid: Boolean = false,
    val cutTwilight: Boolean = false,
    val fireTwilight: Boolean = false,
)

internal fun hiBrkDeltasPlateaued(delta: Double, shortDelta: Double, longDelta: Double): Boolean =
    shortDelta > 0.0 && shortDelta < 1.8 &&
        delta > 0.0 && delta < 1.8 &&
        longDelta > 0.0 && longDelta < 5.4

internal fun ttIsFourMmol(lowTargetMgdl: Double?): Boolean {
    if (lowTargetMgdl == null) return false
    val four = 4.0 * 18.0
    return abs(lowTargetMgdl - four) <= 0.08 * 18.0
}

// Night, day, and dawn plateau brakes. A fire blocks the later brakes in the same loop.
// A cut-short does not. A missing 15-minute UKF rise, or a missing hypo prediction, blocks a fire.
// The 5-minute UKF rise is not part of this gate. On 19 Sep at 19:57 and 20:10 the fires that
// ended in a low had a negative 15-minute rise, not a 5-minute one. On 28 Sep from 13:57 to 14:01
// the 15-minute rise stayed positive while the 5-minute rise flickered negative from noise.
internal fun highBrakePlan(s: HighBrakeSnapshot): HighBrakePlan {
    val plateau = hiBrkDeltasPlateaued(s.delta, s.shortDelta, s.longDelta)
    val cutNight = ttIsFourMmol(s.ttLowMgdl) && s.nightMarkedWithin6 && (s.iobChange5 >= 1.0 || !plateau)
    val duraDominant = s.duraIsf >= s.acceIsf && s.duraIsf >= s.bgIsf && s.duraIsf >= s.ppIsf
    val ukfUp = s.ukfDelta15 != null && s.ukfDelta15 > 0.0
    val noTarget = s.ttLowMgdl == null
    val iobOk = s.iobChange5 < 0.5
    val shared = s.factorsReady && s.steroidsOff && plateau && noTarget && duraDominant && ukfUp && iobOk
    val hp65 = (s.hp1Mmol ?: 0.0) >= 6.5
    val hp62 = (s.hp1Mmol ?: 0.0) >= 6.2
    val locks30 = s.nightReady30 && s.dayReady30 && s.twilightReady30
    val fireNight = !cutNight && locks30 && shared && hp65 &&
        minuteInWindow(s.minuteOfDay, 22 * 60, 6 * 60) && s.bg >= 162.2
    val cutDay = ttIsFourMmol(s.ttLowMgdl) && s.dayMarkedWithin6 && (s.iobChange5 >= 1.0 || !plateau)
    val dayWindow = minuteInWindow(s.minuteOfDay, 6 * 60, 1 * 60 + 30)
    val dayAllowed = !fireNight && !cutDay && locks30 && shared && hp65 && dayWindow
    val fireDayHigh = dayAllowed && minuteInWindow(s.minuteOfDay, 6 * 60, 22 * 60) && s.bg >= 162.2
    val fireDayMid = dayAllowed && !fireDayHigh &&
        (minuteInWindow(s.minuteOfDay, 7 * 60, 1 * 60 + 30) || s.daytimeBypass) &&
        s.bg > 135.1 && s.bg < 162.2 && s.mj3OrNoMj && !s.lowBg50Recent
    val cutTwilight = s.twilightOwn && (!plateau || s.iobChange5 >= 1.0)
    val fireTwilight = !fireNight && !fireDayHigh && !fireDayMid && !cutTwilight &&
        s.twilightReady15 && s.nightReady30 && s.dayReady30 && shared && hp62 &&
        minuteInWindow(s.minuteOfDay, 4 * 60, 7 * 60) &&
        s.bg > 6.5 * 18.0 && s.bg < 9.0 * 18.0
    return HighBrakePlan(cutNight, fireNight, cutDay, fireDayHigh, fireDayMid, cutTwilight, fireTwilight)
}
