package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants
import kotlin.math.abs

private const val MMOL_TO_MGDL = Constants.MMOLL_TO_MGDL

internal fun ttNear(ttMgdl: Double, targetMmol: Double, toleranceMmol: Double): Boolean =
    abs(ttMgdl - targetMmol * MMOL_TO_MGDL) <= toleranceMmol * MMOL_TO_MGDL

// The running profile is the safety name and the phone battery is back above 1%.
internal fun batteryOver1ShouldFire(ready: Boolean, running: String, safetyName: String, batteryPercent: Int): Boolean =
    ready && safetyName.isNotBlank() && running == safetyName && batteryPercent > 1

// Full recovery from the 5.7 mmol temp target. off2 is checked first.
// A missing UKF delta must be passed as -9999 so off3 stays closed.
internal fun tt57FullExit(
    ready: Boolean,
    ttMgdl: Double?,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    longDelta: Double,
    iob: Double,
    cob: Double,
    ukfDelta5: Double,
    steps15: Int,
    steps30: Int,
    steps60: Int,
): String? {
    if (!ready || ttMgdl == null || !ttNear(ttMgdl, 5.7, 0.1)) return null
    val off2 = bg >= 108.1 && delta >= -4.50 && shortDelta >= -4.50
    val off3 = cob <= 4.0 && bg >= 81.1 && iob <= 0.8 && ukfDelta5 >= 1.8 && delta >= 1.8
    val off4 = bg >= 108.1 && delta >= 5.40 && shortDelta >= 3.60 && steps30 <= 300
    val off5 = bg > 108.1 && delta in 1.80..14.41 && shortDelta >= 1.80
    val off1 = bg <= 99.1 && delta in 9.01..14.41 && shortDelta in 0.90..14.41 && longDelta in 0.90..9.01 &&
        steps60 <= 500 && steps15 <= 100 && steps30 <= 200
    return when {
        off2 -> "off2"
        off3 -> "off3"
        off4 -> "off4"
        off5 -> "off5"
        off1 -> "off1"
        else -> null
    }
}

// Smaller exit from the same 5.7 mmol target. Cancels the target only.
// [mildConfirmed] is the mild-boost rise, recomputed while the target is still on.
internal fun tt57LightExit(
    ready: Boolean,
    ttMgdl: Double?,
    bg: Double,
    iob: Double,
    cob: Double,
    mildConfirmed: Boolean,
): String? {
    if (!ready || ttMgdl == null || !ttNear(ttMgdl, 5.7, 0.1)) return null
    val new2 = bg >= 147.7 && iob <= 1.6 && cob <= 4.0
    val new3 = bg >= 117.1 && iob <= 0.8 && cob <= 4.0
    return when {
        new2 -> "N2"
        new3 -> "N3"
        mildConfirmed -> "Mld"
        else -> null
    }
}

// Mild rise while a 5.7 mmol target is still on. The live mild gate itself stays closed until the target ends.
internal fun tt57MildConfirmed(
    boostOn: Boolean,
    minuteOfDay: Int,
    daytimeBypass: Boolean,
    bg: Double,
    delta: Double,
    rawDelta5: Double,
    rawDelta1: Double,
    iobChange5: Double,
    smbCount5: Int,
    onLowProfile: Boolean,
    mjActive: Boolean,
    readyBg3: Boolean,
    steps5: Int,
    steps30: Int,
    smbIntervalSec: Double,
    deliveryBaseline: Double,
): Boolean {
    if (!boostOn) return false
    val stackK = if (smbIntervalSec <= 70.0) 1.10 else 1.0
    val scale = deliveryBaseline / 0.17
    val rawFloor = bg < 162.1 || rawDelta1 >= 4.5 * stackK
    val suppressed = smbCount5 <= 1 && rawDelta5 >= 5.4 && rawDelta5 < 14.4 && rawDelta1 < 14.4
    val inWindow = minuteInWindow(minuteOfDay, 8 * 60 + 30, 24 * 60) || daytimeBypass
    return inWindow &&
        ((iobChange5 > 0.40 * stackK * scale && delta >= 5.4 * stackK) || suppressed) &&
        rawDelta5 >= 5.4 * stackK && rawDelta5 < 14.4 * stackK &&
        rawFloor && rawDelta1 < 14.4 * stackK &&
        !onLowProfile && !mjActive && readyBg3 &&
        steps5 <= 100 && steps30 <= 200 &&
        !(bg < 135.1 && iobChange5 > 0.8)
}

// The 8.0 mmol hyp target. The match is 0.001 mg/dL, the same tight check as 3.2.1.
internal fun t80OffShouldFire(
    ready: Boolean,
    ttMgdl: Double?,
    bg: Double,
    delta: Double,
    steps5: Int,
    steps15: Int,
    steps30: Int,
    steps60: Int,
): Boolean {
    if (!ready || ttMgdl == null || abs(ttMgdl - 8.0 * MMOL_TO_MGDL) > 0.001) return false
    val stepsOk = steps60 <= 1500 && steps30 <= 300 && steps5 <= 50 && steps15 <= 500
    val riseOk = (bg >= 126.1 && delta >= 3.6) || (bg >= 117.1 && delta >= 5.4)
    return stepsOk && riseOk
}

// A temp target in the 6.5 to 7.6 mmol band. New1 wins over new2. The caller only cancels the target.
internal fun activityTtExit(ready: Boolean, ttMgdl: Double?, bg: Double, delta: Double, iob: Double, cob: Double): String? {
    if (!ready || ttMgdl == null) return null
    if (ttMgdl < 6.5 * MMOL_TO_MGDL || ttMgdl > 7.6 * MMOL_TO_MGDL) return null
    val new1 = delta >= 1.8 && bg >= 162.1 && cob >= 4.0 && iob <= 2.0
    val new2 = bg >= 144.1 && iob <= 0.8 && delta >= 0.0
    return when {
        new1 -> "1"
        new2 -> "2"
        else -> null
    }
}

// 10:00 until 22:00. Temp target at or under 4.4 mmol, carbs on board, rising, almost no IOB.
internal fun carbsStop1ShouldFire(
    ready: Boolean,
    ttMgdl: Double?,
    cob: Double,
    delta: Double,
    iob: Double,
    minuteOfDay: Int,
): Boolean = ready && ttMgdl != null && ttMgdl <= 79.3 && cob >= 10.0 && delta >= 3.6 && iob <= 0.3 &&
    minuteInWindow(minuteOfDay, 10 * 60, 22 * 60)

// Three ways to leave a carb temp target. Block 1 stays closed while the active target is the mild 5.0 mmol hold.
internal fun carbsStop57Block(
    ready: Boolean,
    ttMgdl: Double?,
    cob: Double,
    iob: Double,
    bg: Double,
    delta: Double,
    minutesSinceBolus: Int,
    ownMildTt: Boolean,
): String? {
    if (!ready || ttMgdl == null) return null
    val block1 = cob > 10.0 && ttMgdl <= 106.3 && ttMgdl >= 82.9 && iob >= 2.2 && bg >= 90.1 && delta >= 0.0 && !ownMildTt
    val block2 = cob > 10.0 && bg >= 90.1 && delta >= 0.9 && ttMgdl >= 111.7 && ttMgdl < 115.3 && iob <= 2.2
    val block3 = minutesSinceBolus <= 10 && ttMgdl >= 102.7 && ttMgdl <= 104.5 && bg >= 90.1 && delta >= 0.0
    return when {
        block1 -> "1"
        block2 -> "2"
        block3 -> "3"
        else -> null
    }
}

// Profile is 100%, no temp target, steroids off. Sets acceleration 0.50, Standard for 30 minutes, and IOB threshold 70.
internal fun carbsThOffBlock(
    ready: Boolean,
    profilePercent: Int,
    ttActive: Boolean,
    steroidsOff: Boolean,
    bg: Double,
    delta: Double,
    shortDelta: Double,
    acce: Double,
    iobTh: Int,
    minutesSinceBolus: Int,
): String? {
    if (!ready || profilePercent != 100 || ttActive || !steroidsOff) return null
    val block1 = shortDelta <= -1.8 && delta <= -1.8 && bg > 90.1 && bg <= 153.1 && minutesSinceBolus >= 80 &&
        (iobTh >= 71 || acce <= 0.03)
    val block2 = bg <= 198.2 && (acce <= 0.49 || acce >= 0.51) && iobTh >= 71 && iobTh <= 96 && bg >= 171.2
    return when {
        block1 -> "1"
        block2 -> "2"
        else -> null
    }
}
