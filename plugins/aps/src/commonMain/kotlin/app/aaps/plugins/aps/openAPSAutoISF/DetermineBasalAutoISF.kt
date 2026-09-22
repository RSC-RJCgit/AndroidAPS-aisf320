package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.ProfileUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Inputs are mg/dL and mg/dL per five minutes, matching determine_basal's native units.
internal fun loRebLookbackMinutes(bg: Double, delta: Double, shortDelta: Double): Int =
    if (bg.isFinite() && delta.isFinite() && shortDelta.isFinite() &&
        bg > 6.0 * 18.0 && delta > 0.10 * 18.0 && shortDelta > 0.10 * 18.0
    ) 30 else 60

internal data class LoRebWindow(
    val lookbackMinutes: Int,
    val recentAlarm: Boolean,
    val carbRebound: Boolean,
    val artifactRebound: Boolean,
    val active: Boolean,
)

// Same rule as UKF3426. A real AlarmHypo must sit inside the 30 or 60 minute lookback.
// Carbs, or a sharp rise with no COB, then mark the rebound. Active is the flag that
// later turns FastRise off. Times are epoch milliseconds. uci and csf use the same units
// as determine_basal, and uci / csf is grams.
internal fun loRebWindow(
    bg: Double,
    delta: Double,
    shortDelta: Double,
    systemTimeMs: Long,
    lastAlarmHypoAtMs: Long,
    cob: Double,
    uci: Double,
    csf: Double,
    recentLowReboundGuardEnabled: Boolean,
): LoRebWindow {
    val lookbackMinutes = loRebLookbackMinutes(bg, delta, shortDelta)
    val alarmAgeMs = systemTimeMs - lastAlarmHypoAtMs
    val recentAlarm = lastAlarmHypoAtMs > 0L &&
        alarmAgeMs >= 0L &&
        alarmAgeMs <= lookbackMinutes * 60_000L
    val uciGrams = if (csf > 0.0) uci / csf else 0.0
    val carbRebound = recentAlarm && (cob > 0.0 || uciGrams >= 0.3)
    val artifactRebound = recentAlarm &&
        cob == 0.0 &&
        delta > 0.40 * 18.0 &&
        shortDelta > 0.30 * 18.0 &&
        bg < 9.4 * 18.0
    return LoRebWindow(
        lookbackMinutes = lookbackMinutes,
        recentAlarm = recentAlarm,
        carbRebound = carbRebound,
        artifactRebound = artifactRebound,
        active = recentLowReboundGuardEnabled && (carbRebound || artifactRebound),
    )
}

internal enum class FastRiseSizeTier {
    OffTest,
    OffLoReb,
    OffNotLibre,
    OffTempTarget,
    Size0201,
    Size0602,
    Size0653,
    Size0504,
    Uncapped0564,
    Size0900,
    Size0850,
    Size0750,
    Uncapped0707,
    Size0700,
    Uncapped0608,
    Size0759,
    None,
}

internal data class FastRiseSizeDecision(
    val factor: Double,
    val tier: FastRiseSizeTier,
)

internal data class FastRiseSizeInput(
    val bg: Double,
    val delta: Double,
    val shortDelta: Double,
    val longDelta: Double,
    val rawDelta5: Double,
    val aapsDelta1: Double,
    val cob: Double,
    val iob: Double,
    val maxIob: Double,
    val microBolus: Double,
    val threshold: Double,
    val hour: Int,
    val slopeRatio: Double = 1.0,
    val libreActive: Boolean = true,
    val tempTargetSet: Boolean = false,
    val fastRiseSettingOn: Boolean = true,
    val loRebWindowActive: Boolean = false,
)

// The three Libre FastRise size tiers from UKF3426. Deltas are mg/dL per five minutes.
// slopeRatio matches determine_basal: compensated delta = delta / slopeRatio, and 1.0 leaves
// the delta unchanged. An active LoReb window, or the test setting off, leaves the SMB at 1.0.
// Sensor-glitch cuts and the late taper are not part of this decision.
internal fun fastRiseSizeDecision(input: FastRiseSizeInput): FastRiseSizeDecision {
    val tier = when {
        !input.fastRiseSettingOn -> FastRiseSizeTier.OffTest
        input.loRebWindowActive -> FastRiseSizeTier.OffLoReb
        !input.libreActive -> FastRiseSizeTier.OffNotLibre
        input.tempTargetSet -> FastRiseSizeTier.OffTempTarget
        else -> fastRiseSizeTier(input)
    }
    val factor = when (tier) {
        FastRiseSizeTier.Size0201 -> 0.2
        FastRiseSizeTier.Size0602 -> 0.6
        FastRiseSizeTier.Size0653 -> 0.65
        FastRiseSizeTier.Size0504 -> 0.5
        FastRiseSizeTier.Size0900 -> 0.9
        FastRiseSizeTier.Size0850 -> 0.85
        FastRiseSizeTier.Size0750 -> 0.75
        FastRiseSizeTier.Size0700 -> 0.7
        FastRiseSizeTier.Size0759 -> 0.75
        else -> 1.0
    }
    return FastRiseSizeDecision(factor, tier)
}

private fun fastRiseSizeTier(input: FastRiseSizeInput): FastRiseSizeTier {
    val delta = input.delta / input.slopeRatio
    val shortDelta = input.shortDelta / input.slopeRatio
    val longDelta = input.longDelta / input.slopeRatio
    val mainGate = input.bg > 6.0 * 18.0 &&
        input.bg < 12.0 * 18.0 &&
        input.cob <= 25.0 &&
        delta >= 0.25 * 18.0 &&
        shortDelta >= 0.10 * 18.0 &&
        (input.iob > 0.12 * input.maxIob || input.hour >= 22 || input.hour <= 5) &&
        input.rawDelta5 >= 0.25 * 18.0 &&
        input.aapsDelta1 >= 0.25 * 18.0
    if (mainGate) {
        return when {
            delta >= 1.0 * 18.0 && shortDelta >= 1.0 * 18.0 && longDelta >= 1.0 * 18.0 ->
                FastRiseSizeTier.Size0201
            delta >= 0.55 * 18.0 && shortDelta >= 0.30 * 18.0 && delta < 1.0 * 18.0 && shortDelta < 1.0 * 18.0 ->
                when {
                    input.bg > 8.8 * 18.0 -> FastRiseSizeTier.Size0602
                    input.bg > 8.0 * 18.0 -> FastRiseSizeTier.Size0653
                    input.bg <= 8.0 * 18.0 && input.microBolus > input.threshold -> FastRiseSizeTier.Size0504
                    input.bg <= 8.0 * 18.0 && input.microBolus <= input.threshold -> FastRiseSizeTier.Uncapped0564
                    else -> FastRiseSizeTier.None
                }
            delta >= 0.25 * 18.0 && shortDelta >= 0.15 * 18.0 && delta < 0.55 * 18.0 && shortDelta < 0.55 * 18.0 ->
                when {
                    input.bg > 8.8 * 18.0 -> FastRiseSizeTier.Size0900
                    input.bg > 8.0 * 18.0 -> FastRiseSizeTier.Size0850
                    input.bg <= 8.0 * 18.0 &&
                        (input.microBolus > input.threshold || (input.hour <= 8 && input.hour >= 3)) ->
                        FastRiseSizeTier.Size0750
                    else -> FastRiseSizeTier.Uncapped0707
                }
            else -> FastRiseSizeTier.None
        }
    } else if (
        delta >= 0.25 * 18.0 &&
        shortDelta >= 0.10 * 18.0 &&
        delta < 0.35 * 18.0 &&
        input.rawDelta5 >= 0.25 * 18.0 &&
        input.aapsDelta1 >= 0.25 * 18.0
    ) {
        return if (input.microBolus > input.threshold || input.hour <= 8) {
            FastRiseSizeTier.Size0700
        } else {
            FastRiseSizeTier.Uncapped0608
        }
    } else if (
        delta >= 0.9 * 18.0 &&
        shortDelta >= 0.7 * 18.0 &&
        input.bg > 11.5 * 18.0 &&
        input.bg < 13.5 * 18.0 &&
        input.iob > input.threshold &&
        input.cob <= 25.0 &&
        input.rawDelta5 >= 0.9 * 18.0 &&
        input.aapsDelta1 >= 0.9 * 18.0
    ) {
        return FastRiseSizeTier.Size0759
    }
    return FastRiseSizeTier.None
}

internal data class FastRiseSmbResult(
    val microBolus: Double,
    val reason: String,
    val tier: FastRiseSizeTier,
)

// Applies one FastRise size decision to an SMB that is already rounded to the pump step.
// roundSmbTo is 1 / bolus increment. A factor of 1 leaves the SMB as it is.
internal fun fastRiseAdjustedMicroBolus(
    microBolus: Double,
    roundSmbTo: Double,
    loReb: LoRebWindow,
    input: FastRiseSizeInput,
): FastRiseSmbResult {
    val decision = fastRiseSizeDecision(
        input.copy(microBolus = microBolus, loRebWindowActive = loReb.active)
    )
    val adjusted = if (decision.factor < 1.0 && microBolus > 0.0 && roundSmbTo > 0.0) {
        floor(microBolus * decision.factor * roundSmbTo) / roundSmbTo
    } else {
        microBolus
    }
    val reason = when {
        !input.fastRiseSettingOn -> "FastRise tiers OFF (test). "
        loReb.active -> "FastRise tiers OFF (LoReb ${loReb.lookbackMinutes}min). "
        !input.libreActive -> "FastRise tiers OFF (not Libre). "
        input.tempTargetSet -> "FastRise tiers OFF (temp target). "
        adjusted != microBolus -> "FastRise x${decision.factor} SMB $microBolus -> $adjusted. "
        else -> ""
    }
    return FastRiseSmbResult(adjusted, reason, decision.tier)
}

internal data class ShowerTwilightInput(
    val hour: Int,
    val bg: Double,
    val steps60: Int,
    val cob: Double,
    val tempTargetSet: Boolean,
    val delta: Double,
    val shortDelta: Double,
    val iobThUser: Int,
    val rawDelta5: Double,
    val aapsDelta1: Double,
    val microBolus: Double,
    val iob: Double,
    val maxIob: Double,
)

internal data class ShowerTwilightResult(
    val microBolus: Double,
    val reason: String,
    // True when a morning gate matched, even if the SMB did not change.
    val matched: Boolean,
)

// Morning SMB cap from 3.2.1, hours 5 to 9. The quieter step gate is checked first.
// determine_basal applies this before the FastRise size tiers.
internal fun showerTwilightSmb(input: ShowerTwilightInput): ShowerTwilightResult {
    val morning = input.hour in 5..9 &&
        input.bg <= 8.0 * 18.0 &&
        input.cob <= 0.0 &&
        !input.tempTargetSet &&
        input.iobThUser < 71
    val quietSteps = morning &&
        input.steps60 < 10 &&
        input.delta >= 0.25 * 18.0 &&
        input.shortDelta >= 0.1 * 18.0 &&
        input.rawDelta5 >= 0.25 * 18.0 &&
        input.aapsDelta1 >= 0.25 * 18.0
    val busierSteps = morning &&
        input.steps60 < 100 &&
        input.delta >= 0.35 * 18.0 &&
        input.shortDelta >= 0.15 * 18.0 &&
        input.rawDelta5 >= 0.35 * 18.0 &&
        input.aapsDelta1 >= 0.35 * 18.0
    if (!quietSteps && !busierSteps) return ShowerTwilightResult(input.microBolus, "", matched = false)

    val perSmbCap = 0.04 * input.maxIob
    val iobCeiling = 0.09 * input.maxIob
    var smb = input.microBolus
    val reason = StringBuilder()
    if (smb > perSmbCap) {
        smb = perSmbCap
        reason.append("Shower SMB cap ${twoDecimals(smb)}. ")
    }
    if (smb + input.iob > iobCeiling) {
        smb = iobCeiling - input.iob
        reason.append("Shower IOB ceiling ${twoDecimals(smb)}. ")
    }
    if (!quietSteps) reason.append("Shower time. ")
    return ShowerTwilightResult(smb, reason.toString(), matched = true)
}

internal data class MorningThenGlitchResult(
    val microBolus: Double,
    val reason: String,
)

// Morning cap first. A match, even one that leaves the SMB unchanged, skips the glitch cuts.
internal fun morningThenGlitch(
    showerInput: ShowerTwilightInput,
    glitchInput: SensorGlitchInput,
): MorningThenGlitchResult {
    val shower = showerTwilightSmb(showerInput)
    if (shower.matched) return MorningThenGlitchResult(shower.microBolus, shower.reason)
    val glitch = sensorGlitchSmb(glitchInput.copy(microBolus = shower.microBolus))
    return MorningThenGlitchResult(glitch.microBolus, shower.reason + glitch.reason)
}

private fun twoDecimals(value: Double): Double =
    (value * 100.0).roundToInt() / 100.0

internal data class SensorGlitchInput(
    val bgAcceleration: Double,
    val delta: Double,
    val shortDelta: Double,
    val longDelta: Double,
    val iob: Double,
    val cob: Double,
    val bg: Double,
    val hour: Int,
    val tempTargetSet: Boolean,
    val targetBg: Double,
    val rawDelta5: Double,
    val aapsDelta1: Double,
    val microBolus: Double,
)

internal data class SensorGlitchResult(
    val microBolus: Double,
    val reason: String,
)

// Sensor-glitch SMB cuts from 3.2.1. The first matching gate wins.
// determine_basal applies these after the morning cap, and only when that cap did not match.
// The FastRise switch does not turn these cuts off.
internal fun sensorGlitchSmb(input: SensorGlitchInput): SensorGlitchResult {
    val smb = input.microBolus
    val delta = input.delta
    val shortDelta = input.shortDelta
    val longDelta = input.longDelta
    val bg = input.bg

    if (input.bgAcceleration > 0.30 * 18.0 && delta >= 0.50 * 18.0 && input.iob < 0.70) {
        val cut = smb * 0.7
        return SensorGlitchResult(cut, "Low IOB accel glitch 0.712 SMB ${twoDecimals(cut)}. ")
    }
    if (delta >= 0.40 * 18.0 &&
        shortDelta <= 0.5 * delta &&
        longDelta <= 0.10 * delta &&
        input.cob <= 20.0 &&
        bg < 10.0 * 18.0
    ) {
        val cut = smb * 0.5
        return SensorGlitchResult(cut, "Sensor swing 0.511 SMB ${twoDecimals(cut)}. ")
    }
    if (delta >= 0.50 * 18.0 &&
        shortDelta >= 0.40 * 18.0 &&
        longDelta <= 0.15 * 18.0 &&
        bg < 10.0 * 18.0
    ) {
        val cut = smb * 0.5
        return SensorGlitchResult(cut, "Sensor swing 0.512 SMB ${twoDecimals(cut)}. ")
    }
    if (delta >= 0.50 * 18.0 &&
        input.hour >= 18 &&
        input.cob > 10.0 &&
        delta <= 0.95 * 18.0 &&
        bg < 10.0 * 18.0
    ) {
        val cut = smb * 0.7
        return SensorGlitchResult(cut, "Post carb swing 0.713 SMB ${twoDecimals(cut)}. ")
    }
    if (delta >= 0.25 * 18.0 &&
        shortDelta >= 0.20 * 18.0 &&
        bg < 10.0 * 18.0 &&
        input.tempTargetSet &&
        input.targetBg <= 4.1 * 18.0 &&
        input.rawDelta5 >= 0.25 * 18.0 &&
        input.aapsDelta1 >= 0.25 * 18.0
    ) {
        val cut = smb * 0.5
        return SensorGlitchResult(cut, "Low temp target SMB ${twoDecimals(cut)}. ")
    }
    if (delta > 1.0 * 18.0 && longDelta < -0.05 * 18.0 && bg < 162.0) {
        return SensorGlitchResult(0.0, "Glitch zero SMB. ")
    }
    if (shortDelta >= 0.25 * 18.0 &&
        longDelta <= 0.08 * 18.0 &&
        shortDelta >= 3.0 * (longDelta + 0.01)
    ) {
        val cut = smb * 0.7
        return SensorGlitchResult(cut, "Short spike 0.713 SMB ${twoDecimals(cut)}. ")
    }
    return SensorGlitchResult(smb, "")
}

internal data class SmbStepResult(
    val microBolus: Double,
    val reason: String,
)

// Early-morning and twilight run only when the FastRise size cascade did not take a branch,
// and only for Libre with no temp target. The test switch and an active LoReb window skip the
// size tiers, and these two cuts still run.
internal fun earlyMorningFollowOnAllowed(
    tier: FastRiseSizeTier,
    libreActive: Boolean,
    tempTargetSet: Boolean,
): Boolean = libreActive && !tempTargetSet &&
    (tier == FastRiseSizeTier.None ||
        tier == FastRiseSizeTier.OffTest ||
        tier == FastRiseSizeTier.OffLoReb)

internal data class EarlyMorningTwilightInput(
    val hour: Int,
    val iob: Double,
    val maxIob: Double,
    val cob: Double,
    val immediateRawDelta5: Double,
    val rawDelta15: Double,
    val bg: Double,
    val delta: Double,
    val shortDelta: Double,
    val steps60: Int,
    val microBolus: Double,
)

// Hours 6 to 9 use the raw 5-minute rise. A missing 15-minute raw (9999) does not block the cut.
// Hours 6 to 8 with very few steps use the smaller twilight cap instead, and only if the raw cut did not match.
internal fun earlyMorningThenTwilight(input: EarlyMorningTwilightInput): SmbStepResult {
    val raw15Ok = input.rawDelta15 >= 9999.0 ||
        (input.rawDelta15 >= 0.2 * 18.0 && input.immediateRawDelta5 > input.rawDelta15 * 1.5)
    if (input.hour in 6..9 &&
        input.iob > 0.075 * input.maxIob &&
        input.cob <= 25.0 &&
        input.immediateRawDelta5 >= 0.5 * 18.0 &&
        raw15Ok
    ) {
        val cut = input.microBolus * 0.8
        return SmbStepResult(cut, "Early morning raw rise x0.8 SMB ${twoDecimals(cut)}. ")
    }
    if (input.hour in 6..8 &&
        input.bg < 9.0 * 18.0 &&
        input.delta < 1.0 * 18.0 &&
        input.shortDelta < 1.0 * 18.0 &&
        input.steps60 < 10 &&
        input.cob <= 25.0
    ) {
        var smb = input.microBolus
        val reason = StringBuilder()
        val perSmb = 0.02 * input.maxIob
        if (smb > perSmb) {
            smb = perSmb
            reason.append("Twilight SMB cap ${twoDecimals(smb)}. ")
        }
        val ceiling = 0.09 * input.maxIob
        if (smb + input.iob > ceiling) {
            smb = ceiling - input.iob
            reason.append("Twilight IOB ceiling ${twoDecimals(smb)}. ")
        }
        reason.append("Twilight hours. ")
        return SmbStepResult(smb, reason.toString())
    }
    return SmbStepResult(input.microBolus, "")
}

// A lot of walking cuts the SMB to 70% when it is already above the FastRise threshold.
internal fun highStepsSmbCut(
    microBolus: Double,
    threshold: Double,
    steps30: Int,
    steps60: Int,
    steps180: Int,
): SmbStepResult {
    if (microBolus > threshold && (steps30 > 1500 || steps60 > 800 || steps180 > 1500)) {
        val cut = microBolus * 0.70
        return SmbStepResult(cut, "High steps x0.7 SMB ${twoDecimals(cut)}. ")
    }
    return SmbStepResult(microBolus, "")
}

// The LoReb window already turns the size tiers off. This is the extra SMB trim from 3.2.1.
internal fun loRebSmbTrim(microBolus: Double, windowActive: Boolean, guardEnabled: Boolean): SmbStepResult {
    if (!guardEnabled || !windowActive || microBolus <= 0.10) return SmbStepResult(microBolus, "")
    val cut = if (microBolus <= 0.25) microBolus * 0.75 else microBolus * 0.5
    return SmbStepResult(cut, "Low rebound SMB ${twoDecimals(microBolus)} -> ${twoDecimals(cut)}. ")
}

// Later FastRise requests shrink after a lot of SMB, or when IOB is already high and carbs are gone.
// The UamBst boost marks are not in this app, so that branch is left out.
internal fun lateFastRiseTaper(
    microBolus: Double,
    fastRiseNow: Boolean,
    iob: Double,
    maxIob: Double,
    cob: Double,
    smbSum30: Double,
): SmbStepResult {
    if (!fastRiseNow || microBolus <= 0.0) return SmbStepResult(microBolus, "")
    val iobHighNoCob = iob >= 0.18 * maxIob && cob <= 0.0
    val factor = when {
        iobHighNoCob -> 0.50
        smbSum30 >= 1.9 -> 0.50
        smbSum30 >= 1.5 -> 0.75
        else -> 1.0
    }
    if (factor >= 1.0) return SmbStepResult(microBolus, "")
    val cut = microBolus * factor
    val why = if (iobHighNoCob) "high IOB" else "SMB 30 min ${twoDecimals(smbSum30)}U"
    return SmbStepResult(cut, "Late FastRise $why x${twoDecimals(factor)} SMB ${twoDecimals(cut)}. ")
}

internal fun smbCap30Min(microBolus: Double, smbSum30: Double): SmbStepResult {
    val allowance = (2.1 - smbSum30).coerceAtLeast(0.0)
    if (microBolus <= allowance) return SmbStepResult(microBolus, "")
    return SmbStepResult(allowance, "30 min SMB cap ${twoDecimals(microBolus)} -> ${twoDecimals(allowance)}. ")
}

// 00:00-04:00 is 0.6 U under 8.0 mmol/L. 22:00-00:00 is 1.0 U under 8.0. Otherwise 1.5 U.
internal fun smbCap10Min(microBolus: Double, smbSum10: Double, minuteOfDay: Int, bg: Double): SmbStepResult {
    val bgUnder8 = bg < 144.0
    val cap = when {
        minuteOfDay < 240 && bgUnder8 -> 0.6
        minuteOfDay >= 1320 && bgUnder8 -> 1.0
        else -> 1.5
    }
    val allowance = cap - smbSum10
    if (microBolus <= allowance) return SmbStepResult(microBolus, "")
    val cut = if (allowance > 0.0) allowance else 0.0
    return SmbStepResult(cut, "10 min SMB cap ${twoDecimals(microBolus)} -> ${twoDecimals(cut)}. ")
}

internal data class AfterFastRiseInput(
    val tier: FastRiseSizeTier,
    val libreActive: Boolean,
    val tempTargetSet: Boolean,
    val microBolus: Double,
    val roundSmbTo: Double,
    val earlyMorning: EarlyMorningTwilightInput,
    val threshold: Double,
    val steps30: Int,
    val steps60: Int,
    val steps180: Int,
    val loRebActive: Boolean,
    val loRebGuardEnabled: Boolean,
    val fastRiseNow: Boolean,
    val iob: Double,
    val maxIob: Double,
    val cob: Double,
    val smbSum30: Double,
    val smbSum10: Double,
    val minuteOfDay: Int,
    val bg: Double,
)

// Order matches 3.2.1 after the size tiers: early morning, high steps, LoReb trim, late taper, then the two caps.
internal fun afterFastRiseSmb(input: AfterFastRiseInput): SmbStepResult {
    val reason = StringBuilder()
    var smb = input.microBolus
    if (earlyMorningFollowOnAllowed(input.tier, input.libreActive, input.tempTargetSet)) {
        val early = earlyMorningThenTwilight(input.earlyMorning.copy(microBolus = smb))
        smb = early.microBolus
        reason.append(early.reason)
    }
    val steps = highStepsSmbCut(smb, input.threshold, input.steps30, input.steps60, input.steps180)
    smb = steps.microBolus
    reason.append(steps.reason)
    val loReb = loRebSmbTrim(smb, input.loRebActive, input.loRebGuardEnabled)
    smb = loReb.microBolus
    reason.append(loReb.reason)
    val taper = lateFastRiseTaper(smb, input.fastRiseNow, input.iob, input.maxIob, input.cob, input.smbSum30)
    smb = taper.microBolus
    reason.append(taper.reason)
    val cap30 = smbCap30Min(smb, input.smbSum30)
    smb = cap30.microBolus
    reason.append(cap30.reason)
    val cap10 = smbCap10Min(smb, input.smbSum10, input.minuteOfDay, input.bg)
    smb = cap10.microBolus
    reason.append(cap10.reason)
    if (input.roundSmbTo > 0.0 && smb.isFinite()) smb = floor(smb * input.roundSmbTo) / input.roundSmbTo
    if (!smb.isFinite() || smb <= 0.0) smb = 0.0
    return SmbStepResult(smb, reason.toString())
}

internal data class BlendedTdd(
    val ratio: Double,
    val tdd7D: Double,
)

// 8-hour weighted blend against the 7 day average, from 3.2.1. Null when a part is missing.
// The ratio is limited to 0.70..1.50 before the tighter TDD-factor clamp.
internal fun blendedTddRatio(
    tdd7D: Double?,
    tdd1D: Double?,
    tddLast4H: Double?,
    tddLast8to4H: Double?,
): BlendedTdd? {
    if (tdd7D == null || tdd7D <= 0.0 || tdd1D == null || tddLast4H == null || tddLast8to4H == null) return null
    val weighted8h = ((1.4 * tddLast4H) + (0.6 * tddLast8to4H)) * 3.0
    val blended = if (weighted8h < 0.75 * tdd7D) {
        val adjusted7d = weighted8h + ((weighted8h / tdd7D) * (tdd7D - weighted8h))
        (adjusted7d * 0.34) + (tdd1D * 0.33) + (weighted8h * 0.33)
    } else {
        (weighted8h * 0.33) + (tdd7D * 0.34) + (tdd1D * 0.33)
    }
    return BlendedTdd(ratio = (blended / tdd7D).coerceIn(0.70, 1.50), tdd7D = tdd7D)
}

// The switch uses the live ratio, clamped to 0.80..1.20. Off uses the fallback setting.
internal fun tddFactorValue(enabled: Boolean, fallback: Double, tddRatio: Double): Double {
    val raw = if (enabled) tddRatio.coerceIn(0.80, 1.20) else fallback
    return twoDecimals(raw)
}

@SingleIn(AppScope::class)
@Inject
class DetermineBasalAutoISF(
    private val profileUtil: ProfileUtil
) {

    private var consoleError = mutableListOf<String>()
    private var consoleLog = mutableListOf<String>()

    private fun Double.toFixed2(): String = NumberFormat.DECIMAL_2_UP_TO_3.format(round(this, 2))

    fun round_basal(value: Double): Double = value

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        // Pass NaN AND ±Infinity through untouched. Math.round saturates at Long.MAX_VALUE, so without
        // this an infinite value would come back as a normal looking ~9.2e18/scale and every isFinite()
        // guard downstream would be blind to it - the guards all read values that went through here.
        if (!value.isFinite()) return value
        val scale = 10.0.pow(digits.toDouble())
        return (value * scale).roundToLong() / scale
    }

    // kotlin.math.max and Double.coerceAtLeast both propagate NaN. This variant pins
    // NaN/±Infinity to `minimum` and otherwise behaves like coerceAtLeast.
    private fun Double.coerceAtLeastFinite(minimum: Double): Double =
        if (isFinite()) maxOf(this, minimum) else minimum

    fun Double.withoutZeros(): String = NumberFormat.UP_TO_2_DECIMALS.format(this)
    fun round(value: Double): Int =
        // Crash backstop: roundToInt() throws on NaN and saturates at Int.MAX_VALUE on ±Infinity.
        // Substitute 0, but record a token that the reportNonFiniteRtFields tripwire
        // (PersistenceLayerImpl) surfaces to Crashlytics, so laundering here does not hide the real bug.
        if (!value.isFinite()) {
            consoleError.add("round(): non-finite value substituted with 0 (roundNonFinite=$value)")
            0
        } else value.roundToInt()

    // we expect BG to rise or fall at the rate of BGI,
    // adjusted by the rate at which BG would need to rise /
    // fall to get eventualBG to target over 2 hours
    fun calculate_expected_delta(targetBg: Double, eventualBg: Double, bgi: Double): Double {
        // (hours * mins_per_hour) / 5 = how many 5 minute periods in 2h = 24
        val fiveMinBlocks = (2 * 60) / 5
        val targetDelta = targetBg - eventualBg
        return /* expectedDelta */ round(bgi + (targetDelta / fiveMinBlocks), 1)
    }

    fun convert_bg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")
    //DecimalFormat("0.#").format(profileUtil.fromMgdlToUnits(value))
    //if (profile.out_units === "mmol/L") round(value / 18, 1).toFixed(1);
    //else Math.round(value);

    fun enable_smb(profile: OapsProfileAutoIsf, microBolusAllowed: Boolean, meal_data: MealData, target_bg: Double): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && target_bg > 100) {
            consoleError.add("SMB disabled due to high temptarget of $target_bg")
            return false
        }

        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (profile.enableSMB_always) {
            consoleError.add("SMB enabled due to enableSMB_always")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) while we have COB
        if (profile.enableSMB_with_COB && meal_data.mealCOB != 0.0) {
            consoleError.add("SMB enabled for COB of ${meal_data.mealCOB}")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) for a full 6 hours after any carb entry
        // (6 hours is defined in carbWindow in lib/meal/total.js)
        if (profile.enableSMB_after_carbs && meal_data.carbs != 0.0) {
            consoleError.add("SMB enabled for 6h after carb entry")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
        if (profile.enableSMB_with_temptarget && (profile.temptargetSet && target_bg < 100)) {
            consoleError.add("SMB enabled for temptarget of ${convert_bg(target_bg)}")
            return true
        }

        consoleError.add("SMB disabled (no enableSMB preferences active or no condition satisfied)")
        return false
    }

    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError.add(msg)
    }

    private fun getMaxSafeBasal(profile: OapsProfileAutoIsf): Double =
        min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * profile.current_basal))

    fun setTempBasal(_rate: Double, duration: Int, profile: OapsProfileAutoIsf, rT: RT, currenttemp: CurrentTemp): RT {
        //var maxSafeBasal = kotlin.math.min(profile.max_basal, 3 * profile.max_daily_basal, 4 * profile.current_basal);

        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate
        // A non-finite rate passes both clamps below untouched, because every comparison with NaN is
        // false, and then lands in rT.rate - which DetermineBasalResult turns into a real pump command.
        // Do not invent a dose out of a broken value: fall back to the profile basal, which the
        // `suggestedRate == profile.current_basal` branch below turns into a neutral temp or cancels
        // the running temp. Zero would be worse, because that actively withholds basal for 30 minutes.
        // The interpolated value leaves a literal `setTempBasalRate=NaN` token in consoleError, which is
        // what the reportNonFiniteRtFields tripwire (PersistenceLayerImpl) scans for.
        if (!rate.isFinite()) {
            consoleError.add("setTempBasal: setTempBasalRate=$_rate is not finite, using profile basal instead")
            rate = profile.current_basal
        }
        if (rate < 0) rate = 0.0
        else if (rate > maxSafeBasal) rate = maxSafeBasal

        val suggestedRate = round_basal(rate)
        if (currenttemp.duration > (duration - 10) && currenttemp.duration <= 120 && suggestedRate <= currenttemp.rate * 1.2 && suggestedRate >= currenttemp.rate * 0.8 && duration > 0) {
            rT.reason.append(" ${currenttemp.duration}m left and ${currenttemp.rate.withoutZeros()} ~ req ${suggestedRate.withoutZeros()}U/hr: no temp required")
            return rT
        }

        if (suggestedRate == profile.current_basal) {
            if (profile.skip_neutral_temps) {
                if (currenttemp.duration > 0) {
                    reason(rT, "Suggested rate is same as profile rate, a temp basal is active, canceling current temp")
                    rT.duration = 0
                    rT.rate = 0.0
                    return rT
                } else {
                    reason(rT, "Suggested rate is same as profile rate, no temp basal is active, doing nothing")
                    return rT
                }
            } else {
                reason(rT, "Setting neutral temp basal of ${profile.current_basal}U/hr")
                rT.duration = duration
                rT.rate = suggestedRate
                return rT
            }
        } else {
            rT.duration = duration
            rT.rate = suggestedRate
            return rT
        }
    }

    /**
     * Give up on this run instead of turning a broken internal value into a dose.
     *
     * Same policy the invalid input check in [determine_basal] uses for bad CGM data: put a running
     * high temp back to neutral, shorten a long zero temp, otherwise leave the pump alone. Never
     * suspend, and never guess a rate.
     *
     * [token] must be written as `name=value` so the interpolated NaN / Infinity keeps the literal
     * `name=NaN` form that the `PersistenceLayerImpl.reportNonFiniteRtFields` tripwire scans for -
     * that is how the event reaches Crashlytics even though the returned result is finite.
     */
    private fun abortNonFinite(token: String, rT: RT, currenttemp: CurrentTemp, basal: Double, deliverAt: Long): RT {
        consoleError.add("Aborting run: $token")
        rT.reason.append("Aborting run: $token. ")
        if (currenttemp.rate > basal) {
            rT.reason.append("Replacing high temp basal of ${currenttemp.rate} with neutral temp of $basal. ")
            rT.deliverAt = deliverAt
            rT.duration = 30
            rT.rate = basal
        } else if (currenttemp.rate == 0.0 && currenttemp.duration > 30) {
            rT.reason.append("Shortening ${currenttemp.duration}m long zero temp to 30m. ")
            rT.deliverAt = deliverAt
            rT.duration = 30
            rT.rate = 0.0
        } else {
            rT.reason.append("Temp ${currenttemp.rate} <= current basal ${round(basal, 2)}U/hr; doing nothing. ")
        }
        return rT
    }

    fun determine_basal(
        glucose_status: GlucoseStatus, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfileAutoIsf, autosens_data: AutosensResult, meal_data: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, autoIsfMode: Boolean, loop_wanted_smb: String, profile_percentage: Int, smb_ratio: Double,
        smb_max_range_extension: Double, iob_threshold_percent: Int, auto_isf_consoleError: MutableList<String>, auto_isf_consoleLog: MutableList<String>,
        // Null means this caller does not use the FastRise size tiers. Existing tests stay on that path.
        fastRiseSettingOn: Boolean? = null,
        libreActive: Boolean = false,
        rawDelta5Mgdl: Double = 0.0,
        aapsDelta1Mgdl: Double = 0.0,
        hour: Int = -1,
        lastAlarmHypoAt: Long = 0L,
        lowReboundGuardEnabled: Boolean = false,
        fastRiseSlopeRatio: Double = 1.0,
        // Walking steps in the last hour. 0 means none were stored. The morning cap uses this.
        steps60: Int = 0,
        // The IOB-threshold percent from settings, before any profile scaling.
        iobThUser: Int = 100,
        // BG acceleration from the parabola fit. 0 leaves the low-IOB accel cut closed.
        bgAcceleration: Double = 0.0,
        // Missing raw uses 9999 so the early-morning guard still caps. FastRise keeps its own 0.
        immediateRawDelta5Mgdl: Double = 9999.0,
        rawDelta15Mgdl: Double = 9999.0,
        steps30: Int = 0,
        steps180: Int = 0,
        smbSum10Min: Double = 0.0,
        smbSum30Min: Double = 0.0,
        // 1.0 leaves insulinReq and max IOB unchanged. Live ratio is clamped to 0.80..1.20.
        tddFactor: Double = 1.0,
    ): RT {
        consoleError = mutableListOf()
        consoleLog = mutableListOf()
        var rT = RT(
            algorithm = APSResult.Algorithm.AUTO_ISF,
            runningDynamicIsf = autoIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )

        // TODO eliminate
        val deliverAt = currentTime

        // TODO eliminate
        val profile_current_basal = round_basal(profile.current_basal)
        var basal = profile_current_basal

        // TODO eliminate
        val systemTime = currentTime

        // TODO eliminate
        val bgTime = glucose_status.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        // TODO eliminate
        val bg = glucose_status.glucose
        // TODO eliminate
        val noise = glucose_status.noise
        // 38 is an xDrip error state that usually indicates sensor failure
        // all other BG values between 11 and 37 mg/dL reflect non-error-code BG values, so we should zero temp for those
        if (bg <= 10 || bg == 38.0 || noise >= 3) {  //Dexcom is in ??? mode or calibrating, or xDrip reports high noise
            rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
        }
        if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
            rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read ${minAgo}m ago at $bgTime")
            // if BG is too old/noisy, or is changing less than 1 mg/dL/5m for 45m, cancel any high temps and shorten any long zero temps
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append("Error: CGM data is unchanged for the past ~45m")
        }
        if (bg <= 10 || bg == 38.0 || noise >= 3 || minAgo > 12 || minAgo < -5 || (bg > 60 && flatBGsDetected)) {
            if (currenttemp.rate > basal) { // high temp is running
                rT.reason.append(". Replacing high temp basal of ${currenttemp.rate} with neutral temp of $basal")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = basal
                return rT
            } else if (currenttemp.rate == 0.0 && currenttemp.duration > 30) { //shorten long zero temps to 30m
                rT.reason.append(". Shortening " + currenttemp.duration + "m long zero temp to 30m. ")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = 0.0
                return rT
            } else { //do nothing.
                rT.reason.append(". Temp ${currenttemp.rate} <= current basal ${round(basal, 2)}U/hr; doing nothing. ")
                return rT
            }
        }

        // TODO eliminate
        var max_iob = profile.max_iob // maximum amount of non-bolus IOB OpenAPS will ever deliver

        // if min and max are set, then set target to their average
        var target_bg = (profile.min_bg + profile.max_bg) / 2
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg

        var sensitivityRatio = 1.0
        // var origin_sens = ""
        var exercise_ratio = 1.0
        val high_temptarget_raises_sensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
        val normalTarget = Constants.NORMAL_TARGET_MGDL // evaluate high/low temptarget against normal target, not scheduled target (which might change)
        // when temptarget is 160 mg/dL, run 50% basal (120 = 75%; 140 = 60%),  80 mg/dL with low_temptarget_lowers_sensitivity would give 1.5x basal, but is limited to autosens_max (1.2x by default)
        val halfBasalTarget = profile.half_basal_exercise_target

        if (high_temptarget_raises_sensitivity && profile.temptargetSet && target_bg > normalTarget
            || profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && target_bg < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val c = (halfBasalTarget - normalTarget).toDouble()
            if (c * (c + target_bg - normalTarget) <= 0.0) {
                sensitivityRatio = profile.autosens_max
            } else {
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
                sensitivityRatio = round(sensitivityRatio, 2)
                exercise_ratio = sensitivityRatio
                // origin_sens = "from TT modifier"
                consoleError.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
            }
        } else {
            sensitivityRatio = autosens_data.ratio
            consoleError.add("Autosens ratio: $sensitivityRatio; ")
        }
        var iobTH_reduction_ratio = 1.0
        if (iob_threshold_percent != 100) {
            iobTH_reduction_ratio = profile_percentage / 100.0 * exercise_ratio     // later: * activityRatio;
        }
        basal = profile.current_basal * sensitivityRatio
        basal = round_basal(basal)
        if (basal != profile_current_basal)
            consoleError.add("Adjusting basal from $profile_current_basal to $basal;")
        else
            consoleError.add("Basal unchanged: $basal;")

        // adjust min, max, and target BG for sensitivity, such that 50% increase in ISF raises target from 100 to 120
        if (profile.temptargetSet) {
            //console.log("Temp Target set, not adjusting with autosens; ");
        } else {
            if (profile.sensitivity_raises_target && autosens_data.ratio < 1 || profile.resistance_lowers_target && autosens_data.ratio > 1) {
                // with a target of 100, default 0.7-1.2 autosens min/max range would allow a 93-117 target range
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                var new_target_bg = round((target_bg - 60) / autosens_data.ratio, 0) + 60
                // don't allow target_bg below 80
                new_target_bg = max(80.0, new_target_bg)
                if (target_bg == new_target_bg)
                    consoleError.add("target_bg unchanged: $new_target_bg; ")
                else
                    consoleError.add("target_bg from $target_bg to $new_target_bg; ")

                target_bg = new_target_bg
            }
        }

        val iobArray = iob_data_array
        val iob_data = iobArray[0]

        val tick: String
        tick = if (glucose_status.delta > -0.5) {
            "+" + round(glucose_status.delta)
        } else {
            round(glucose_status.delta).toString()
        }
        val minDelta = min(glucose_status.delta, glucose_status.shortAvgDelta)
        val minAvgDelta = min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta)
        val maxDelta = max(glucose_status.delta, max(glucose_status.shortAvgDelta, glucose_status.longAvgDelta))

        val profile_sens = round(profile.sens, 1)
        val adjusted_sens = round(profile.sens / sensitivityRatio, 1)
        if (adjusted_sens != profile_sens) {
            consoleError.add("ISF from $profile_sens to $adjusted_sens")
        } else {
            consoleError.add("ISF unchanged: $adjusted_sens")
        }
        val sens =
            if (autoIsfMode) {
                profile.variable_sens
            } else {
                adjusted_sens
                //console.log(" (autosens ratio "+sensitivityRatio+")");
            }
        consoleError.add("CR: ${profile.carb_ratio}")

        if (autoIsfMode) {
            consoleError.add("----------------------------------")
            consoleError.add("start AutoISF ${profile.autoISF_version}")
            consoleError.add("----------------------------------")
            consoleError.addAll(auto_isf_consoleLog)
            consoleError.addAll(auto_isf_consoleError)
        }
        // mod autoISF3.0-dev: if that would put us over iobTH, then reduce accordingly; allow 30% overrun
        val iobTHtolerance = 130.0
        val iobTHvirtual = iob_threshold_percent * iobTHtolerance / 10000.0 * profile.max_iob * iobTH_reduction_ratio
        var enableSMB = false
        if (microBolusAllowed && loop_wanted_smb != "AAPS") {
            if (loop_wanted_smb == "enforced" || loop_wanted_smb == "fullLoop") {              // otherwise FL switched SMB off
                enableSMB = true
            }
        } else {
            enableSMB = enable_smb(
                profile,
                microBolusAllowed,
                meal_data,
                target_bg
            )
        }

        //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
        val bgi = round((-iob_data.activity * sens * 5), 2)
        // project deviations for 30 minutes
        var deviation = round(30 / 5 * (minDelta - bgi))
        // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            // and if deviation is still negative, use long_avgdelta
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucose_status.longAvgDelta - bgi))
            }
        }

        // calculate the naive (bolus calculator math) eventual BG based on net IOB and sensitivity
        val naive_eventualBG =
            if (autoIsfMode)
                round(bg - (iob_data.iob * sens), 0)
            else {
                if (iob_data.iob > 0) round(bg - (iob_data.iob * sens), 0)
                else  // if IOB is negative, be more conservative and use the lower of sens, profile.sens
                    round(bg - (iob_data.iob * min(sens, profile.sens)), 0)
            }
        // and adjust it for the deviation above
        var eventualBG = naive_eventualBG + deviation

        // raise target for noisy / raw CGM data
        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            // with target=100, as BG rises from 100 to 160, adjustedTarget drops from 100 to 80
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, target_bg - (bg - target_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedMinBG, don’t use it
            //console.error("naive_eventualBG:",naive_eventualBG+", eventualBG:",eventualBG);
            if (eventualBG > adjustedMinBG && naive_eventualBG > adjustedMinBG && min_bg > adjustedMinBG) {
                consoleError.add("Adjusting targets for high BG: min_bg from $min_bg to $adjustedMinBG; ")
                min_bg = adjustedMinBG
            } else {
                consoleError.add("min_bg unchanged: $min_bg; ")
            }
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedTargetBG, don’t use it
            if (eventualBG > adjustedTargetBG && naive_eventualBG > adjustedTargetBG && target_bg > adjustedTargetBG) {
                consoleError.add("target_bg from $target_bg to $adjustedTargetBG; ")
                target_bg = adjustedTargetBG
            } else {
                consoleError.add("target_bg unchanged: $target_bg; ")
            }
            // if eventualBG, naive_eventualBG, and max_bg aren't all above adjustedMaxBG, don’t use it
            if (eventualBG > adjustedMaxBG && naive_eventualBG > adjustedMaxBG && max_bg > adjustedMaxBG) {
                consoleError.add("max_bg from $max_bg to $adjustedMaxBG")
                max_bg = adjustedMaxBG
            } else {
                consoleError.add("max_bg unchanged: $max_bg")
            }
        }

        val expectedDelta = calculate_expected_delta(target_bg, eventualBG, bgi)

        // min_bg of 90 -> threshold of 65, 100 -> 70 110 -> 75, and 130 -> 85
        val threshold = min_bg - 0.5 * (min_bg - 40)
        // if (profile.lgsThreshold != null) {
        //     val lgsThreshold = profile.lgsThreshold ?: error("lgsThreshold missing")
        //     if (lgsThreshold > threshold) {
        //         consoleError.add("Threshold set from ${convert_bg(threshold)} to ${convert_bg(lgsThreshold.toDouble())}; ")
        //         threshold = lgsThreshold.toDouble()
        //     }
        // }

        //console.error(reservoir_data);

        rT = RT(
            algorithm = APSResult.Algorithm.AUTO_ISF,
            runningDynamicIsf = autoIsfMode,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            targetBG = target_bg,
            insulinReq = 0.0,
            deliverAt = deliverAt, // The time at which the microbolus should be delivered
            sensitivityRatio = sensitivityRatio, // autosens ratio (fraction of normal basal)
            consoleLog = consoleLog,
            consoleError = consoleError,
            variable_sens = profile.variable_sens
        )

        // generate predicted future BGs based on IOB, COB, and current absorption rate

        var COBpredBGs = mutableListOf<Double>()
        var aCOBpredBGs = mutableListOf<Double>()
        var IOBpredBGs = mutableListOf<Double>()
        var UAMpredBGs = mutableListOf<Double>()
        var ZTpredBGs = mutableListOf<Double>()
        COBpredBGs.add(bg)
        aCOBpredBGs.add(bg)
        IOBpredBGs.add(bg)
        ZTpredBGs.add(bg)
        UAMpredBGs.add(bg)

        //var enableSMB = if (autoIsfMode) microBolusAllowed else enable_smb(profile, microBolusAllowed, meal_data, target_bg) // pulled ahead for autoISF

        // enable UAM (if enabled in preferences)
        val enableUAM = profile.enableUAM

        //console.error(meal_data);
        // carb impact and duration are 0 unless changed below
        var ci: Double
        val cid: Double
        // calculate current carb absorption rate, and how long to absorb all carbs
        // CI = current carb impact on BG in mg/dL/5m
        ci = round((minDelta - bgi), 1)
        val uci = round((minDelta - bgi), 1)
        // ISF (mg/dL/U) / CR (g/U) = CSF (mg/dL/g)

        // TODO: remove commented-out code for old behavior
        //if (profile.temptargetSet) {
        // if temptargetSet, use unadjusted profile.sens to allow activity mode sensitivityRatio to adjust CR
        //var csf = profile.sens / profile.carb_ratio;
        //} else {
        // otherwise, use autosens-adjusted sens to counteract autosens meal insulin dosing adjustments
        // so that autotuned CR is still in effect even when basals and ISF are being adjusted by autosens
        //var csf = sens / profile.carb_ratio;
        //}
        // use autosens-adjusted sens to counteract autosens meal insulin dosing adjustments so that
        // autotuned CR is still in effect even when basals and ISF are being adjusted by TT or autosens
        // this avoids overdosing insulin for large meals when low temp targets are active
        val csf = sens / profile.carb_ratio
        consoleError.add("profile.sens: ${profile.sens}, sens: $sens, CSF: $csf")

        val maxCarbAbsorptionRate = 30 // g/h; maximum rate to assume carbs will absorb if no CI observed
        // limit Carb Impact to maxCarbAbsorptionRate * csf in mg/dL per 5m
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            consoleError.add("Limiting carb impact from $ci to $maxCI mg/dL/5m ( $maxCarbAbsorptionRate g/h )")
            ci = maxCI
        }
        var remainingCATimeMin = 3.0 // h; duration of expected not-yet-observed carb absorption
        // adjust remainingCATime (instead of CR) for autosens if sensitivityRatio defined
        remainingCATimeMin /= sensitivityRatio
        // 20 g/h means that anything <= 60g will get a remainingCATimeMin, 80g will get 4h, and 120g 6h
        // when actual absorption ramps up it will take over from remainingCATime
        val assumedCarbAbsorptionRate = 20 // g/h; maximum rate to assume carbs will absorb if no CI observed
        var remainingCATime = remainingCATimeMin
        if (meal_data.carbs != 0.0) {
            // if carbs * assumedCarbAbsorptionRate > remainingCATimeMin, raise it
            // so <= 90g is assumed to take 3h, and 120g=4h
            remainingCATimeMin = kotlin.math.max(remainingCATimeMin, meal_data.mealCOB / assumedCarbAbsorptionRate)
            val lastCarbAge = round((systemTime - meal_data.lastCarbTime) / 60000.0)
            //console.error(meal_data.lastCarbTime, lastCarbAge);

            val fractionCOBAbsorbed = (meal_data.carbs - meal_data.mealCOB) / meal_data.carbs
            remainingCATime = remainingCATimeMin + 1.5 * lastCarbAge / 60
            remainingCATime = round(remainingCATime, 1)
            //console.error(fractionCOBAbsorbed, remainingCATimeAdjustment, remainingCATime)
            consoleError.add("Last carbs " + lastCarbAge + "minutes ago; remainingCATime:" + remainingCATime + "hours;" + round(fractionCOBAbsorbed * 100) + "% carbs absorbed")
        }

        // calculate the number of carbs absorbed over remainingCATime hours at current CI
        // CI (mg/dL/5m) * (5m)/5 (m) * 60 (min/hr) * 4 (h) / 2 (linear decay factor) = total carb impact (mg/dL)
        val totalCI = kotlin.math.max(0.0, ci / 5 * 60 * remainingCATime / 2)
        // totalCI (mg/dL) / CSF (mg/dL/g) = total carbs absorbed (g)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int = min(90, profile.remainingCarbsCap) // default to 90
        var remainingCarbs = max(0.0, meal_data.mealCOB - totalCA)
        remainingCarbs = kotlin.math.min(remainingCarbsCap.toDouble(), remainingCarbs)
        // assume remainingCarbs will absorb in a /\ shaped bilinear curve
        // peaking at remainingCATime / 2 and ending at remainingCATime hours
        // area of the /\ triangle is the same as a remainingCIpeak-height rectangle out to remainingCATime/2
        // remainingCIpeak (mg/dL/5m) = remainingCarbs (g) * CSF (mg/dL/g) * 5 (m/5m) * 1h/60m / (remainingCATime/2) (h)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)
        //console.error(profile.min_5m_carbimpact,ci,totalCI,totalCA,remainingCarbs,remainingCI,remainingCATime);

        // calculate peak deviation in last hour, and slope from that to current deviation
        val slopeFromMaxDeviation = round(meal_data.slopeFromMaxDeviation, 2)
        // calculate lowest deviation in last hour, and slope from that to current deviation
        val slopeFromMinDeviation = round(meal_data.slopeFromMinDeviation, 2)
        // assume deviations will drop back down at least at 1/3 the rate they ramped up
        val slopeFromDeviations = kotlin.math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)
        //console.error(slopeFromMaxDeviation);

        val aci = 10
        //5m data points = g * (1U/10g) * (40mg/dL/1U) / (mg/dL/5m)
        // duration (in 5m data points) = COB (g) * CSF (mg/dL/g) / ci (mg/dL/5m)
        // limit cid to remainingCATime hours: the reset goes to remainingCI
        if (ci == 0.0) {
            // avoid divide by zero
            cid = 0.0
        } else {
            cid = min(remainingCATime * 60 / 5 / 2, kotlin.math.max(0.0, meal_data.mealCOB * csf / ci))
        }
        val acid = max(0.0, meal_data.mealCOB * csf / aci)
        // duration (hours) = duration (5m) * 5 / 60 * 2 (to account for linear decay)
        consoleError.add(element = "Carb Impact: ${ci} mg/dL per 5m; CI Duration: ${round(cid * 5 / 60 * 2, 1)} hours; remaining CI (~2h peak): ${round(remainingCIpeak, 1)} mg/dL per 5m")
        //console.error("Accel. Carb Impact:",aci,"mg/dL per 5m; ACI Duration:",round(acid*5/60*2,1),"hours");
        var minIOBPredBG = 999.0
        var minCOBPredBG = 999.0
        var minUAMPredBG = 999.0
        var minGuardBG: Double
        var minCOBGuardBG = 999.0
        var minUAMGuardBG = 999.0
        var minIOBGuardBG = 999.0
        var minZTGuardBG = 999.0
        var minPredBG: Double
        var avgPredBG: Double
        var IOBpredBG: Double = eventualBG
        var maxIOBPredBG = bg
        var maxCOBPredBG = bg
        //var maxUAMPredBG = bg
        //var maxPredBG = bg;
        //var eventualPredBG = bg
        val lastIOBpredBG: Double
        var lastCOBpredBG: Double? = null
        var lastUAMpredBG: Double? = null
        //var lastZTpredBG: Int
        var UAMduration = 0.0
        var remainingCItotal = 0.0
        val remainingCIs = mutableListOf<Int>()
        val predCIs = mutableListOf<Int>()
        var UAMpredBG: Double? = null
        var COBpredBG: Double? = null
        var aCOBpredBG: Double?
        iobArray.forEach { iobTick ->
            //console.error(iobTick);
            val predBGI: Double = round((-iobTick.activity * sens * 5), 2)
            val IOBpredBGI: Double = predBGI
            iobTick.iobWithZeroTemp ?: error("iobTick.iobWithZeroTemp missing")
            val predZTBGI = round((-iobTick.iobWithZeroTemp!!.activity * sens * 5), 2)
            val predUAMBGI = predBGI
            // for IOBpredBGs, predicted deviation impact drops linearly from current deviation down to zero
            // over 60 minutes (data points every 5m)
            val predDev: Double = ci * (1 - min(1.0, IOBpredBGs.size / (60.0 / 5.0)))
            IOBpredBG = IOBpredBGs[IOBpredBGs.size - 1] + IOBpredBGI + predDev
            // calculate predBGs with long zero temp without deviations
            val ZTpredBG = ZTpredBGs[ZTpredBGs.size - 1] + predZTBGI
            // for COBpredBGs, predicted carb impact drops linearly from current carb impact down to zero
            // eventually accounting for all carbs (if they can be absorbed over DIA)
            val predCI: Double = max(0.0, max(0.0, ci) * (1 - COBpredBGs.size / max(cid * 2, 1.0)))
            val predACI = max(0.0, max(0, aci) * (1 - COBpredBGs.size / max(acid * 2, 1.0)))
            // if any carbs aren't absorbed after remainingCATime hours, assume they'll absorb in a /\ shaped
            // bilinear curve peaking at remainingCIpeak at remainingCATime/2 hours (remainingCATime/2*12 * 5m)
            // and ending at remainingCATime h (remainingCATime*12 * 5m intervals)
            val intervals = kotlin.math.min(COBpredBGs.size.toDouble(), ((remainingCATime * 12) - COBpredBGs.size))
            val remainingCI = kotlin.math.max(0.0, intervals / (remainingCATime / 2 * 12) * remainingCIpeak)
            remainingCItotal += predCI + remainingCI
            remainingCIs.add(round(remainingCI))
            predCIs.add(round(predCI))
            //console.log(round(predCI,1)+"+"+round(remainingCI,1)+" ");
            COBpredBG = COBpredBGs[COBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predCI + remainingCI
            aCOBpredBG = aCOBpredBGs[aCOBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predACI
            // for UAMpredBGs, predicted carb impact drops at slopeFromDeviations
            // calculate predicted CI from UAM based on slopeFromDeviations
            val predUCIslope = max(0.0, uci + (UAMpredBGs.size * slopeFromDeviations))
            // if slopeFromDeviations is too flat, predicted deviation impact drops linearly from
            // current deviation down to zero over 3h (data points every 5m)
            val predUCImax = max(0.0, uci * (1 - UAMpredBGs.size / max(3.0 * 60 / 5, 1.0)))
            //console.error(predUCIslope, predUCImax);
            // predicted CI from UAM is the lesser of CI based on deviationSlope or DIA
            val predUCI = min(predUCIslope, predUCImax)
            if (predUCI > 0) {
                //console.error(UAMpredBGs.length,slopeFromDeviations, predUCI);
                UAMduration = round((UAMpredBGs.size + 1) * 5 / 60.0, 1)
            }
            UAMpredBG = UAMpredBGs[UAMpredBGs.size - 1] + predUAMBGI + min(0.0, predDev) + predUCI
            //console.error(predBGI, predCI, predUCI);
            // truncate all BG predictions at 4 hours
            if (IOBpredBGs.size < 48) IOBpredBGs.add(IOBpredBG)
            if (COBpredBGs.size < 48) COBpredBGs.add(COBpredBG)
            if (aCOBpredBGs.size < 48) aCOBpredBGs.add(aCOBpredBG)
            if (UAMpredBGs.size < 48) UAMpredBGs.add(UAMpredBG)
            if (ZTpredBGs.size < 48) ZTpredBGs.add(ZTpredBG)
            // calculate minGuardBGs without a wait from COB, UAM, IOB predBGs
            if (COBpredBG < minCOBGuardBG) minCOBGuardBG = round(COBpredBG).toDouble()
            if (UAMpredBG < minUAMGuardBG) minUAMGuardBG = round(UAMpredBG).toDouble()
            if (IOBpredBG < minIOBGuardBG) minIOBGuardBG = IOBpredBG
            if (ZTpredBG < minZTGuardBG) minZTGuardBG = round(ZTpredBG, 0)

            // set minPredBGs starting when currently-dosed insulin activity will peak
            // look ahead 60m (regardless of insulin type) so as to be less aggressive on slower insulins
            // add 30m to allow for insulin delivery (SMBs or temps)
            val insulinPeakTime = 90
            val insulinPeak5m = (insulinPeakTime / 60.0) * 12.0
            //console.error(insulinPeakTime, insulinPeak5m, profile.insulinPeakTime, profile.curve);

            // wait 90m before setting minIOBPredBG
            if (IOBpredBGs.size > insulinPeak5m && (IOBpredBG < minIOBPredBG)) minIOBPredBG = round(IOBpredBG, 0)
            if (IOBpredBG > maxIOBPredBG) maxIOBPredBG = IOBpredBG
            // wait 85-105m before setting COB and 60m for UAM minPredBGs
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBGs.size > insulinPeak5m && (COBpredBG < minCOBPredBG)) minCOBPredBG = round(COBpredBG, 0)
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBG > maxIOBPredBG) maxCOBPredBG = COBpredBG
            if (enableUAM && UAMpredBGs.size > 12 && (UAMpredBG < minUAMPredBG)) minUAMPredBG = round(UAMpredBG, 0)
            //if (enableUAM && UAMpredBG!! > maxIOBPredBG) maxUAMPredBG = UAMpredBG!!
        }
        // set eventualBG to include effect of carbs
        //console.error("PredBGs:",JSON.stringify(predBGs));
        if (meal_data.mealCOB > 0) {
            consoleError.add("predCIs (mg/dL/5m):" + predCIs.joinToString(separator = " "))
            consoleError.add("remainingCIs:      " + remainingCIs.joinToString(separator = " "))
        }
        rT.predBGs = Predictions()
        IOBpredBGs = IOBpredBGs.map { round(min(401.0, it.coerceAtLeastFinite(39.0)), 0) }.toMutableList()
        for (i in IOBpredBGs.size - 1 downTo 13) {
            if (IOBpredBGs[i - 1] != IOBpredBGs[i]) break
            else IOBpredBGs.removeAt(IOBpredBGs.lastIndex)
        }
        rT.predBGs?.IOB = IOBpredBGs.map { it.toInt() }
        lastIOBpredBG = round(IOBpredBGs[IOBpredBGs.size - 1]).toDouble()
        ZTpredBGs = ZTpredBGs.map { round(min(401.0, it.coerceAtLeastFinite(39.0)), 0) }.toMutableList()
        for (i in ZTpredBGs.size - 1 downTo 7) {
            // stop displaying ZTpredBGs once they're rising and above target
            if (ZTpredBGs[i - 1] >= ZTpredBGs[i] || ZTpredBGs[i] <= target_bg) break
            else ZTpredBGs.removeAt(ZTpredBGs.lastIndex)
        }
        rT.predBGs?.ZT = ZTpredBGs.map { it.toInt() }
        if (meal_data.mealCOB > 0) {
            aCOBpredBGs = aCOBpredBGs.map { round(min(401.0, it.coerceAtLeastFinite(39.0)), 0) }.toMutableList()
            for (i in aCOBpredBGs.size - 1 downTo 13) {
                if (aCOBpredBGs[i - 1] != aCOBpredBGs[i]) break
                else aCOBpredBGs.removeAt(aCOBpredBGs.lastIndex)
            }
        }
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            COBpredBGs = COBpredBGs.map { round(min(401.0, it.coerceAtLeastFinite(39.0)), 0) }.toMutableList()
            for (i in COBpredBGs.size - 1 downTo 13) {
                if (COBpredBGs[i - 1] != COBpredBGs[i]) break
                else COBpredBGs.removeAt(COBpredBGs.lastIndex)
            }
            rT.predBGs?.COB = COBpredBGs.map { it.toInt() }
            lastCOBpredBG = COBpredBGs[COBpredBGs.size - 1]
            eventualBG = max(eventualBG, round(COBpredBGs[COBpredBGs.size - 1], 0))
        }
        if (ci > 0 || remainingCIpeak > 0) {
            if (enableUAM) {
                UAMpredBGs = UAMpredBGs.map { round(min(401.0, it.coerceAtLeastFinite(39.0)), 0) }.toMutableList()
                for (i in UAMpredBGs.size - 1 downTo 13) {
                    if (UAMpredBGs[i - 1] != UAMpredBGs[i]) break
                    else UAMpredBGs.removeAt(UAMpredBGs.lastIndex)
                }
                rT.predBGs?.UAM = UAMpredBGs.map { it.toInt() }
                lastUAMpredBG = UAMpredBGs[UAMpredBGs.size - 1]
                eventualBG = max(eventualBG, round(UAMpredBGs[UAMpredBGs.size - 1], 0))
            }

            // set eventualBG based on COB or UAM predBGs
            rT.eventualBG = eventualBG
        }

        consoleError.add("UAM Impact: $uci mg/dL per 5m; UAM Duration: $UAMduration hours")
        consoleError.add("EventualBG is $eventualBG ;")

        // The predictions above are clamped to [39, 401], but a non-finite tick escapes that clamp and
        // ends up here. It must not go further: rT.eventualBG is stored and uploaded, and predBGs are
        // written with toInt(), where NaN becomes 0 - a 0 mg/dL predicted BG in the graph and in NS.
        if (!eventualBG.isFinite()) return abortNonFinite("eventualBG=$eventualBG", rT, currenttemp, basal, deliverAt)

        // coerceAtLeastFinite, not max(39.0, x): max propagates NaN, so it would not be a floor at all.
        minIOBPredBG = minIOBPredBG.coerceAtLeastFinite(39.0)
        minCOBPredBG = minCOBPredBG.coerceAtLeastFinite(39.0)
        minUAMPredBG = minUAMPredBG.coerceAtLeastFinite(39.0)
        minPredBG = round(minIOBPredBG, 0)

        // meal_data.carbs counts only carb entries inside the absorption window, while mealCOB comes
        // from autosens. So carbs can drop to 0 while mealCOB is still > 0 (slow or stalled absorption),
        // and mealCOB / 0 is Infinity. That made avgPredBG below Infinity - Infinity = NaN, and the NaN
        // spread to minPredBG -> insulinReq -> rate. Falling back to 0.0 keeps avgPredBG on the UAM
        // prediction, the same choice the no-carb branch of minGuardBG makes below.
        val fractionCarbsLeft = if (meal_data.carbs > 0.0) meal_data.mealCOB / meal_data.carbs else 0.0
        // if we have COB and UAM is enabled, average both
        if (minUAMPredBG < 999 && minCOBPredBG < 999) {
            // weight COBpredBG vs. UAMpredBG based on how many carbs remain as COB
            avgPredBG = round((1 - fractionCarbsLeft) * UAMpredBG!! + fractionCarbsLeft * COBpredBG!!, 0)
            // if UAM is disabled, average IOB and COB
        } else if (minCOBPredBG < 999) {
            avgPredBG = round((IOBpredBG + COBpredBG!!) / 2.0, 0)
            // if we have UAM but no COB, average IOB and UAM
        } else if (minUAMPredBG < 999) {
            avgPredBG = round((IOBpredBG + UAMpredBG!!) / 2.0, 0)
        } else {
            avgPredBG = round(IOBpredBG, 0)
        }
        // if avgPredBG is below minZTGuardBG, bring it up to that level
        if (minZTGuardBG > avgPredBG) {
            avgPredBG = minZTGuardBG
        }

        // if we have both minCOBGuardBG and minUAMGuardBG, blend according to fractionCarbsLeft
        if ((cid > 0.0 || remainingCIpeak > 0)) {
            if (enableUAM) {
                minGuardBG = fractionCarbsLeft * minCOBGuardBG + (1 - fractionCarbsLeft) * minUAMGuardBG
            } else {
                minGuardBG = minCOBGuardBG
            }
        } else if (enableUAM) {
            minGuardBG = minUAMGuardBG
        } else {
            minGuardBG = minIOBGuardBG
        }
        minGuardBG = round(minGuardBG, 0)
        // A non-finite minGuardBG is far worse than a wrong number. Every guard that reads it is a "<"
        // comparison, and those are false for NaN, so it would silently switch off both the SMB
        // suppression and the predictive low glucose suspend below - and the result would still look
        // finite afterwards, so nothing would ever report it. Stop the run instead of dosing on it.
        if (!minGuardBG.isFinite()) return abortNonFinite("minGuardBG=$minGuardBG", rT, currenttemp, basal, deliverAt)
        //console.error(minCOBGuardBG, minUAMGuardBG, minIOBGuardBG, minGuardBG);

        var minZTUAMPredBG = minUAMPredBG
        // if minZTGuardBG is below threshold, bring down any super-high minUAMPredBG by averaging
        // this helps prevent UAM from giving too much insulin in case absorption falls off suddenly
        if (minZTGuardBG < threshold) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
            // if minZTGuardBG is between threshold and target, blend in the averaging
        } else if (minZTGuardBG < target_bg) {
            // target 100, threshold 70, minZTGuardBG 85 gives 50%: (85-70) / (100-70)
            val blendPct = (minZTGuardBG - threshold) / (target_bg - threshold)
            val blendedMinZTGuardBG = minUAMPredBG * blendPct + minZTGuardBG * (1 - blendPct)
            minZTUAMPredBG = (minUAMPredBG + blendedMinZTGuardBG) / 2.0
            //minZTUAMPredBG = minUAMPredBG - target_bg + minZTGuardBG;
            // if minUAMPredBG is below minZTGuardBG, bring minUAMPredBG up by averaging
            // this allows more insulin if lastUAMPredBG is below target, but minZTGuardBG is still high
        } else if (minZTGuardBG > minUAMPredBG) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
        }
        minZTUAMPredBG = round(minZTUAMPredBG, 0)
        //console.error("minUAMPredBG:",minUAMPredBG,"minZTGuardBG:",minZTGuardBG,"minZTUAMPredBG:",minZTUAMPredBG);
        // if any carbs have been entered recently
        if (meal_data.carbs != 0.0) {

            // if UAM is disabled, use max of minIOBPredBG, minCOBPredBG
            if (!enableUAM && minCOBPredBG < 999) {
                minPredBG = round(max(minIOBPredBG, minCOBPredBG), 0)
                // if we have COB, use minCOBPredBG, or blendedMinPredBG if it's higher
            } else if (minCOBPredBG < 999) {
                // calculate blendedMinPredBG based on how many carbs remain as COB
                val blendedMinPredBG = fractionCarbsLeft * minCOBPredBG + (1 - fractionCarbsLeft) * minZTUAMPredBG
                // if blendedMinPredBG > minCOBPredBG, use that instead
                minPredBG = round(max(minIOBPredBG, max(minCOBPredBG, blendedMinPredBG)), 0)
                // if carbs have been entered, but have expired, use minUAMPredBG
            } else if (enableUAM) {
                minPredBG = minZTUAMPredBG
            } else {
                minPredBG = minGuardBG
            }
            // in pure UAM mode, use the higher of minIOBPredBG,minUAMPredBG
        } else if (enableUAM) {
            minPredBG = round(max(minIOBPredBG, minZTUAMPredBG), 0)
        }
        // make sure minPredBG isn't higher than avgPredBG
        minPredBG = min(minPredBG, avgPredBG)
        // Last resort backstop, same as DetermineBasalSMB. min() propagates NaN, and an unpinned
        // minPredBG would flow straight into insulinReq and rate.
        if (!minPredBG.isFinite()) minPredBG = 39.0

        consoleError.add("minPredBG: $minPredBG minIOBPredBG: $minIOBPredBG minZTGuardBG: $minZTGuardBG")
        if (minCOBPredBG < 999) {
            consoleError.add(" minCOBPredBG: $minCOBPredBG")
        }
        if (minUAMPredBG < 999) {
            consoleError.add(" minUAMPredBG: $minUAMPredBG")
        }
        consoleError.add(" avgPredBG: $avgPredBG COB: ${meal_data.mealCOB} / ${meal_data.carbs}")
        // But if the COB line falls off a cliff, don't trust UAM too much:
        // use maxCOBPredBG if it's been set and lower than minPredBG
        if (maxCOBPredBG > bg) {
            minPredBG = min(minPredBG, maxCOBPredBG)
        }

        rT.COB = meal_data.mealCOB
        rT.IOB = iob_data.iob
        rT.reason.append(
            "COB: ${round(meal_data.mealCOB, 1).withoutZeros()}, Dev: ${convert_bg(deviation.toDouble())}, BGI: ${convert_bg(bgi)}, ISF: ${convert_bg(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convert_bg(target_bg)}, minPredBG ${convert_bg(minPredBG)}, minGuardBG ${convert_bg(minGuardBG)}, IOBpredBG ${convert_bg(lastIOBpredBG)}"
        )
        if (lastCOBpredBG != null) {
            rT.reason.append(", COBpredBG " + convert_bg(lastCOBpredBG))
        }
        if (lastUAMpredBG != null) {
            rT.reason.append(", UAMpredBG " + convert_bg(lastUAMpredBG))
        }
        rT.reason.append("; ")
        // use naive_eventualBG if above 40, but switch to minGuardBG if both eventualBGs hit floor of 39
        var carbsReqBG = naive_eventualBG
        if (carbsReqBG < 40) {
            carbsReqBG = min(minGuardBG, carbsReqBG)
        }
        var bgUndershoot: Double = threshold - carbsReqBG
        // calculate how long until COB (or IOB) predBGs drop below min_bg
        var minutesAboveMinBG = 240
        var minutesAboveThreshold = 240
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            for (i in COBpredBGs.indices) {
                //console.error(COBpredBGs[i], min_bg);
                if (COBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in COBpredBGs.indices) {
                //console.error(COBpredBGs[i], threshold);
                if (COBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        } else {
            for (i in IOBpredBGs.indices) {
                //console.error(IOBpredBGs[i], min_bg);
                if (IOBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in IOBpredBGs.indices) {
                //console.error(IOBpredBGs[i], threshold);
                if (IOBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        }

        if (enableSMB && minGuardBG < threshold) {
            consoleError.add("minGuardBG ${convert_bg(minGuardBG)} projected below ${convert_bg(threshold)} - disabling SMB")
            //rT.reason += "minGuardBG "+minGuardBG+"<"+threshold+": SMB disabled; ";
            enableSMB = false
        }
        var maxDeltaPercentage = 0.2           // the AAPS default
        if (loop_wanted_smb == "fullLoop") {   // only if SMB specifically requested, e.g. for full loop
            maxDeltaPercentage = 0.3
        }
        if (maxDelta > maxDeltaPercentage * bg) {
            consoleError.add("maxDelta ${convert_bg(maxDelta)} > ${100 * maxDeltaPercentage}% of BG ${convert_bg(bg)} - disabling SMB")
            rT.reason.append("maxDelta " + convert_bg(maxDelta) + " > " + 100 * maxDeltaPercentage + "% of BG " + convert_bg(bg) + ": SMB disabled; ")
            enableSMB = false
        }

        consoleError.add("BG projected to remain above ${convert_bg(min_bg)} for $minutesAboveMinBG minutes")
        if (minutesAboveThreshold < 240 || minutesAboveMinBG < 60) {
            consoleError.add("BG projected to remain above ${convert_bg(threshold)} for $minutesAboveThreshold minutes")
        }
        // include at least minutesAboveThreshold worth of zero temps in calculating carbsReq
        // always include at least 30m worth of zero temp (carbs to 80, low temp up to target)
        val zeroTempDuration = minutesAboveThreshold
        // BG undershoot, minus effect of zero temps until hitting min_bg, converted to grams, minus COB
        val zeroTempEffectDouble = profile.current_basal * sens * zeroTempDuration / 60
        // don't count the last 25% of COB against carbsReq
        val COBforCarbsReq = max(0.0, meal_data.mealCOB - 0.25 * meal_data.carbs)
        val carbsReq = round(((bgUndershoot - zeroTempEffectDouble) / csf - COBforCarbsReq))
        val zeroTempEffect = round(zeroTempEffectDouble)
        consoleError.add("naive_eventualBG: $naive_eventualBG bgUndershoot: $bgUndershoot zeroTempDuration $zeroTempDuration zeroTempEffect: $zeroTempEffect carbsReq: $carbsReq")
        if (carbsReq >= profile.carbsReqThreshold && minutesAboveThreshold <= 45) {
            rT.carbsReq = carbsReq
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsReq add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }

        // don't low glucose suspend if IOB is already super negative and BG is rising faster than predicted
        if (bg < threshold && iob_data.iob < -profile.current_basal * 20 / 60 && minDelta > 0 && minDelta > expectedDelta) {
            rT.reason.append("IOB ${iob_data.iob} < ${round(-profile.current_basal * 20 / 60, 2)}")
            rT.reason.append(" and minDelta ${convert_bg(minDelta)} > expectedDelta ${convert_bg(expectedDelta)}; ")
            // predictive low glucose suspend mode: BG is / is projected to be < threshold
        } else if (bg < threshold || minGuardBG < threshold) {
            rT.reason.append("minGuardBG ${convert_bg(minGuardBG)} < ${convert_bg(threshold)}")
            bgUndershoot = target_bg - minGuardBG
            val worstCaseInsulinReq = bgUndershoot / sens
            var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)
            durationReq = round(durationReq / 30.0) * 30
            // always set a 30-120m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
            durationReq = min(120, max(30, durationReq))
            return setTempBasal(0.0, durationReq, profile, rT, currenttemp)
        }

        // if not in LGS mode, cancel temps before the top of the hour to reduce beeping/vibration
        // console.error(profile.skip_neutral_temps, rT.deliverAt.getMinutes());
        val minutes = Instant.fromEpochMilliseconds(rT.deliverAt!!).toLocalDateTime(TimeZone.currentSystemDefault()).minute
        if (profile.skip_neutral_temps && minutes >= 55) {
            rT.reason.append("; Canceling temp at " + minutes + "m past the hour. ")
            return setTempBasal(0.0, 0, profile, rT, currenttemp)
        }

        if (eventualBG < min_bg) { // if eventual BG is below target:
            rT.reason.append("Eventual BG ${convert_bg(eventualBG)} < ${convert_bg(min_bg)}")
            // if 5m or 30m avg BG is rising faster than expected delta
            if (minDelta > expectedDelta && minDelta > 0 && carbsReq == 0) {
                // if naive_eventualBG < 40, set a 30m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
                if (naive_eventualBG < 40) {
                    rT.reason.append(", naive_eventualBG < 40. ")
                    return setTempBasal(0.0, 30, profile, rT, currenttemp)
                }
                if (glucose_status.delta > minDelta) {
                    rT.reason.append(", but Delta ${convert_bg(tick.toDouble())} > expectedDelta ${convert_bg(expectedDelta)}")
                } else {
                    rT.reason.append(", but Min. Delta ${minDelta.toFixed2()} > Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(basal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }

            // calculate 30m low-temp required to get projected BG up to target
            // multiply by 2 to low-temp faster for increased hypo safety
            var insulinReq = 2 * min(0.0, (eventualBG - target_bg) / sens)
            insulinReq = round(insulinReq, 2)
            // calculate naiveInsulinReq based on naive_eventualBG
            var naiveInsulinReq = min(0.0, (naive_eventualBG - target_bg) / sens)
            naiveInsulinReq = round(naiveInsulinReq, 2)
            if (minDelta < 0 && minDelta > expectedDelta) {
                // if we're barely falling, newinsulinReq should be barely negative
                val newinsulinReq = round((insulinReq * (minDelta / expectedDelta)), 2)
                //console.error("Increasing insulinReq from " + insulinReq + " to " + newinsulinReq);
                insulinReq = newinsulinReq
            }
            // rate required to deliver insulinReq less insulin over 30m:
            var rate = basal + (2 * insulinReq)
            rate = round_basal(rate)

            // if required temp < existing temp basal
            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            // if current temp would deliver a lot (30% of basal) less than the required insulin,
            // by both normal and naive calculations, then raise the rate
            val minInsulinReq = kotlin.math.min(insulinReq, naiveInsulinReq)
            if (insulinScheduled < minInsulinReq - basal * 0.3) {
                rT.reason.append(", ${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} is a lot less than needed. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }
            if (currenttemp.duration > 5 && rate >= currenttemp.rate * 0.8) {
                rT.reason.append(", temp ${currenttemp.rate} ~< req ${round(rate, 2)}U/hr. ")
                return rT
            } else {
                // calculate a long enough zero temp to eventually correct back up to target
                if (rate <= 0) {
                    bgUndershoot = (target_bg - naive_eventualBG)
                    val worstCaseInsulinReq = bgUndershoot / sens
                    var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)
                    if (durationReq < 0) {
                        durationReq = 0
                        // don't set a temp longer than 120 minutes
                    } else {
                        durationReq = round(durationReq / 30.0) * 30
                        durationReq = min(120, max(0, durationReq))
                    }
                    //console.error(durationReq);
                    if (durationReq > 0) {
                        rT.reason.append(", setting ${durationReq}m zero temp. ")
                        return setTempBasal(rate, durationReq, profile, rT, currenttemp)
                    }
                } else {
                    rT.reason.append(", setting ${round(rate, 2)}U/hr. ")
                }
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }
        }

        // if eventual BG is above min but BG is falling faster than expected Delta
        if (minDelta < expectedDelta) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                if (glucose_status.delta < minDelta) {
                    rT.reason.append(
                        "Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Delta ${convert_bg(tick.toDouble())} < Exp. Delta ${
                            convert_bg(expectedDelta)
                        }"
                    )
                } else {
                    rT.reason.append("Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Min. Delta ${minDelta.toFixed2()} < Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(basal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }
        }
        // eventualBG or minPredBG is below max_bg
        if (min(eventualBG, minPredBG) < max_bg) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                rT.reason.append("${convert_bg(eventualBG)}-${convert_bg(minPredBG)} in range: no temp required")
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }
        }

        // eventual BG is at/above target
        // if iob is over max, just cancel any temps
        if (eventualBG >= max_bg) {
            rT.reason.append("Eventual BG " + convert_bg(eventualBG) + " >= " + convert_bg(max_bg) + ", ")
        }
        if (iob_data.iob > max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} > max_iob $max_iob")
            if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                return rT
            } else {
                rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                return setTempBasal(basal, 30, profile, rT, currenttemp)
            }
        } else { // otherwise, calculate 30m high-temp required to get projected BG down to target
            // insulinReq is the additional insulin required to get minPredBG down to target_bg
            //console.error(minPredBG,eventualBG);
            var insulinReq =
                // if (dynIsfMode) round((min(minPredBG, eventualBG) - target_bg) / future_sens, 2)
                round((min(minPredBG, eventualBG) - target_bg) / sens, 2)
            insulinReq = round(insulinReq * tddFactor, 2)
            max_iob = round(max_iob * tddFactor, 2)
            rT.reason.append("TDDfactor ${twoDecimals(tddFactor)} max_iob ${twoDecimals(max_iob)} insulinReq ${twoDecimals(insulinReq)}. ")
            // if that would put us over max_iob, then reduce accordingly
            if (insulinReq > max_iob - iob_data.iob) {
                rT.reason.append("max_iob $max_iob, ")
                insulinReq = max_iob - iob_data.iob
            }

            // rate required to deliver insulinReq more insulin over 30m:
            var rate = basal + (2 * insulinReq)
            rate = round_basal(rate)
            insulinReq = round(insulinReq, 3)
            rT.insulinReq = insulinReq
            //console.error(iob_data.lastBolusTime);
            //console.error(profile.temptargetSet, target_bg, rT.COB);
            // only allow microboluses with COB or low temp targets, or within DIA hours of a bolus
            val maxBolus: Double
            if (microBolusAllowed && enableSMB && bg > threshold) {
                // never bolus more than maxSMBBasalMinutes worth of basal
                val mealInsulinReq = round(meal_data.mealCOB / profile.carb_ratio, 3)
                val smb_max_range = smb_max_range_extension
                if (iob_data.iob > mealInsulinReq && iob_data.iob > 0) {
                    consoleError.add("IOB ${iob_data.iob} > COB ${meal_data.mealCOB}; mealInsulinReq = $mealInsulinReq")
                    consoleError.add("profile.maxUAMSMBBasalMinutes: ${profile.maxUAMSMBBasalMinutes} profile.current_basal: ${profile.current_basal}")
                    maxBolus = round(smb_max_range * profile.current_basal * profile.maxUAMSMBBasalMinutes / 60, 1)
                } else {
                    consoleError.add("profile.maxSMBBasalMinutes: ${profile.maxSMBBasalMinutes} profile.current_basal: ${profile.current_basal}")
                    maxBolus = round(smb_max_range * profile.current_basal * profile.maxSMBBasalMinutes / 60, 1)
                }
                // bolus 1/2 the insulinReq, up to maxBolus, rounding down to nearest bolus increment
                val roundSMBTo = 1 / profile.bolus_increment
                //var microBolus: Double
                var microBolus = kotlin.math.floor(kotlin.math.min(insulinReq / 2, maxBolus) * roundSMBTo) / roundSMBTo
                if (autoIsfMode) {
                    microBolus = kotlin.math.min(insulinReq * smb_ratio, maxBolus)
                    if (microBolus > iobTHvirtual - iob_data.iob && (loop_wanted_smb == "fullLoop" || loop_wanted_smb == "enforced")) {
                        microBolus = iobTHvirtual - iob_data.iob
                        consoleError.add("Full loop capped SMB at ${round(microBolus, 2)} to not exceed $iobTHtolerance% of effective iobTH ${round(iobTHvirtual / iobTHtolerance * 100, 2)}U")
                    }
                    microBolus = kotlin.math.floor(microBolus * roundSMBTo) / roundSMBTo
                }
                if (fastRiseSettingOn != null) {
                    val nowHour = if (hour in 0..23) hour
                    else Instant.fromEpochMilliseconds(currentTime).toLocalDateTime(TimeZone.currentSystemDefault()).hour
                    val loReb = loRebWindow(
                        bg = bg,
                        delta = glucose_status.delta,
                        shortDelta = glucose_status.shortAvgDelta,
                        systemTimeMs = systemTime,
                        lastAlarmHypoAtMs = lastAlarmHypoAt,
                        cob = meal_data.mealCOB,
                        uci = 0.0,
                        csf = 1.0,
                        recentLowReboundGuardEnabled = lowReboundGuardEnabled,
                    )
                    val slope = if (fastRiseSlopeRatio > 0.0) fastRiseSlopeRatio else 1.0
                    val morningAndGlitch = morningThenGlitch(
                        showerInput = ShowerTwilightInput(
                            hour = nowHour,
                            bg = bg,
                            steps60 = steps60,
                            cob = meal_data.mealCOB,
                            tempTargetSet = profile.temptargetSet,
                            delta = glucose_status.delta / slope,
                            shortDelta = glucose_status.shortAvgDelta / slope,
                            iobThUser = iobThUser,
                            rawDelta5 = rawDelta5Mgdl,
                            aapsDelta1 = aapsDelta1Mgdl,
                            microBolus = microBolus,
                            iob = iob_data.iob,
                            maxIob = profile.max_iob,
                        ),
                        glitchInput = SensorGlitchInput(
                            bgAcceleration = bgAcceleration,
                            delta = glucose_status.delta / slope,
                            shortDelta = glucose_status.shortAvgDelta / slope,
                            longDelta = glucose_status.longAvgDelta / slope,
                            iob = iob_data.iob,
                            cob = meal_data.mealCOB,
                            bg = bg,
                            hour = nowHour,
                            tempTargetSet = profile.temptargetSet,
                            targetBg = target_bg,
                            rawDelta5 = rawDelta5Mgdl,
                            aapsDelta1 = aapsDelta1Mgdl,
                            microBolus = microBolus,
                        ),
                    )
                    microBolus = morningAndGlitch.microBolus
                    if (morningAndGlitch.reason.isNotEmpty()) rT.reason.append(morningAndGlitch.reason)
                    val adjusted = fastRiseAdjustedMicroBolus(
                        microBolus = microBolus,
                        roundSmbTo = roundSMBTo,
                        loReb = loReb,
                        input = FastRiseSizeInput(
                            bg = bg,
                            delta = glucose_status.delta,
                            shortDelta = glucose_status.shortAvgDelta,
                            longDelta = glucose_status.longAvgDelta,
                            rawDelta5 = rawDelta5Mgdl,
                            aapsDelta1 = aapsDelta1Mgdl,
                            cob = meal_data.mealCOB,
                            iob = iob_data.iob,
                            maxIob = profile.max_iob,
                            microBolus = microBolus,
                            threshold = tddFactor * 0.030 * profile.max_iob,
                            hour = nowHour,
                            slopeRatio = fastRiseSlopeRatio,
                            libreActive = libreActive,
                            tempTargetSet = profile.temptargetSet,
                            fastRiseSettingOn = fastRiseSettingOn,
                        ),
                    )
                    microBolus = adjusted.microBolus
                    if (adjusted.reason.isNotEmpty()) rT.reason.append(adjusted.reason)
                    val minuteOfDay = Instant.fromEpochMilliseconds(currentTime)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .let { it.hour * 60 + it.minute }
                    val fastRiseNow = libreActive &&
                        bg > 6.0 * 18.0 &&
                        bg < 12.0 * 18.0 &&
                        meal_data.mealCOB <= 25.0 &&
                        glucose_status.delta >= 0.25 * 18.0 &&
                        glucose_status.shortAvgDelta >= 0.10 * 18.0 &&
                        rawDelta5Mgdl >= 0.25 * 18.0 &&
                        aapsDelta1Mgdl >= 0.25 * 18.0
                    val after = afterFastRiseSmb(
                        AfterFastRiseInput(
                            tier = adjusted.tier,
                            libreActive = libreActive,
                            tempTargetSet = profile.temptargetSet,
                            microBolus = microBolus,
                            roundSmbTo = roundSMBTo,
                            earlyMorning = EarlyMorningTwilightInput(
                                hour = nowHour,
                                iob = iob_data.iob,
                                maxIob = profile.max_iob,
                                cob = meal_data.mealCOB,
                                immediateRawDelta5 = immediateRawDelta5Mgdl,
                                rawDelta15 = rawDelta15Mgdl,
                                bg = bg,
                                delta = glucose_status.delta / slope,
                                shortDelta = glucose_status.shortAvgDelta / slope,
                                steps60 = steps60,
                                microBolus = microBolus,
                            ),
                            threshold = tddFactor * 0.030 * profile.max_iob,
                            steps30 = steps30,
                            steps60 = steps60,
                            steps180 = steps180,
                            loRebActive = loReb.active,
                            loRebGuardEnabled = lowReboundGuardEnabled,
                            fastRiseNow = fastRiseNow,
                            iob = iob_data.iob,
                            maxIob = profile.max_iob,
                            cob = meal_data.mealCOB,
                            smbSum30 = smbSum30Min,
                            smbSum10 = smbSum10Min,
                            minuteOfDay = minuteOfDay,
                            bg = bg,
                        )
                    )
                    microBolus = after.microBolus
                    if (after.reason.isNotEmpty()) rT.reason.append(after.reason)
                }

                // calculate a long enough zero temp to eventually correct back up to target
                val smbTarget = target_bg
                val worstCaseInsulinReq = (smbTarget - (naive_eventualBG + minIOBPredBG) / 2.0) / sens
                var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)

                // if insulinReq > 0 but not enough for a microBolus, don't set an SMB zero temp
                if (insulinReq > 0 && microBolus < profile.bolus_increment) {
                    durationReq = 0
                }

                var smbLowTempReq = 0.0
                if (durationReq <= 0) {
                    durationReq = 0
                    // don't set an SMB zero temp longer than 60 minutes
                } else if (durationReq >= 30) {
                    durationReq = round(durationReq / 30.0) * 30
                    durationReq = min(60, max(0, durationReq))
                } else {
                    // if SMB durationReq is less than 30m, set a nonzero low temp
                    //
                    // durationReq is how many minutes of basal we want to hold back. The pump gets a
                    // 30 minute temp instead of a shorter zero temp, so the rate must deliver what is
                    // left over: full basal for the other 30 - durationReq minutes, spread across the
                    // whole 30.
                    //
                    // oref has this the other way round (basal * durationReq / 30). That gets it
                    // backwards: asking to hold back 29 of 30 minutes produced 29/30 of the basal
                    // rate, so almost nothing was held back, and asking to hold back 1 minute cut the
                    // rate to 1/30. We deliberately differ from oref here. See issue #5082.
                    smbLowTempReq = round(basal * (30 - durationReq) / 30.0, 2)
                    durationReq = 30
                }
                rT.reason.append(" insulinReq $insulinReq")
                if (microBolus >= maxBolus) {
                    rT.reason.append("; maxBolus $maxBolus")
                }
                if (durationReq > 0) {
                    rT.reason.append("; setting ${durationReq}m low temp of ${smbLowTempReq}U/h")
                }
                rT.reason.append(". ")

                // seconds since last bolus
                val lastBolusAge = (systemTime - iob_data.lastBolusTime) / 1000.0
                //console.error(lastBolusAge);
                // allow SMBIntervals between 1 and 10 minutes
                val SMBInterval = min(10, max(1, profile.SMBInterval)) * 60.0   // in seconds
                //console.error(naive_eventualBG, insulinReq, worstCaseInsulinReq, durationReq);
                consoleError.add("naive_eventualBG $naive_eventualBG,${durationReq}m ${smbLowTempReq}U/h temp needed; last bolus ${round(lastBolusAge / 60.0, 1)}m ago; maxBolus: $maxBolus")
                if (lastBolusAge > SMBInterval - 6.0) {   // 6s tolerance
                    if (microBolus > 0) {
                        rT.units = microBolus
                        rT.reason.append("Microbolusing ${microBolus}U. ")
                    }
                } else {
                    val nextBolusMins = (SMBInterval - lastBolusAge) / 60.0
                    val nextBolusSeconds = (SMBInterval - lastBolusAge) % 60
                    val waitingSeconds = round(nextBolusSeconds, 0) % 60
                    val waitingMins = round(nextBolusMins - waitingSeconds / 60.0, 0)
                    rT.reason.append("Waiting ${waitingMins.withoutZeros()}m ${waitingSeconds.withoutZeros()}s to microbolus again.")
                }
                //rT.reason += ". ";

                // if no zero temp is required, don't return yet; allow later code to set a high temp
                if (durationReq > 0) {
                    rT.rate = smbLowTempReq
                    rT.duration = durationReq
                    return rT
                }

            }

            val maxSafeBasal = getMaxSafeBasal(profile)

            if (rate > maxSafeBasal) {
                rT.reason.append("adj. req. rate: ${round(rate, 2)} to maxSafeBasal: ${maxSafeBasal.withoutZeros()}, ")
                rate = round_basal(maxSafeBasal)
            }

            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            if (insulinScheduled >= insulinReq * 2) { // if current temp would deliver >2x more than the required insulin, lower the rate
                rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} > 2 * insulinReq. Setting temp basal of ${round(rate, 2)}U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration == 0) { // no temp is set
                rT.reason.append("no temp, setting " + round(rate, 2).withoutZeros() + "U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration > 5 && (round_basal(rate) <= round_basal(currenttemp.rate))) { // if required temp <~ existing temp basal
                rT.reason.append("temp ${(currenttemp.rate).toFixed2()} >~ req ${round(rate, 2).withoutZeros()}U/hr. ")
                return rT
            }

            // required temp > existing temp basal
            rT.reason.append("temp ${currenttemp.rate.toFixed2()} < ${round(rate, 2).withoutZeros()}U/hr. ")
            return setTempBasal(rate, 30, profile, rT, currenttemp)
        }
    }
}
