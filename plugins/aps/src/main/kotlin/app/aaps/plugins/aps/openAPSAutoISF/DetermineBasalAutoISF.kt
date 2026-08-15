package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.interfaces.Preferences
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Singleton
class DetermineBasalAutoISF @Inject constructor(
    private val profileUtil: ProfileUtil
) {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var profileFunction: ProfileFunction

    // TDD-based ratio passed from OpenAPSAutoISFPlugin via class-level properties (Option 3)
    var tddRatio: Double = 1.0
    var tdd7D: Double = 0.0

    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))

    fun round_basal(value: Double): Double = value

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    fun round(value: Double): Int = value.roundToInt()

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
        String.format(Locale.US, "%.1f", profileUtil.fromMgdlToUnits(value)).replace("-0.0", "0.0")

    fun convert_bg2(value: Double): String =
        String.format(Locale.US, "%.2f", profileUtil.fromMgdlToUnits(value)).replace("-0.00", "0.00")

    private fun basalForDisplay(value: Double): String = String.format(Locale.US, "%.1f", value)

    fun convert_isf(value: Double): String =
        String.format("%.1f", profileUtil.fromMgdlToUnits(value))

    // Not yet called anywhere; ready for later conditions that need to branch on the active profile.
    private fun currentProfileName(): String = profileFunction.getProfileName()

    fun enable_smb(profile: OapsProfileAutoIsf, microBolusAllowed: Boolean, meal_data: MealData, target_bg: Double): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && target_bg > 100) {
            consoleError.add("SMB disabled due to high temptarget of ${convert_bg(target_bg)}")
            return false
        }

        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (profile.enableSMB_always) {
            consoleError.add("SMB enabled due to enableSMB_always")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) while we have COB
        if (profile.enableSMB_with_COB && meal_data.mealCOB != 0.0) {
            consoleError.add("SMB enabled for COB of ${round(meal_data.mealCOB, 1)}")
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
        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate
        if (rate < 0) rate = 0.0
        else if (rate > maxSafeBasal) rate = maxSafeBasal

        val suggestedRate = round_basal(rate)
        if (currenttemp.duration > (duration - 10) && currenttemp.duration <= 120 && suggestedRate <= currenttemp.rate * 1.2 && suggestedRate >= currenttemp.rate * 0.8 && duration > 0) {
            rT.reason.append(" ${currenttemp.duration}m left and ${basalForDisplay(currenttemp.rate)} ~ req ${basalForDisplay(suggestedRate)}U/hr: no temp required")
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
                reason(rT, "Setting neutral temp basal of ${basalForDisplay(profile.current_basal)}U/hr")
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

    fun isEven(value: Double): Boolean =
        if (value % 1 == 0.0) value.toInt() % 2 == 0          // whole number: check integer
        else (value * 10).roundToInt() % 2 == 0                // decimal: check first decimal digit

    fun determine_basal(
        glucose_status: GlucoseStatus, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfileAutoIsf, autosens_data: AutosensResult, meal_data: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, autoIsfMode: Boolean, loop_wanted_smb: String, profile_percentage: Int, smb_ratio: Double,
        smb_max_range_extension: Double, iob_threshold_percent: Int, activity_consoleLog: String, auto_isf_consoleError: MutableList<String>, auto_isf_consoleLog: MutableList<String>,
        bg_acce: Double,
        steps180M: Int,
        steps15M: Int,
        steps5M: Int,
        smbInt5Sec: Double = 9999.0,  // avg secs between SMBs over last 5 min; <=70 = rapid stacking. Default 9999 = no stacking
        smbBoostRecent: Boolean = false,   // BolusGiven bg3 / BolusGivenMild fired within 30 min -> skip fast-rise caps
        // Raw/AAPS-processed 1-min and raw 5-min deltas (mg/dL, already per-5-min-rate normalised), used
        // as extra AND confirmations on the fast-rise capping blocks' own Delta gate (entry point only —
        // not the nested severity tiers). Default 9999.0 = "no data supplied" -> the AND-term is trivially
        // satisfied, so a caller that doesn't pass these (tests, replay) sees unchanged behaviour, and a
        // momentary sensor gap can't itself block a cap the original Delta/SDelta logic would have applied.
        rawDelta5Mgdl: Double = 9999.0,
        // Always-unsmoothed companion used only by checks whose purpose is to see a turn before UKF.
        // All other SMB confirmations use rawDelta5Mgdl, selected by the LibreUKF toggle upstream.
        immediateRawDelta5Mgdl: Double = 9999.0,
        rawDelta1Mgdl: Double = 9999.0,
        aapsDelta1Mgdl: Double = 9999.0,
        // Raw 15-min delta (mg/dL, normalised to a per-5-min rate to match rawDelta5Mgdl's scale — same
        // /3 convention AutoIsfHistoryExporter's rΔ15 table column uses). Default 9999.0 = "no data" —
        // only consumed by the early-AM raw-rise guard below, whose own check treats this sentinel as
        // "skip the rΔ15 corroboration, fall back to rΔ5 alone" rather than "fail the whole condition".
        rawDelta15Mgdl: Double = 9999.0,
        // True when the LowBG automation state is "50recent" -- i.e. a low happened recently and hasn't
        // been cleared yet. Read from the SAME state BolusWizard already uses to halve carb insulin, so
        // both dosing paths now respond to one shared, already-maintained signal rather than each having
        // its own notion of "recent low". Default false = unchanged behaviour for callers that don't
        // pass it (tests, replay), consistent with the other optional params above.
        recentLowActive: Boolean = false,
        // Total units of SMB delivered in the last 10 min (see smbSum10Min() in OpenAPSAutoISFPlugin.kt).
        // Default 0.0 = "no data supplied" -> the cumulative cap below is trivially satisfied and
        // behaviour is unchanged for callers that don't pass it (tests, replay), same convention as the
        // other optional params above.
        smbSum10Min: Double = 0.0,
        // Total units of SMB delivered in the last 30 min. Used by the late FastRise taper and the
        // final 30-min cumulative cap below. Default 0.0 preserves existing callers/tests.
        smbSum30Min: Double = 0.0,
        // True for a fixed 10-min window once smbSum10Min() has exceeded 1.0U while glucose was under
        // 7.5mmol -- see Sub75HeavyDelivery in OpenAPSAutoISFPlugin.kt. A hard cooldown, not another
        // rolling-window amount cap: added 2026-08-14 after two real hypos (4.4mmol, sustained 4.5mmol)
        // where ordinary-ratio SMB kept flowing on a still-high acce-ISF term for 10+ minutes despite
        // substantial insulin already delivered -- the existing smbSum10Min/30Min caps below eventually
        // stopped both, but only after enough had already gone in to overshoot the eventual (modest)
        // peak BG's real need. A rolling amount cap alone doesn't fix that: as soon as the oldest minute
        // ages out of its own window, headroom reopens even though nothing has actually caught up with
        // the insulin already in flight. This forces a genuine pause instead, giving already-delivered
        // insulin time to actually show up in BG before more goes in. Default false preserves existing
        // callers/tests, same convention as the other optional params above.
        sub75HeavyDeliveryCooldown: Boolean = false
    ): RT {
        consoleError.clear()
        consoleError.add(activity_consoleLog)
        consoleLog.clear()
        var rT = RT(
            algorithm = APSResult.Algorithm.AUTO_ISF,
            runningAutoIsf = true,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )

        val deliverAt = currentTime
        val profile_current_basal = round_basal(profile.current_basal)
        var basal = profile_current_basal
        val systemTime = currentTime
        val bgTime = glucose_status.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        val bg = glucose_status.glucose
        val noise = glucose_status.noise
        if (bg <= 10 || bg == 38.0 || noise >= 3) {
            rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
        }
        if (minAgo > 12 || minAgo < -5) {
            rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read ${minAgo}m ago at $bgTime")
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append("Error: CGM data is unchanged for the past ~45m")
        }
        if (bg <= 10 || bg == 38.0 || noise >= 3 || minAgo > 12 || minAgo < -5 || (bg > 60 && flatBGsDetected)) {
            if (currenttemp.rate > basal) {
                rT.reason.append(". Replacing high temp basal of ${basalForDisplay(currenttemp.rate)} with neutral temp of ${basalForDisplay(basal)}")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = basal
                return rT
            } else if (currenttemp.rate == 0.0 && currenttemp.duration > 30) {
                rT.reason.append(". Shortening " + currenttemp.duration + "m long zero temp to 30m. ")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = 0.0
                return rT
            } else {
                rT.reason.append(". Temp ${basalForDisplay(currenttemp.rate)} <= current basal ${basalForDisplay(basal)}U/hr; doing nothing. ")
                return rT
            }
        }

        var max_iob = profile.max_iob
        val iobThUser = profile.iob_threshold_percent
        if (iobThUser == 95) {
            max_iob = max_iob * 1.2
        } else if (iobThUser == 96) {
            max_iob = max_iob * 1.5
        } else if (iobThUser == 97) {
            max_iob = max_iob * 2.0
        }

        var target_bg = (profile.min_bg + profile.max_bg) / 2
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg
        val target_bgOrigmm: Double = convert_bg(target_bg).toDouble()
        val activityRatio = preferences.get(DoubleKey.ActivityMonitorRatio)
        val stepActivityDetected = preferences.get(BooleanKey.ActivityMonitorStepsActive)
        val stepInactivityDetected = preferences.get(BooleanKey.ActivityMonitorStepsInactive)
        var sensitivityRatio = 1.0
        val normalTarget = 100
        val exerciseModeActive = (profile.exercise_mode || profile.high_temptarget_raises_sensitivity) && profile.temptargetSet && target_bg > normalTarget
        val resistanceModeActive = profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && target_bg < normalTarget
        val mgdlHalfBasalTarget = profile.half_basal_exercise_target * if (profile.out_units == "mmol/L") GlucoseUnit.MMOLL_TO_MGDL else 1.0
        if (exerciseModeActive || resistanceModeActive || stepActivityDetected || stepInactivityDetected) {
            if (exerciseModeActive || resistanceModeActive) {
                val resistanceMax = min(1.5, profile.autosens_max)
                val c = (mgdlHalfBasalTarget - normalTarget).toDouble()
                if (c * (c + target_bg - normalTarget) <= 0.0) {
                    sensitivityRatio = resistanceMax
                } else {
                    sensitivityRatio = c / (c + target_bg - normalTarget)
                    sensitivityRatio = min(sensitivityRatio, resistanceMax)
                    sensitivityRatio = round(sensitivityRatio, 2)
                }
                consoleError.add("Sensitivity ratio set to ${round(sensitivityRatio, 2)} based on temp target of ${convert_bg(target_bg)}; ")
            } else if (stepActivityDetected) {
                sensitivityRatio = activityRatio
            } else if (stepInactivityDetected) {
                sensitivityRatio = activityRatio
            }
        } else {
            consoleError.add("Sensitivity ratio unchanged: 1.0")
            sensitivityRatio = autosens_data.ratio
            consoleError.add("Autosens ratio: ${round(sensitivityRatio, 2)}; ")
        }
        var iobTH_reduction_ratio = 1.0
        if (iob_threshold_percent != 100) {
            iobTH_reduction_ratio = profile_percentage / 100.0 * sensitivityRatio
        }
        basal = profile.current_basal * sensitivityRatio
        basal = round_basal(basal)
        if (basal != profile_current_basal)
            consoleError.add("adjusting basal from ${basalForDisplay(profile_current_basal)} to ${basalForDisplay(basal)};")
        else
            consoleError.add("Basal unchanged: ${basalForDisplay(basal)};")

        if (profile.temptargetSet) {
            // temp target set, not adjusting with autosens
        } else {
            if (profile.sensitivity_raises_target && autosens_data.ratio < 1 || profile.resistance_lowers_target && autosens_data.ratio > 1) {
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                var new_target_bg = round((target_bg - 60) / autosens_data.ratio, 0) + 60
                new_target_bg = max(80.0, new_target_bg)
                if (target_bg == new_target_bg)
                    consoleError.add("target_bg unchanged: ${convert_bg(new_target_bg)}; ")
                else
                    consoleError.add("target_bg from ${convert_bg(target_bg)} to ${convert_bg(new_target_bg)}; ")
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
            consoleError.add("ISF from ${convert_isf(profile_sens)} to ${convert_isf(adjusted_sens)}")
        } else {
            consoleError.add("ISF unchanged: ${convert_isf(adjusted_sens)}")
        }
        val sens =
            if (autoIsfMode) {
                profile.variable_sens
            } else {
                adjusted_sens
            }
        consoleError.add("CR: ${round(profile.carb_ratio, 2)}")

        if (autoIsfMode) {
            consoleError.add("----------------------------------")
            consoleError.add("start AutoISF ${profile.autoISF_version} __ aisf321_593")
            consoleError.add("----------------------------------")
            consoleError.add("Sensitivity: ${autosens_data.sensResult}")
            consoleError.addAll(auto_isf_consoleLog)
            consoleError.addAll(auto_isf_consoleError)
        }
        var TDDfactor = preferences.get(DoubleKey.ApsAutoIsfTddFactorFallback)
        if (preferences.get(BooleanKey.ApsAutoIsfTddFactor)) {
            TDDfactor = min(1.2, max(0.80, tddRatio))
            consoleError.add("TDDfactor ${round(TDDfactor, 2)} from tddRatio ${round(tddRatio, 2)} (tdd7D ${round(tdd7D, 1)}U)")
            rT.reason.append("TDDfactor ${round(TDDfactor, 2)} from tddRatio ${round(tddRatio, 2)} (tdd7D ${round(tdd7D, 1)}U)")
        }
        TDDfactor = round(TDDfactor, 2)
        val iobTHtolerance = 130.0
        val iobTHvirtual = iob_threshold_percent * iobTHtolerance / 10000.0 * profile.max_iob * iobTH_reduction_ratio
        var enableSMB = false
        if (microBolusAllowed && loop_wanted_smb != "AAPS") {
            if (loop_wanted_smb == "enforced" || loop_wanted_smb == "fullLoop") {
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

        val bgi = round((-iob_data.activity * sens * 5), 2)
        var deviation = round(30 / 5 * (minDelta - bgi))
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucose_status.longAvgDelta - bgi))
            }
        }

        val naive_eventualBG =
            if (autoIsfMode)
                round(bg - (iob_data.iob * sens), 0)
            else {
                if (iob_data.iob > 0) round(bg - (iob_data.iob * sens), 0)
                else round(bg - (iob_data.iob * min(sens, profile.sens)), 0)
            }
        var eventualBG = naive_eventualBG + deviation

        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, target_bg - (bg - target_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)
            if (eventualBG > adjustedMinBG && naive_eventualBG > adjustedMinBG && min_bg > adjustedMinBG) {
                consoleError.add("Adjusting targets for high BG: min_bg from ${convert_bg(min_bg)} to ${convert_bg(adjustedMinBG)}; ")
                min_bg = adjustedMinBG
            } else {
                consoleError.add("min_bg unchanged: ${convert_bg(min_bg)}; ")
            }
            if (eventualBG > adjustedTargetBG && naive_eventualBG > adjustedTargetBG && target_bg > adjustedTargetBG) {
                consoleError.add("target_bg from ${convert_bg(target_bg)} to ${convert_bg(adjustedTargetBG)}; ")
                target_bg = adjustedTargetBG
            } else {
                consoleError.add("target_bg unchanged: ${convert_bg(target_bg)}; ")
            }
            if (eventualBG > adjustedMaxBG && naive_eventualBG > adjustedMaxBG && max_bg > adjustedMaxBG) {
                consoleError.add("max_bg from ${convert_bg(max_bg)} to ${convert_bg(adjustedMaxBG)}")
                max_bg = adjustedMaxBG
            } else {
                consoleError.add("max_bg unchanged: ${convert_bg(max_bg)}")
            }
        }

        val expectedDelta = calculate_expected_delta(target_bg, eventualBG, bgi)
        val threshold = min_bg - 0.5 * (min_bg - 40)

        rT = RT(
            algorithm = APSResult.Algorithm.AUTO_ISF,
            runningAutoIsf = true,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            targetBG = target_bg,
            insulinReq = 0.0,
            deliverAt = deliverAt,
            sensitivityRatio = sensitivityRatio,
            consoleLog = consoleLog,
            consoleError = consoleError,
            variable_sens = profile.variable_sens
        )

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

        val enableUAM = profile.enableUAM

        var ci: Double = round((minDelta - bgi), 1)
        var cid: Double = 0.0
        val uci = round((minDelta - bgi), 1)

        val csf = sens / profile.carb_ratio
        consoleError.add("profile.sens: ${convert_isf(profile.sens)}, sens: ${convert_isf(sens)}, CSF: ${round(csf, 2)}")

        val maxCarbAbsorptionRate = 30
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            consoleError.add("Limiting carb impact from $ci to $maxCI mg/dL/5m ( $maxCarbAbsorptionRate g/h )")
            ci = maxCI
        }
        var remainingCATimeMin = 3.0
        remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        val assumedCarbAbsorptionRate = 20
        var remainingCATime = remainingCATimeMin
        if (meal_data.carbs != 0.0) {
            remainingCATimeMin = Math.max(remainingCATimeMin, meal_data.mealCOB / assumedCarbAbsorptionRate)
            val lastCarbAge = round((systemTime - meal_data.lastCarbTime) / 60000.0)
            val fractionCOBAbsorbed = (meal_data.carbs - meal_data.mealCOB) / meal_data.carbs
            remainingCATime = remainingCATimeMin + 1.5 * lastCarbAge / 60
            remainingCATime = round(remainingCATime, 1)
            consoleError.add("Last carbs " + lastCarbAge + "minutes ago; remainingCATime:" + remainingCATime + "hours;" + round(fractionCOBAbsorbed * 100) + "% carbs absorbed")
        }

        val totalCI = Math.max(0.0, ci / 5 * 60 * remainingCATime / 2)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int
        remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, meal_data.mealCOB - totalCA)
        remainingCarbs = Math.min(remainingCarbsCap.toDouble(), remainingCarbs)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)

        val slopeFromMaxDeviation = round(meal_data.slopeFromMaxDeviation, 2)
        val slopeFromMinDeviation = round(meal_data.slopeFromMinDeviation, 2)
        val slopeFromDeviations = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)

        val aci = 10
        if (ci == 0.0) {
            cid = 0.0
        } else {
            cid = min(remainingCATime * 60 / 5 / 2, Math.max(0.0, meal_data.mealCOB * csf / ci))
        }
        val acid = max(0.0, meal_data.mealCOB * csf / aci)
        consoleError.add(element = "Carb Impact: ${ci} mg/dL per 5m; CI Duration: ${round(cid * 5 / 60 * 2, 1)} hours; remaining CI (~2h peak): ${round(remainingCIpeak, 1)} mg/dL per 5m")
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
        val lastIOBpredBG: Double
        var lastCOBpredBG: Double? = null
        var lastUAMpredBG: Double? = null
        var UAMduration = 0.0
        var remainingCItotal = 0.0
        val remainingCIs = mutableListOf<Int>()
        val predCIs = mutableListOf<Int>()
        var UAMpredBG: Double? = null
        var COBpredBG: Double? = null
        var aCOBpredBG: Double?
        iobArray.forEach { iobTick ->
            val predBGI: Double = round((-iobTick.activity * sens * 5), 2)
            val IOBpredBGI: Double = predBGI
            iobTick.iobWithZeroTemp ?: error("iobTick.iobWithZeroTemp missing")
            val predZTBGI = round((-iobTick.iobWithZeroTemp!!.activity * sens * 5), 2)
            val predUAMBGI = predBGI
            val predDev: Double = ci * (1 - min(1.0, IOBpredBGs.size / (60.0 / 5.0)))
            IOBpredBG = IOBpredBGs[IOBpredBGs.size - 1] + IOBpredBGI + predDev
            val ZTpredBG = ZTpredBGs[ZTpredBGs.size - 1] + predZTBGI
            val predCI: Double = max(0.0, max(0.0, ci) * (1 - COBpredBGs.size / max(cid * 2, 1.0)))
            val predACI = max(0.0, max(0, aci) * (1 - COBpredBGs.size / max(acid * 2, 1.0)))
            val intervals = Math.min(COBpredBGs.size.toDouble(), ((remainingCATime * 12) - COBpredBGs.size))
            val remainingCI = Math.max(0.0, intervals / (remainingCATime / 2 * 12) * remainingCIpeak)
            remainingCItotal += predCI + remainingCI
            remainingCIs.add(round(remainingCI))
            predCIs.add(round(predCI))
            COBpredBG = COBpredBGs[COBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predCI + remainingCI
            aCOBpredBG = aCOBpredBGs[aCOBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predACI
            val predUCIslope = max(0.0, uci + (UAMpredBGs.size * slopeFromDeviations))
            val predUCImax = max(0.0, uci * (1 - UAMpredBGs.size / max(3.0 * 60 / 5, 1.0)))
            val predUCI = min(predUCIslope, predUCImax)
            if (predUCI > 0) {
                UAMduration = round((UAMpredBGs.size + 1) * 5 / 60.0, 1)
            }
            UAMpredBG = UAMpredBGs[UAMpredBGs.size - 1] + predUAMBGI + min(0.0, predDev) + predUCI
            if (IOBpredBGs.size < 48) IOBpredBGs.add(IOBpredBG)
            if (COBpredBGs.size < 48) COBpredBGs.add(COBpredBG)
            if (aCOBpredBGs.size < 48) aCOBpredBGs.add(aCOBpredBG)
            if (UAMpredBGs.size < 48) UAMpredBGs.add(UAMpredBG)
            if (ZTpredBGs.size < 48) ZTpredBGs.add(ZTpredBG)
            if (COBpredBG < minCOBGuardBG) minCOBGuardBG = round(COBpredBG).toDouble()
            if (UAMpredBG < minUAMGuardBG) minUAMGuardBG = round(UAMpredBG).toDouble()
            if (IOBpredBG < minIOBGuardBG) minIOBGuardBG = IOBpredBG
            if (ZTpredBG < minZTGuardBG) minZTGuardBG = round(ZTpredBG, 0)

            val insulinPeakTime = 90
            val insulinPeak5m = (insulinPeakTime / 60.0) * 12.0
            if (IOBpredBGs.size > insulinPeak5m && (IOBpredBG < minIOBPredBG)) minIOBPredBG = round(IOBpredBG, 0)
            if (IOBpredBG > maxIOBPredBG) maxIOBPredBG = IOBpredBG
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBGs.size > insulinPeak5m && (COBpredBG < minCOBPredBG)) minCOBPredBG = round(COBpredBG, 0)
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBG > maxIOBPredBG) maxCOBPredBG = COBpredBG
            if (enableUAM && UAMpredBGs.size > 12 && (UAMpredBG < minUAMPredBG)) minUAMPredBG = round(UAMpredBG, 0)
        }
        if (meal_data.mealCOB > 0) {
            consoleError.add("predCIs (mg/dL/5m):" + predCIs.joinToString(separator = " "))
            consoleError.add("remainingCIs:      " + remainingCIs.joinToString(separator = " "))
        }
        rT.predBGs = Predictions()
        IOBpredBGs = IOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in IOBpredBGs.size - 1 downTo 13) {
            if (IOBpredBGs[i - 1] != IOBpredBGs[i]) break
            else IOBpredBGs.removeAt(IOBpredBGs.lastIndex)
        }
        rT.predBGs?.IOB = IOBpredBGs.map { it.toInt() }
        lastIOBpredBG = round(IOBpredBGs[IOBpredBGs.size - 1]).toDouble()
        ZTpredBGs = ZTpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in ZTpredBGs.size - 1 downTo 7) {
            if (ZTpredBGs[i - 1] >= ZTpredBGs[i] || ZTpredBGs[i] <= target_bg) break
            else ZTpredBGs.removeAt(ZTpredBGs.lastIndex)
        }
        rT.predBGs?.ZT = ZTpredBGs.map { it.toInt() }
        if (meal_data.mealCOB > 0) {
            aCOBpredBGs = aCOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
            for (i in aCOBpredBGs.size - 1 downTo 13) {
                if (aCOBpredBGs[i - 1] != aCOBpredBGs[i]) break
                else aCOBpredBGs.removeAt(aCOBpredBGs.lastIndex)
            }
        }
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            COBpredBGs = COBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
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
                UAMpredBGs = UAMpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
                for (i in UAMpredBGs.size - 1 downTo 13) {
                    if (UAMpredBGs[i - 1] != UAMpredBGs[i]) break
                    else UAMpredBGs.removeAt(UAMpredBGs.lastIndex)
                }
                rT.predBGs?.UAM = UAMpredBGs.map { it.toInt() }
                lastUAMpredBG = UAMpredBGs[UAMpredBGs.size - 1]
                eventualBG = max(eventualBG, round(UAMpredBGs[UAMpredBGs.size - 1], 0))
            }
            rT.eventualBG = eventualBG
        }

        // Always set (uci is computed unconditionally above, not gated by enableUAM) -- read back into
        // autoIsfValues.uamCarbImpact for persistence in OpenAPSAutoISFPlugin.kt, for the Raw/UAM-carbs
        // graph line. Converted from uci's native mg/dL/5min BG-impact into a grams/5min carbs-
        // equivalent via csf (mg/dL per gram, already computed above and used the same way for maxCI's
        // own grams-based clamp a few lines up) -- same live per-cycle csf, not a separate/staler
        // approximation, so this is exactly as fresh as the real ci/maxCI dosing math right next to it.
        // Graphed on its own separate scale (uamCarbImpactScale), not shared with carbAbsorptionScale --
        // still its own line, just now in comparable grams/5min units instead of raw mg/dL.
        val uciGramsEquivalent = if (csf > 0.0) round(uci / csf, 2) else 0.0
        rT.autoIsfUamCarbImpact = uciGramsEquivalent
        consoleError.add("UAM Impact: $uci mg/dL per 5m ($uciGramsEquivalent g/5m); UAM Duration: $UAMduration hours")
        consoleError.add("EventualBG is ${convert_bg(eventualBG)} ;")

        minIOBPredBG = max(39.0, minIOBPredBG)
        minCOBPredBG = max(39.0, minCOBPredBG)
        minUAMPredBG = max(39.0, minUAMPredBG)
        minPredBG = round(minIOBPredBG, 0)

        val fractionCarbsLeft = meal_data.mealCOB / meal_data.carbs
        if (minUAMPredBG < 999 && minCOBPredBG < 999) {
            avgPredBG = round((1 - fractionCarbsLeft) * UAMpredBG!! + fractionCarbsLeft * COBpredBG!!, 0)
        } else if (minCOBPredBG < 999) {
            avgPredBG = round((IOBpredBG + COBpredBG!!) / 2.0, 0)
        } else if (minUAMPredBG < 999) {
            avgPredBG = round((IOBpredBG + UAMpredBG!!) / 2.0, 0)
        } else {
            avgPredBG = round(IOBpredBG, 0)
        }
        if (minZTGuardBG > avgPredBG) {
            avgPredBG = minZTGuardBG
        }

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

        var minZTUAMPredBG = minUAMPredBG
        if (minZTGuardBG < threshold) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
        } else if (minZTGuardBG < target_bg) {
            val blendPct = (minZTGuardBG - threshold) / (target_bg - threshold)
            val blendedMinZTGuardBG = minUAMPredBG * blendPct + minZTGuardBG * (1 - blendPct)
            minZTUAMPredBG = (minUAMPredBG + blendedMinZTGuardBG) / 2.0
        } else if (minZTGuardBG > minUAMPredBG) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
        }
        minZTUAMPredBG = round(minZTUAMPredBG, 0)

        if (meal_data.carbs != 0.0) {
            if (!enableUAM && minCOBPredBG < 999) {
                minPredBG = round(max(minIOBPredBG, minCOBPredBG), 0)
            } else if (minCOBPredBG < 999) {
                val blendedMinPredBG = fractionCarbsLeft * minCOBPredBG + (1 - fractionCarbsLeft) * minZTUAMPredBG
                minPredBG = round(max(minIOBPredBG, max(minCOBPredBG, blendedMinPredBG)), 0)
            } else if (enableUAM) {
                minPredBG = minZTUAMPredBG
            } else {
                minPredBG = minGuardBG
            }
        } else if (enableUAM) {
            minPredBG = round(max(minIOBPredBG, minZTUAMPredBG), 0)
        }
        minPredBG = min(minPredBG, avgPredBG)

        consoleError.add("minPredBG: ${convert_bg(minPredBG)} minIOBPredBG: ${convert_bg(minIOBPredBG)} minZTGuardBG: ${convert_bg(minZTGuardBG)}")
        if (minCOBPredBG < 999) {
            consoleError.add(" minCOBPredBG: ${convert_bg(minCOBPredBG)}")
        }
        if (minUAMPredBG < 999) {
            consoleError.add(" minUAMPredBG: ${convert_bg(minUAMPredBG)}")
        }
        consoleError.add(" avgPredBG: ${convert_bg(avgPredBG)} COB: ${round(meal_data.mealCOB, 1)} / ${round(meal_data.carbs, 1)}")
        if (maxCOBPredBG > bg) {
            minPredBG = min(minPredBG, maxCOBPredBG)
        }

        rT.COB = meal_data.mealCOB
        rT.IOB = iob_data.iob
        //==========================================================================================================
        val Delta = glucose_status.delta
        val IOB = iob_data.iob
        val COB = meal_data.mealCOB
        val SDelta = glucose_status.shortAvgDelta
        val LDelta = glucose_status.longAvgDelta
        val Steps10M = profile.recent_steps_10_minutes
        val Steps30M = profile.recent_steps_30_minutes
        val Steps60M = profile.recent_steps_60_minutes
        val Steps180M = steps180M
        val Steps15M = steps15M
        val Steps5M = steps5M

        val delta_accl: Double = if (abs(SDelta) == 0.0) {
            0.0
        } else {
            100 * round((Delta - SDelta) / abs(SDelta), 2)
        }

        var CR = round(profile.carb_ratio, 2)

        val TwilightTimeAM = 8
        val TwilightTimeMins = 0
        val TwilightTimeDec = TwilightTimeAM + TwilightTimeMins / 100
        rT.reason.append(
            " aisf321_593 COB: ${round(meal_data.mealCOB, 1).withoutZeros()}, Dev: ${convert_bg2(deviation.toDouble())}, BGI: ${convert_bg2(bgi)}, ISF: ${convert_isf(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convert_bg(target_bg)}, minPredBG ${convert_bg(minPredBG)}, minGuardBG ${convert_bg(minGuardBG)}, IOBpredBG ${convert_bg(lastIOBpredBG)}"
        )

        rT.reason.append(" ================================== Delta: ${convert_bg2(Delta)}")
        val applyWeights = preferences.get(BooleanKey.ApsUseAutoIsfWeights)
        rT.reason.append("applyWeights=$applyWeights ;; ")
        if (applyWeights) {
            rT.reason.append("AutoISF weights ACTIVE AutoISF weights enabled in Preferences ")
        } else {
            rT.reason.append("AutoISF weights DISPLAY only: AutoISF weights disabled in Preferences ")
        }
        rT.reason.append("IOB: ${round(IOB, 2)} ;")
        rT.reason.append("iobThUser is ${iobThUser} ;;")
        var TOD = "not set TOD"
        if (iobThUser == 12) {
            TOD = "Night"
        } else if (iobThUser == 15) {
            TOD = "Twilight"
        } else if (iobThUser == 20) {
            TOD = "SemiTwilight"
        } else if (iobThUser == 15) {
            TOD = "Evening"
        } else if (iobThUser == 30) {
            TOD = "PP90%"
        } else if (iobThUser == 50) {
            TOD = "Day PP100%"
        } else if (iobThUser == 40) {
            TOD = "Day PP100%"
        } else if (iobThUser == 55) {
            TOD = "Day PP110%"
        } else if (iobThUser == 60) {
            TOD = "Day PP130%"
        }
        consoleError.add("bgAccel_ISF_weight is ${round(profile.bgAccel_ISF_weight, 2)} ;;")
        consoleError.add("delta_accl: " + round(delta_accl, 1).withoutZeros() + " ; ")
        rT.reason.append("bgAccel_ISF_weight is ${round(profile.bgAccel_ISF_weight, 2)} ;;")
        rT.reason.append("delta_accl: ${round(delta_accl, 1).withoutZeros()} ;")
        rT.reason.append("Delta: ${convert_bg2(Delta)} ;")
        rT.reason.append("SDelta: ${convert_bg2(SDelta)} ;")
        rT.reason.append("LDelta: ${convert_bg2(LDelta)} ;")
        consoleError.add("IOB: " + round(IOB, 2) + " ; ")
        consoleError.add("Delta: " + convert_bg2(Delta) + " ; ")
        consoleError.add("SDelta: " + convert_bg2(SDelta) + " ; ")
        consoleError.add("LDelta: " + convert_bg2(LDelta) + " ; ")
        consoleError.add("pp_ISF_weight is ${round(profile.pp_ISF_weight, 2)} ;;")
        consoleError.add("delta_accl: " + round(delta_accl, 1).withoutZeros() + " ; ")
        consoleError.add("bg_acce: ${round(bg_acce, 2)} ;")
        consoleError.add("profile_percentage: ${profile_percentage} ;")
        rT.reason.append("Steps5M: ${Steps5M} ;")
        rT.reason.append("Steps15M: ${Steps15M} ;")
        rT.reason.append("Steps30M: ${Steps30M} ;")
        rT.reason.append("Steps60M: ${Steps60M} ;")
        rT.reason.append("Steps180M: ${Steps180M} ;")
        rT.reason.append("TwilightTimeDec: ${TwilightTimeDec} ;")
        rT.reason.append("profile_percentage: ${profile_percentage} ;")
        rT.reason.append("bg_acce: ${round(bg_acce, 2)} ;")
        rT.reason.append("delta_accl: ${round(delta_accl, 1).withoutZeros()} ;")
        rT.reason.append("bgAccel_ISF_weight is ${round(profile.bgAccel_ISF_weight, 2)} ;;")
        rT.reason.append("dura_ISF_weight is ${round(profile.dura_ISF_weight, 2)} ;;")
        rT.reason.append("higher_ISFrange_weight is ${round(profile.higher_ISFrange_weight, 2)} ;;")
        consoleError.add("pp_ISF_weight is ${round(profile.pp_ISF_weight, 2)} ;;")
        rT.reason.append("pp_ISF_weight is ${round(profile.pp_ISF_weight, 2)} ;;")
        consoleError.add("Steps60M: " + Steps60M + " ; ")
        consoleError.add("Steps30M: " + Steps30M + " ; ")
        consoleError.add("TwilightTimeDec: " + TwilightTimeDec + " ; ")

        //================================================================================/
        var lastCarbAge = 363.0
        if (meal_data.carbs != null && meal_data.carbs > 0) {
            lastCarbAge = round(((systemTime - meal_data.lastCarbTime) / 60000.0), 2)
        }

        var CarbAge = lastCarbAge

        val high_SMB = profile.smb_delivery_ratio_max
        // Simple override: a single fixed mmol value (GUI setting) replaces smb_delivery_ratio_max as
        // varOffset's base when this is on. Either way, the time-of-day nudge below always applies on
        // top (see ApsAutoIsfTodOffset* below) — it's independent of this toggle.
        val useSimpleOffsetOverride = preferences.get(BooleanKey.ApsAutoIsfSmbOffsetOverrideEnabled)
        var varOffset: Double = if (useSimpleOffsetOverride) preferences.get(DoubleKey.ApsAutoIsfSmbOffsetOverride) * 18.0
                                 else high_SMB * 18.0  // 0.5→9, 0.6→10.8, 1.0→18

        val hour = LocalDateTime.now().hour

        var targetBgOrig: Double = when {
            !profile.temptargetSet && profile.min_bg != null -> profile.min_bg!!.toDouble()
            hour >= 22                                       -> 5.2 * 18
            hour in 20 until 22                              -> 5.2 * 18
            hour in 16 until 20                              -> 5.0 * 18
            hour in 10 until 16                              -> 5.0 * 18
            hour in 8 until 10                               -> 5.0 * 18
            hour in 6 until 8                                -> 5.0 * 18
            hour in 5 until 6                                -> 5.0 * 18
            else                                             -> 5.4 * 18
        }

        var eatSoon = false
        var stuckH = false
        var high = false
        var rising = false
        var straightRise = false
        var night4Ov = false
        var day3Ov = false
        var over6Ov = false

        if (profile.temptargetSet && target_bg < 4.45 * 18 && target_bg > 4.35 * 18 && bg > 4.5 * 18 && Delta > 0.02 * 18) {
            eatSoon = true
        }

        var highProfile = false
        var boostOrig = false

        var profileSwitch = 100

        var targetBgOffset = min(targetBgOrig + varOffset, 126.0)

        val nowLocalDateTime = LocalDateTime.now()
        val nowHour = nowLocalDateTime.hour
        // Minute-of-day, needed because the cumulative SMB cap's window starts at 00:30 -- hour
        // granularity alone cannot express that.
        val nowMinsOfDay = nowHour * 60 + nowLocalDateTime.minute
        // Omnipod Dash requires durationInMinutes divisible by 30; 15-min temps are rejected.
        val standardTempDuration = 30

        rT.reason.append(
            if (useSimpleOffsetOverride) "useSimpleOffsetOverride: varOffset fixed at ${convert_bg(varOffset)} ;"
            else "varOffset from smb_delivery_ratio_max: ${convert_bg(varOffset)} ;"
        )

        // Time-of-day varOffset nudge — one signed mmol value per fixed window (TT-adjustable, see the
        // *TT blocks in OpenAPSAutoISFPlugin.kt), applied ADDITIVELY on top of whatever varOffset already
        // is above, regardless of which mode (HARD override or the normal derivation) produced it.
        // Replaces the old carbsReqThreshold-encoded offset1/2/3 + hardcoded nowHour mechanism.
        val todOffsetMmol = when (nowHour) {
            in 0 until 2   -> preferences.get(DoubleKey.ApsAutoIsfTodOffset0002)
            in 2 until 4   -> preferences.get(DoubleKey.ApsAutoIsfTodOffset0204)
            in 4 until 6   -> preferences.get(DoubleKey.ApsAutoIsfTodOffset0406)
            in 6 until 9   -> preferences.get(DoubleKey.ApsAutoIsfTodOffset0609)
            in 9 until 12  -> preferences.get(DoubleKey.ApsAutoIsfTodOffset0912)
            in 12 until 18 -> preferences.get(DoubleKey.ApsAutoIsfTodOffset1218)
            in 18 until 22 -> preferences.get(DoubleKey.ApsAutoIsfTodOffset1822)
            else           -> preferences.get(DoubleKey.ApsAutoIsfTodOffset2200)   // 22-24
        }
        if (todOffsetMmol != 0.0) {
            varOffset += todOffsetMmol * 18.0
            rT.reason.append("todOffset[${nowHour}h] ${round(todOffsetMmol, 2)}mmol: varOffset now ${convert_bg(varOffset)} ;")
        }

        // MildOffsetZero: OpenAPSAutoISFPlugin's BolusGivenMild sets this flag when it fires while BG <
        // 5.9mmol, so its stronger SMB delivery ratio isn't wasted behind this offset gate below — forces
        // varOffset to 0 (bg only needs to clear targetBgOrig itself, not target+offset) for the same
        // 2-min window as the delivery-ratio boost. Self-clears when that TT expires.
        if (preferences.get(BooleanKey.ApsAutoIsfMildOffsetZeroActive)) {
            varOffset = 0.0
            rT.reason.append("MildOffsetZero active (BolusGivenMild fired under 5.9mmol): varOffset forced to 0 ;")
        }

        var offsetSoZeroSMB = false
        if (bg < targetBgOffset && (COB == 0.0 || (COB < 5.0 && CarbAge > 120))) {
            offsetSoZeroSMB = true
            rT.reason.append("bg un targetBgOffset && low COB offsetSoZeroSMB=($offsetSoZeroSMB) ;")
        }
        if (!(bg < targetBgOffset && (COB == 0.0 || (COB < 5.0 && CarbAge > 120)))) {
            offsetSoZeroSMB = false
            rT.reason.append("NOT (bg un targetBgOffset && low COB offsetSoZeroSMB=($offsetSoZeroSMB) ;")
        }
        rT.reason.append("varOffset ${convert_bg(varOffset)} ;")
        rT.reason.append("varOffset raw ${round(varOffset, 1)} ;")
        consoleError.add("varOffset ${convert_bg(varOffset)} ;")
        consoleError.add("varOffset raw ${round(varOffset, 1)} ;")
        varOffset = min(36.0, varOffset)

        rT.reason.append("varOffset (${round(varOffset, 1)})")
        targetBgOffset = min(targetBgOrig + varOffset, 126.0)
        rT.reason.append("targetBgOffset = min(targetBgOrig + varOffset, 7.0): ${convert_bg(targetBgOffset)} ;")
        if (bg < targetBgOffset && (COB == 0.0 || (COB < 5 && CarbAge > 120))) {
            offsetSoZeroSMB = true
            rT.reason.append("bg un targetBgOffset && no/low COB: ")
        }

        consoleError.add("target_bgOrigmm: " + round(target_bgOrigmm, 1) + " ; ")
        consoleError.add("targetBgOrig: " + convert_bg(targetBgOrig) + " ; ")
        consoleError.add("targetBgOffset: " + convert_bg(targetBgOffset) + " ; ")
        consoleError.add("offsetSoZeroSMB: " + offsetSoZeroSMB + " ; ")
        rT.reason.append("offsetSoZeroSMB: ${offsetSoZeroSMB} ;")
        rT.reason.append("targetBgOffset: ${convert_bg(targetBgOffset)} ;")
        rT.reason.append("targetBgOrig: ${convert_bg(targetBgOrig)} ;")
        rT.reason.append("target_bgOrigmm: ${round(target_bgOrigmm, 1)} ;")

        if (!(bg < targetBgOffset && (COB == 0.0 || (COB < 5 && CarbAge > 120)))) {
            offsetSoZeroSMB = false
            rT.reason.append("offsetSoZeroSMBcleared: BG ov targetBgOffset, SMB restored; ")
            if (bg > targetBgOffset) {
                // boostActive restored
            }
        }

        rT.reason.append(
            "COB: ${round(meal_data.mealCOB, 1).withoutZeros()}, Dev: ${convert_bg2(deviation.toDouble())}, BGI: ${convert_bg2(bgi)}, ISF: ${convert_isf(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convert_bg(target_bg)}, minPredBG ${convert_bg(minPredBG)}, minGuardBG ${convert_bg(minGuardBG)}, IOBpredBG ${convert_bg(lastIOBpredBG)}"
        )
        if (lastCOBpredBG != null) {
            rT.reason.append(", COBpredBG " + convert_bg(lastCOBpredBG.toDouble()))
        }
        if (lastUAMpredBG != null) {
            rT.reason.append(", UAMpredBG " + convert_bg(lastUAMpredBG.toDouble()))
        }
        rT.reason.append("; ")
        var carbsReqBG = naive_eventualBG
        if (carbsReqBG < 40) {
            carbsReqBG = min(minGuardBG, carbsReqBG)
        }
        var bgUndershoot: Double = threshold - carbsReqBG
        var minutesAboveMinBG = 240
        var minutesAboveThreshold = 240
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            for (i in COBpredBGs.indices) {
                if (COBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in COBpredBGs.indices) {
                if (COBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        } else {
            for (i in IOBpredBGs.indices) {
                if (IOBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in IOBpredBGs.indices) {
                if (IOBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        }

        if (enableSMB && minGuardBG < threshold) {
            consoleError.add("minGuardBG ${convert_bg(minGuardBG)} projected below ${convert_bg(threshold)} - disabling SMB")
            enableSMB = false
        }
        var maxDeltaPercentage = 0.2
        if (loop_wanted_smb == "fullLoop") {
            maxDeltaPercentage = 0.3
        }
        if (maxDelta > maxDeltaPercentage * bg) {
            consoleError.add("maxDelta ${convert_bg2(maxDelta)} > ${100 * maxDeltaPercentage}% of BG ${convert_bg(bg)} - disabling SMB")
            rT.reason.append("maxDelta " + convert_bg2(maxDelta) + " > " + 100 * maxDeltaPercentage + "% of BG " + convert_bg(bg) + ": SMB disabled; ")
            enableSMB = false
        }

        consoleError.add("BG projected to remain above ${convert_bg(min_bg)} for $minutesAboveMinBG minutes")
        if (minutesAboveThreshold < 240 || minutesAboveMinBG < 60) {
            consoleError.add("BG projected to remain above ${convert_bg(threshold)} for $minutesAboveThreshold minutes")
        }
        val zeroTempDuration = minutesAboveThreshold
        val zeroTempEffectDouble = profile.current_basal * sens * zeroTempDuration / 60
        val COBforCarbsReq = max(0.0, meal_data.mealCOB - 0.25 * meal_data.carbs)
        val carbsReq = round(((bgUndershoot - zeroTempEffectDouble) / csf - COBforCarbsReq))
        val zeroTempEffect = round(zeroTempEffectDouble)
        consoleError.add("naive_eventualBG: ${convert_bg(naive_eventualBG)} bgUndershoot: ${convert_bg(bgUndershoot)} zeroTempDuration $zeroTempDuration zeroTempEffect: ${convert_bg(zeroTempEffect.toDouble())} carbsReq: $carbsReq")
        if (carbsReq >= profile.carbsReqThreshold && minutesAboveThreshold <= 45) {
            rT.carbsReq = carbsReq
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsReq add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }

        if (bg < threshold && iob_data.iob < -profile.current_basal * 20 / 60 && minDelta > 0 && minDelta > expectedDelta) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} un ${round(-profile.current_basal * 20 / 60, 2)}")
            rT.reason.append(" and minDelta ${convert_bg2(minDelta)} > expectedDelta ${convert_bg2(expectedDelta)}; ")
        } else if (bg < threshold || minGuardBG < threshold) {
            rT.reason.append("minGuardBG ${convert_bg(minGuardBG)} < ${convert_bg(threshold)}")
            bgUndershoot = target_bg - minGuardBG
            val worstCaseInsulinReq = bgUndershoot / sens
            var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)
            durationReq = round(durationReq / 30.0) * 30
            durationReq = min(120, max(30, durationReq))
            return setTempBasal(0.0, durationReq, profile, rT, currenttemp)
        }

        val minutes = Instant.ofEpochMilli(rT.deliverAt!!).atZone(ZoneId.systemDefault()).toLocalDateTime().minute
        if (profile.skip_neutral_temps && minutes >= 55) {
            rT.reason.append("; Canceling temp at " + minutes + "m past the hour. ")
            return setTempBasal(0.0, 0, profile, rT, currenttemp)
        }

        if (eventualBG < min_bg) {
            rT.reason.append("Eventual BG ${convert_bg(eventualBG)} < ${convert_bg(min_bg)}")
            if (minDelta > expectedDelta && minDelta > 0 && carbsReq == 0) {
                if (naive_eventualBG < 40) {
                    rT.reason.append(", naive_eventualBG < 40. ")
                    return setTempBasal(0.0, 30, profile, rT, currenttemp)
                }
                if (glucose_status.delta > minDelta) {
                    rT.reason.append(", but Delta ${convert_bg2(tick.toDouble())} > expectedDelta ${convert_bg2(expectedDelta)}")
                } else {
                    rT.reason.append(", but Min. Delta ${convert_bg2(minDelta)} > Exp. Delta ${convert_bg2(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + basalForDisplay(currenttemp.rate) + " ~ req " + basalForDisplay(basal) + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${basalForDisplay(basal)} as temp. ")
                    return setTempBasal(basal, standardTempDuration, profile, rT, currenttemp)
                }
            }

            var insulinReq = 2 * min(0.0, (eventualBG - target_bg) / sens)
            insulinReq = round(insulinReq, 2)
            var naiveInsulinReq = min(0.0, (naive_eventualBG - target_bg) / sens)
            naiveInsulinReq = round(naiveInsulinReq, 2)
            if (minDelta < 0 && minDelta > expectedDelta) {
                val newinsulinReq = round((insulinReq * (minDelta / expectedDelta)), 2)
                insulinReq = newinsulinReq
            }
            var rate = basal + (2 * insulinReq)
            rate = round_basal(rate)

            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            val minInsulinReq = Math.min(insulinReq, naiveInsulinReq)
            if (insulinScheduled < minInsulinReq - basal * 0.3) {
                rT.reason.append(", ${currenttemp.duration}m@${basalForDisplay(currenttemp.rate)} is a lot less than needed. ")
                return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
            }
            if (currenttemp.duration > 5 && rate >= currenttemp.rate * 0.8) {
                rT.reason.append(", temp ${basalForDisplay(currenttemp.rate)} ~< req ${basalForDisplay(rate)}U/hr. ")
                return rT
            } else {
                if (rate <= 0) {
                    bgUndershoot = (target_bg - naive_eventualBG)
                    val worstCaseInsulinReq = bgUndershoot / sens
                    var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)
                    if (durationReq < 0) {
                        durationReq = 0
                    } else {
                        durationReq = round(durationReq / 30.0) * 30
                        durationReq = min(120, max(0, durationReq))
                    }
                    if (durationReq > 0) {
                        rT.reason.append(", setting ${durationReq}m zero temp. ")
                        return setTempBasal(rate, durationReq, profile, rT, currenttemp)
                    }
                } else {
                    rT.reason.append(", setting ${basalForDisplay(rate)}U/hr. ")
                }
                return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
            }
        }

        if (minDelta < expectedDelta) {
            if (!(microBolusAllowed && enableSMB)) {
                if (glucose_status.delta < minDelta) {
                    rT.reason.append(
                        "Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Delta ${convert_bg2(tick.toDouble())} < Exp. Delta ${
                            convert_bg2(expectedDelta)
                        }"
                    )
                } else {
                    rT.reason.append("Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Min. Delta ${convert_bg2(minDelta)} < Exp. Delta ${convert_bg2(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + basalForDisplay(currenttemp.rate) + " ~ req " + basalForDisplay(basal) + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${basalForDisplay(basal)} as temp. ")
                    return setTempBasal(basal, standardTempDuration, profile, rT, currenttemp)
                }
            }
        }
        if (min(eventualBG, minPredBG) < max_bg) {
            if (!(microBolusAllowed && enableSMB)) {
                rT.reason.append("${convert_bg(eventualBG)}-${convert_bg(minPredBG)} in range: no temp required")
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp ${basalForDisplay(currenttemp.rate)} ~ req ${basalForDisplay(basal)}U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${basalForDisplay(basal)} as temp. ")
                    return setTempBasal(basal, standardTempDuration, profile, rT, currenttemp)
                }
            }
        }

        if (eventualBG >= max_bg) {
            rT.reason.append("Eventual BG " + convert_bg(eventualBG) + " >= " + convert_bg(max_bg) + ", ")
        }
        if (iob_data.iob > max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} ov max_iob $max_iob")
            if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                rT.reason.append(", temp ${basalForDisplay(currenttemp.rate)} ~ req ${basalForDisplay(basal)}U/hr. ")
                return rT
            } else {
                rT.reason.append("; setting current basal of ${basalForDisplay(basal)} as temp. ")
                return setTempBasal(basal, standardTempDuration, profile, rT, currenttemp)
            }
        } else {
            // =====================================================
            // TDDfactor: scale max_iob and insulinReq by blended TDD ratio when high_SMB is active.
            // tddRatio is set from OpenAPSAutoISFPlugin via class-level property before each invoke().
            // sensitivityRatio is intentionally NOT used here.
            // =====================================================

            var insulinReq =
                round((min(minPredBG, eventualBG) - target_bg) / sens, 2)
            insulinReq *= TDDfactor
            insulinReq = round(insulinReq, 2)
            max_iob *= TDDfactor
            max_iob = round(max_iob, 2)
            consoleError.add("TDDfactor ${round(TDDfactor, 2)} max_iob ${round(max_iob, 2)} insulinReq = ${round(insulinReq, 2)}")
            rT.reason.append("TDDfactor ${round(TDDfactor, 2)} max_iob ${round(max_iob, 2)} insulinReq = ${round(insulinReq, 2)}")
            if (insulinReq > max_iob - iob_data.iob) {
                rT.reason.append("max_iob $max_iob, ")
                insulinReq = max_iob - iob_data.iob
            }

            var rate = basal + (2 * insulinReq)
            rate = round_basal(rate)
            insulinReq = round(insulinReq, 3)
            rT.insulinReq = insulinReq
            val maxBolus: Double
            if (microBolusAllowed && enableSMB && bg > threshold) {
                val mealInsulinReq = round(meal_data.mealCOB / profile.carb_ratio, 3)
                val smb_max_range = smb_max_range_extension
                if (iob_data.iob > mealInsulinReq && iob_data.iob > 0) {
                    consoleError.add("IOB ${round(iob_data.iob, 2)} ov mealInsulinReq = ${round(mealInsulinReq, 2)}")
                    consoleError.add("profile.maxUAMSMBBasalMinutes: ${profile.maxUAMSMBBasalMinutes} profile.current_basal: ${basalForDisplay(profile.current_basal)}")
                    maxBolus = round(smb_max_range * profile.current_basal * profile.maxUAMSMBBasalMinutes / 60, 1)
                } else {
                    consoleError.add("profile.maxSMBBasalMinutes: ${profile.maxSMBBasalMinutes} profile.current_basal: ${basalForDisplay(profile.current_basal)}")
                    maxBolus = round(smb_max_range * profile.current_basal * profile.maxSMBBasalMinutes / 60, 1)
                }
                val roundSMBTo = 1 / profile.bolus_increment
                var microBolus = Math.floor(Math.min(insulinReq / 2, maxBolus) * roundSMBTo) / roundSMBTo
                if (autoIsfMode) {
                    microBolus = Math.min(insulinReq * smb_ratio, maxBolus)
                    if (microBolus > iobTHvirtual - iob_data.iob && (loop_wanted_smb == "fullLoop" || loop_wanted_smb == "enforced")) {
                        microBolus = iobTHvirtual - iob_data.iob
                        consoleError.add("Full loop capped SMB at ${round(microBolus, 2)} to not exceed $iobTHtolerance% of effective iobTH ${round(iobTHvirtual / iobTHtolerance * 100, 2)}U")
                    }
                    microBolus = Math.floor(microBolus * roundSMBTo) / roundSMBTo
                }

                val smbTarget = target_bg
                val worstCaseInsulinReq = (smbTarget - (naive_eventualBG + minIOBPredBG) / 2.0) / sens
                var durationReq = round(60 * worstCaseInsulinReq / profile.current_basal)

                if (insulinReq > 0 && microBolus < profile.bolus_increment) {
                    durationReq = 0
                }

                var smbLowTempReq = 0.0
                if (durationReq <= 0) {
                    durationReq = 0
                } else if (durationReq >= 30) {
                    durationReq = round(durationReq / 30.0) * 30
                    durationReq = min(60, max(0, durationReq))
                } else {
                    smbLowTempReq = round(basal * durationReq / 30.0, 2)
                    durationReq = 30
                }
                rT.reason.append(" insulinReq ${round(insulinReq, 2)}")
                if (microBolus >= maxBolus) {
                    rT.reason.append("; maxBolus ${round(maxBolus, 2)}")
                }
                if (durationReq > 0) {
                    rT.reason.append("; setting ${durationReq}m low temp of ${basalForDisplay(smbLowTempReq)}U/h")
                }
                rT.reason.append(". ")

                val lastBolusAge = (systemTime - iob_data.lastBolusTime) / 1000.0
                val SMBInterval = min(10, max(1, profile.SMBInterval)) * 60.0
                consoleError.add("naive_eventualBG ${convert_bg(naive_eventualBG)},${durationReq}m ${basalForDisplay(smbLowTempReq)}U/h temp needed; last bolus ${round(lastBolusAge / 60.0, 1)}m ago; maxBolus: ${round(maxBolus, 2)}")
                consoleError.add("offsetSoZeroSMB $offsetSoZeroSMB")
                val libreActive = (glucose_status as? GlucoseStatusAutoIsf)?.libreActive == true
                val LibreTrue = if (libreActive) 1.0 else 1.0

                val high_SMB2 = profile.smb_delivery_ratio_max
                val bg_range_SMB = profile.smb_delivery_ratio_bg_range
                val delivery_ratio = profile.smb_delivery_ratio
                rT.reason.append("TDDfactor = ${round(TDDfactor, 2)} high_SMB= ${round(high_SMB2, 2)} ")
                rT.reason.append("TDDfactor= ${round(TDDfactor, 2)} high_SMB= ${round(high_SMB2, 2)} ")
                var ThresholForFastRise = TDDfactor * 0.030 * profile.max_iob
                ThresholForFastRise = round(ThresholForFastRise, 2)
                consoleError.add("Delta threshold TDDfactor * 0.030 * profile.max_iob = (${round(TDDfactor, 2)} * 0.030 * ${round(profile.max_iob, 2)})= ${round(ThresholForFastRise, 2)} ")
                rT.reason.append("Delta threshold TDDfactor * 0.030 * profile.max_iob = (${round(TDDfactor, 2)} * 0.030 * ${round(profile.max_iob, 2)})= ${round(ThresholForFastRise, 2)} ")

                // Anti-stacking: if SMBs have averaged <=70s apart over the last 5 min, trim the SMB to
                // 90% BEFORE the fast-rise caps below. Deliberately before the caps: the x0.9 may drop
                // microBolus under ThresholForFastRise so only the 0.9 bites; but if it's still above the
                // threshold ("still high") the fast-rise multiplier also fires — so the extra shrink only
                // compounds when still high. Default smbInt5Sec (9999) = no stacking → no-op.
                //
                // The trim itself resets every 10 minutes, tracked from the CURRENT stack's own start
                // (ApsAutoIsfSmbStackStart), not fixed clock times -- same elapsed-time-since-started
                // pattern as the rest of this file's readyToRun()-style throttles. Previously this was a
                // continuously-sliding 5-min lookback with no concept of "stack age" at all: it just kept
                // re-applying x0.9 every cycle for as long as recent SMBs stayed <=70s apart, with no
                // reset point, so a sustained rapid-fire sequence stayed trimmed indefinitely.
                if (smbInt5Sec <= 70.0 && microBolus > 0.0) {
                    val stackStart = preferences.get(LongKey.ApsAutoIsfSmbStackStart)
                    val nowMs = System.currentTimeMillis()
                    if (stackStart == 0L || nowMs - stackStart >= 10 * 60 * 1000L) {
                        // No active stack, or the previous stack's 10min window has elapsed -- start a
                        // fresh one and let this SMB through at full size; the trim resumes on the next
                        // stacking cycle within this new window.
                        preferences.put(LongKey.ApsAutoIsfSmbStackStart, nowMs)
                        consoleError.add("SMB stacking: avg gap ${round(smbInt5Sec, 0)}s <=70 -> new 10min stack window started, full size this cycle ")
                        rT.reason.append("SMB stacking <=70s: new 10min stack window, full size ")
                    } else {
                        // Two tiers. Below 0.35*max_iob the original flat x0.9 is kept unchanged -- brief
                        // stacking at modest IOB was never the problem, and weakening nothing keeps this
                        // change purely additive. At or above 0.35*max_iob (~3.3U at max_iob 9.5) the trim
                        // escalates with stack age instead.
                        //
                        // Measured against a real failure (27 Jul 2026, 21:50-21:59): nine SMBs ~60s apart
                        // delivered ~1.55U and took IOB 3.02 -> 4.55 while this guard was active the whole
                        // time. x0.9 cannot bite against a sustained sequence -- it just shaves each of
                        // nine deliveries by a tenth. Escalation targets exactly that shape: a brief burst
                        // is barely touched, a run that keeps going gets progressively choked, and the IOB
                        // gate means it only engages once there is already enough on board to matter.
                        // 25% base, +10% per completed 3-min block, capped at 55% so this always remains a
                        // trim rather than a silent block (a hard zero would be indistinguishable in the
                        // logs from "no SMB was wanted"). Over the 10-min window: x0.75 (0-3min) -> x0.65
                        // (3-6) -> x0.55 (6-9) -> x0.45 (9-10); then the window resets and the next stack
                        // starts at full size again, unchanged from before.
                        // 4-MINUTE GRACE before any escalation. Measured justification: across recent
                        // exports 73% of all SMBi5 readings are <=70s (median 61s, min 50s), because at
                        // 1-minute sensor readings ~60s between SMBs IS the normal maximum rate -- one per
                        // reading. The <=70s entry test was calibrated when readings arrived every ~2min,
                        // where a 60s gap really did mean rapid-fire; it now fires during ordinary
                        // operation. Escalating straight from window start would therefore have run
                        // 25->55% almost continuously above the IOB gate, suppressing normal post-meal
                        // dosing -- the same failure inverted. At 1-min cadence interval can no longer
                        // distinguish "9 SMBs in 9 min" from "2 SMBs in 2 min" (both average ~60s), so
                        // DURATION is the only usable discriminator, which is what the grace encodes.
                        //
                        // Within the grace the pre-existing flat 10% still applies, so this change is
                        // purely additive -- it is never weaker than the previous behaviour at any stack
                        // age, it only adds bite to runs that genuinely persist. After the grace: 25%,
                        // +10% per completed 2-min block, capped at 45% by the 10-min window itself.
                        // Against 27 Jul 2026 that engages from ~21:54, covering the worst five minutes
                        // (~1.10U of the 1.55U burst) while leaving ordinary 2-3 SMB sequences untouched.
                        // IOB gate is TIME-OF-DAY dependent. 0.35 was calibrated against 27 Jul 2026, an
                        // evening episode where IOB reached 4.5U with carbs on board -- the wrong context
                        // for overnight. On 6->7 Aug two overnight bursts (23:10-23:18: IOB 0.36 -> 2.00;
                        // 01:08-01:15: 1.43 -> 2.71) drove BGL from 8.6 to a sustained 3.5, and NEITHER
                        // reached 0.35*max_iob (3.33U at max_iob 9.5), so the escalation never engaged and
                        // only the flat 10% applied. With COB at 0 overnight there is nothing for that IOB
                        // to cover, so the level that warrants a brake is far lower: 0.15*max_iob (~1.43U)
                        // engages part-way through the first of those bursts and from the outset of the
                        // second. Window 22:00-08:00 -- starts at 22:00 rather than midnight because the
                        // 23:10 burst would otherwise be missed.
                        val stackAgeMin = (nowMs - stackStart) / 60000.0
                        val stackGraceMin = 4.0
                        val overnightStackWindow = nowHour >= 22 || nowHour < 8
                        val stackIobGateFraction = if (overnightStackWindow) 0.15 else 0.35
                        val highIobStack = IOB >= stackIobGateFraction * profile.max_iob
                        val trimFraction = if (highIobStack && stackAgeMin >= stackGraceMin)
                            (0.25 + 0.10 * ((stackAgeMin - stackGraceMin) / 2.0).toInt()).coerceAtMost(0.45)
                        else 0.10
                        microBolus *= (1.0 - trimFraction)
                        consoleError.add("SMB stacking: avg gap ${round(smbInt5Sec, 0)}s <=70 -> microBolus x${round(1.0 - trimFraction, 2)} = ${round(microBolus, 2)} (stack age ${round(stackAgeMin, 1)}min, trim ${round(trimFraction * 100, 0)}%, IOB ${round(IOB, 2)} vs gate ${round(stackIobGateFraction * profile.max_iob, 2)} @${if (overnightStackWindow) "night" else "day"}) ")
                        rT.reason.append("SMB stacking <=70s: microBolus x${round(1.0 - trimFraction, 2)} (age ${round(stackAgeMin, 1)}min, IOB ${round(IOB, 2)}) = ${round(microBolus, 2)} ")
                    }
                } else if (preferences.get(LongKey.ApsAutoIsfSmbStackStart) != 0L) {
                    // Stacking has genuinely stopped (avg gap back above 70s) -- clear the marker so a
                    // later rapid-fire sequence starts a brand new stack instead of inheriting whatever
                    // was left of the old window.
                    preferences.put(LongKey.ApsAutoIsfSmbStackStart, 0L)
                }

                // Snapshot the full (uncapped) SMB. When the profile is boosted (>100%) all the fast-rise
                // size-reduction/cap blocks below are undone at the end so a boost delivers full SMBs.
                val microBolusFullUncapped = microBolus

// =====================================================
// SHOWER / TWILIGHT AM PROTECTION
// =====================================================
                if (((nowHour >= 5) && (nowHour < 10)) &&
                    bg <= 8.0 * 18 &&
                    (Steps60M ?: 0) < 10 &&
                    COB <= 0 &&
                    !profile.temptargetSet &&
                    Delta >= 0.25 * 18 &&
                    SDelta >= 0.1 * 18 &&
                    iobThUser < 71 &&
                    rawDelta5Mgdl >= 0.25 * 18 && rawDelta1Mgdl >= 0.25 * 18 && aapsDelta1Mgdl >= 0.25 * 18
                ) {
                    val iobTHvirtualHARDshower = 0.075 * profile.max_iob
                    val microBolus1 = microBolus
                    if (microBolus > 0.02 * profile.max_iob) {
                        microBolus = 0.02 * profile.max_iob
                        rT.reason.append("microBolus fast rise 0.713 capped 0.02 * profile.max_iob = ${round(microBolus, 2)} ")
                    }
                    if (microBolus + IOB > iobTHvirtualHARDshower) {
                        microBolus = iobTHvirtualHARDshower - IOB
                        rT.reason.append("microBolus = iobTHvirtualHARDshower - IOB ; iobThUser ${iobThUser} IOB ${round(IOB, 2)} ")
                        rT.reason.append("microBolus + IOB ov iobTHvirtualHARD fast rise 0.714 shower = ${round(microBolus - microBolus1, 2)} diff = ")
                    }
                } else if (((nowHour >= 5) && (nowHour < 10)) &&
                    bg <= 8.0 * 18 &&
                    (Steps60M ?: 0) < 100 &&
                    COB <= 0 &&
                    !profile.temptargetSet &&
                    Delta >= 0.35 * 18 &&
                    SDelta >= 0.15 * 18 &&
                    iobThUser < 71 &&
                    rawDelta5Mgdl >= 0.35 * 18 && rawDelta1Mgdl >= 0.35 * 18 && aapsDelta1Mgdl >= 0.35 * 18
                ) {
                    val iobTHvirtualHARDshower = 0.075 * profile.max_iob
                    val microBolus1 = microBolus
                    if (microBolus > 0.02 * profile.max_iob) {
                        microBolus = 0.02 * profile.max_iob
                        rT.reason.append("microBolus capped fast rise 0.715 0.02 * profile.max_iob = ${round(microBolus, 2)} ")
                    }
                    if (microBolus + IOB > iobTHvirtualHARDshower) {
                        microBolus = iobTHvirtualHARDshower - IOB
                        rT.reason.append("microBolus = iobTHvirtualHARDshower - IOB ; iobThUser ${iobThUser} IOB ${round(IOB, 2)} ")
                        rT.reason.append("microBolus + IOB ov iobTHvirtualHARD fast rise 0.716 shower = ${round(microBolus - microBolus1, 2)} diff  ")
                    }
                    rT.reason.append(" CHANGED SIZE for shower time ")
// =====================================================
// LOW IOB ACCELERATION GLITCH THROTTLE
// =====================================================
                } else if (
                    bg_acce > 0.30 * 18 &&
                    Delta >= 0.50 * 18 &&
                    IOB < 0.70
                ) {
                    microBolus = microBolus * 0.7
                    rT.reason.append("microBolus = microBolus * 0.7 ; microBolus = ${round(microBolus, 2)} ")
                    rT.reason.append(" CHANGED SIZE 0.712 fast rise 0.712 for low IOB accel glitch ")
// =====================================================
// SENSOR GLITCH / SWING DAMPING
// =====================================================
                } else if (Delta >= 0.40 * 18 &&
                    SDelta <= 0.5 * Delta &&
                    LDelta <= 0.10 * Delta &&
                    COB <= 20 &&
                    bg < 10.0 * 18
                ) {
                    microBolus = microBolus * 0.5
                    rT.reason.append("microBolus = microBolus * 0.5 ; microBolus = ${round(microBolus, 2)} ")
                    rT.reason.append(" CHANGED SIZE fast rise 0.511 for sudden glitchy 0.5 rises after gentle fall ; sensor swings 0.5 smb ")
// =====================================================
// SENSOR GLITCH2 / SWING DAMPING
// =====================================================
                } else if (Delta >= 0.50 * 18 &&
                    SDelta >= 0.40 * 18 &&
                    LDelta <= 0.15 * 18 &&
                    bg < 10.0 * 18
                ) {
                    microBolus = microBolus * 0.5
                    rT.reason.append("microBolus = microBolus * 0.5 ; microBolus = ${round(microBolus, 2)} ")
                    rT.reason.append(" CHANGED SIZE fast rise 0.512 for sudden glitchy 0.5 rises after  fall ; sensor swings 0.5 smb ")
// =====================================================
// POST CARBS / SWING DAMPING
// =====================================================
                } else if (Delta >= 0.50 * 18 &&
                    nowHour >= 18 &&
                    COB > 10 &&
                    Delta <= 0.95 * 18 &&
                    bg < 10.0 * 18
                ) {
                    microBolus = microBolus * 0.7
                    rT.reason.append("microBolus = microBolus * 0.7 ; microBolus = ${round(microBolus, 2)} ")
                    rT.reason.append(" CHANGED SIZE fast rise 0.713 for sudden glitchy 0.5 rises after  fall ; sensor swings 0.5 smb ")
// =====================================================
// HIGH TT PROTECTION [low TT 4.0 but delta High]
// =====================================================
                } else if (Delta >= 0.25 * 18 &&
                    SDelta >= 0.20 * 18 &&
                    bg < 10.0 * 18 &&
                    profile.temptargetSet &&
                    target_bg <= 4.1 * 18 &&
                    rawDelta5Mgdl >= 0.25 * 18 && rawDelta1Mgdl >= 0.25 * 18 && aapsDelta1Mgdl >= 0.25 * 18
                ) {
                    microBolus = microBolus * 0.5
                    rT.reason.append("Delta ov0.25 && SDelta ov0.20 && profile.temptargetSet && target_bg <= 4.1 microBolus = ${round(microBolus, 2)} ")
                    rT.reason.append(" CHANGED SIZE for [high delta during low TT for low delta only] highTT 0.5 smb? ")
// =====================================================
// GLITCH ZERO SMB
// =====================================================
                } else if (Delta > 1.0 * 18 &&
                    LDelta < -0.05 * 18 &&
                    bg < 162
                ) {
                    microBolus = 0.0
                    rT.reason.append("glitch 0.0 ")
// =====================================================
// SENSOR GLITCH3 / SHORT SPIKE WITHOUT TREND
// =====================================================
                } else if (
                    SDelta >= 0.25 * 18 &&
                    LDelta <= 0.08 * 18 &&
                    SDelta >= 3.0 * (LDelta + 0.01)
                ) {
                    microBolus = microBolus * 0.5
                    rT.reason.append(" CHANGED SIZE fast rise 0.513 short spike no trend ")
                } else if (
                    libreActive &&
                    !profile.temptargetSet
                ) {
// =====================================================
// FAST RISE HANDLING
// =====================================================
                    if (
                        bg > 6.0 * 18 &&
                        bg < 12.0 * 18 &&
                        COB <= 25 &&
                        Delta >= 0.25 * 18 &&
                        SDelta >= 0.10 * 18 &&
                        ((IOB > 0.12 * profile.max_iob) // was 0.10 — gently widens the fully-uncapped (no fast-rise
                            // caps at all) zone to slightly more accumulated daytime IOB before this whole
                            // capping cascade starts applying
                            || (nowHour >= 22 || nowHour <= 5)) &&
                        rawDelta5Mgdl >= 0.25 * 18 && rawDelta1Mgdl >= 0.25 * 18 && aapsDelta1Mgdl >= 0.25 * 18
                    ) {
                        if (Delta >= 1.0 * 18 &&
                            SDelta >= 1.0 * 18 &&
                            LDelta >= 1.0 * 18
                        ) {
                            microBolus = microBolus * 0.2
                            rT.reason.append("microBolus = microBolus * 0.2 ; microBolus = ${round(microBolus, 2)} ")
                            rT.reason.append(" CHANGED SIZE 0.201 for fast rise 0.201 smb ")
                        } else if (Delta >= 0.55 * 18 &&
                            SDelta >= 0.30 * 18 &&
                            Delta < 1.0 * 18 &&
                            SDelta < 1.0 * 18
                        ) {
                            if (bg > 8.8 * 18) {
                                microBolus = microBolus * 0.6
                                rT.reason.append("microBolus = microBolus * 0.8 ; microBolus = ${round(microBolus, 2)} ")
                                rT.reason.append(" CHANGED SIZE 0.602 for moderate fast rise 0.602 ")
                            } else if (bg > 8.0 * 18) {
                                microBolus = microBolus * 0.65
                                rT.reason.append("microBolus = microBolus * 0.7 ; microBolus = ${round(microBolus, 2)} ")
                                rT.reason.append(" CHANGED SIZE 0.653 for moderate fast rise 0.653 ")
                            } else if (bg <= 8.0 * 18 &&
                                (microBolus > ThresholForFastRise ||
                                    nowHour <= 8)
                            ) {
                                microBolus = microBolus * 0.5
                                rT.reason.append("microBolus ov ${round(ThresholForFastRise, 2)} = microBolus * 0.5 ; microBolus = ${round(microBolus, 2)} ")
                                rT.reason.append(" CHANGED SIZE 0.504 for moderate fast rise 0.504 ")
                            } else if (bg <= 8.0 * 18 && microBolus <= ThresholForFastRise) {
                                rT.reason.append("smbUn  0.564 for microBolus  = ${round(microBolus, 2)} ")
                            }
                        } else if (Delta >= 0.25 * 18 &&
                            SDelta >= 0.15 * 18 &&
                            Delta < 0.55 * 18 &&
                            SDelta < 0.55 * 18
                        ) {
                            if (bg > 8.8 * 18) {
                                microBolus = microBolus * 0.9 // was 0.85 — slight loosening, mild-tier daytime rise
                                rT.reason.append("microBolus = microBolus * 0.9 ; microBolus = ${round(microBolus, 2)} ")
                                rT.reason.append(" CHANGED SIZE 0.855 for mild fast rise 0.855 ")
                            } else if (bg > 8.0 * 18) {
                                microBolus = microBolus * 0.85 // was 0.8 — slight loosening
                                rT.reason.append("microBolus = microBolus * 0.85 ; microBolus = ${round(microBolus, 2)} ")
                                rT.reason.append(" CHANGED SIZE 0.806 for mild fast rise 0.806 ")
                            } else if (bg <= 8.0 * 18 &&
                                (microBolus > ThresholForFastRise ||
                                    (nowHour <= 8 && nowHour >= 3))
                            ) {
                                microBolus = microBolus * 0.75 // was 0.7 — slight loosening
                                rT.reason.append("microBolus ov ${round(ThresholForFastRise, 2)} = microBolus * 0.75 ; microBolus = ${round(microBolus, 2)} ")
                                rT.reason.append(" CHANGED SIZE 0.707 for mild fast rise 0.707 ")
                            } else {
                                rT.reason.append("smbUn 0.707 for 0.025 * profile.max_iob microBolus = ${round(microBolus, 2)} ")
                            }
                        }
                    } else if (Delta >= 0.25 * 18 &&
                        SDelta >= 0.10 * 18 &&
                        Delta < 0.35 * 18 &&
                        rawDelta5Mgdl >= 0.25 * 18 && rawDelta1Mgdl >= 0.25 * 18 && aapsDelta1Mgdl >= 0.25 * 18
                    ) {
                        if (microBolus > ThresholForFastRise ||
                            nowHour <= 8
                        ) {
                            microBolus = microBolus * 0.7 // was 0.6 — slight loosening, narrowest/earliest-stage rise tier
                            rT.reason.append("microBolus ov ${round(ThresholForFastRise, 2)} = microBolus * 0.7 ; microBolus = ${round(microBolus, 2)} ")
                            rT.reason.append(" CHANGED SIZE 0.608 for early fast rise 0.608 ")
                        } else {
                            rT.reason.append("smbUn 0.608 for 0.030 * profile.max_iob microBolus = ${round(microBolus, 2)} ")
                        }
// =====================================================
// HIGHER BG FAST RISE
// =====================================================
                    } else if (Delta >= 0.9 * 18 &&
                        SDelta >= 0.7 * 18 &&
                        bg > 11.5 * 18 &&
                        bg < 13.5 * 18 &&
                        IOB > ThresholForFastRise * profile.max_iob &&
                        COB <= 25 &&
                        rawDelta5Mgdl >= 0.9 * 18 && rawDelta1Mgdl >= 0.9 * 18 && aapsDelta1Mgdl >= 0.9 * 18
                    ) {
                        microBolus = microBolus * 0.75
                        rT.reason.append("microBolus = microBolus * 0.75 ; microBolus = ${round(microBolus, 2)} ")
                        rT.reason.append(" CHANGED SIZE 0.759 for fast rise 0.759 smb ")
// =====================================================
// EARLY MORNING EXTRA FAST RISE GUARD (rev 2 — raw-delta based)
// =====================================================
                    // Original (smoothed-Delta-based, plus an unconditional nowHour<4 IOB-only branch)
                    // was disabled: the nowHour<4 branch capped SMB overnight on IOB alone, no rise
                    // required at all — much blunter than intended, hence the original comment-out.
                    // This revival drops that branch entirely and keeps only the rise-gated one, but
                    // swaps the trigger from smoothed Delta to raw rΔ5: a same-day incident (7/22, ~9:41am)
                    // showed smoothed Delta peaking at just 0.26mmol (never enough to fire the old 0.3
                    // trigger) while raw rΔ5 hit 0.83mmol and rΔ1 hit 1.69mmol in the same window —
                    // smoothed Delta was structurally too damped to catch a brief, real bump.
                    // rawDelta15Mgdl >= 0.2mmol is corroboration (confirms a sustained move, not a single
                    // noisy raw reading); rawDelta5 > rawDelta15*1.5 confirms the short-term rate is
                    // running well ahead of the medium-term one — the signature of a fresh reversal
                    // (e.g. off a preceding fall) rather than an already-established, longer rise.
                    // Window widened to 6-9am (was 8-9am first draft): the Shower/Twilight block covering
                    // 5-9am requires Steps60M < 10/100, and everyday movement around a real shower can
                    // exceed that — this guard has no step gate, so it backstops Shower's own blind spot
                    // across most of its window rather than just the incident's exact hour.
                    // rawDelta15Mgdl's own corroboration/divergence checks are skipped (not failed) at
                    // its 9999.0 sentinel — a comparison against that sentinel would near-never pass,
                    // silently defeating the cap on missing data, the opposite of this file's "still cap
                    // if unsure" convention for these blocks. Missing rΔ15 falls back to rΔ5 alone.
                    } else if (
                        nowHour >= 6 && nowHour < 10 &&
                        IOB > 0.075 * profile.max_iob &&
                        COB <= 25 &&
                        immediateRawDelta5Mgdl >= 0.5 * 18 &&
                        (rawDelta15Mgdl >= 9999.0 ||
                            (rawDelta15Mgdl >= 0.2 * 18 && immediateRawDelta5Mgdl > rawDelta15Mgdl * 1.5))
                    ) {
                        microBolus = microBolus * 0.8
                        rT.reason.append("microBolus = microBolus * 0.8 ; microBolus = ${round(microBolus, 2)} ")
                        rT.reason.append(" CHANGED SIZE 0.810b early-AM immediate-raw-rise guard: rawD5 ${convert_bg2(immediateRawDelta5Mgdl)} rawD15 ${convert_bg2(rawDelta15Mgdl)} ")
//=====================================================
// TWILIGHT / OTHER HOURS SMB LIMITING
// =====================================================
                    } else if (((nowHour >= 6) && (nowHour <= 8)) &&
                        bg < 9.0 * 18 &&
                        Delta < 1.0 * 18 &&
                        SDelta < 1.0 * 18 &&
                        (Steps60M ?: 0) < 10 &&
                        COB <= 25
                    ) {
                        if (microBolus > 0.02 * profile.max_iob) {
                            microBolus = 0.02 * profile.max_iob
                            rT.reason.append("nowHour ${nowHour} ")
                            rT.reason.append("(Steps60M ?: 0) ${(Steps60M ?: 0)} ")
                            rT.reason.append("CHANGED SIZE 0.211 Twilight fast rise 0.211 microBolus = 0.2 * [etc]profile.max_iob ${round(microBolus, 2)} ")
                        }
                        if (microBolus + IOB > 0.075 * profile.max_iob) {
                            microBolus = 0.075 * profile.max_iob - IOB
                            rT.reason.append("microBolus = 0.75 * profile.max_iob - IOB ; 0.75 * profile.max_iob ${round(0.075 * profile.max_iob, 2)} IOB ${round(IOB, 2)} ")
                            rT.reason.append("CHANGED SIZE 0.7512 fast rise 0.7512 microBolus + IOB ov 0.75 * profile.max_iob microBolus = 0.75 * [etc ]profile.max_iob - IOB ${round(microBolus, 2)} ")
                        }
                        rT.reason.append(" CHANGED SIZE SMB other hours ")
// =====================================================
// DEFAULT: NO SMB SIZE CHANGE
// =====================================================
                    } else {
                        rT.reason.append(" NOT CHANGED SIZE SMB ")
                    }
                }
// =====================================================
// HIGH STEPS: SMB SIZE CHANGE
// =====================================================
                if (microBolus > ThresholForFastRise &&
                    ((Steps30M ?: 0) > 1500 ||
                        (Steps60M ?: 0) > 600 ||
                        (Steps180M ?: 0) > 1500)
                ) {
                    microBolus = microBolus * 0.70
                    rT.reason.append("microBolus = microBolus * 0.7 extra; microBolus = ${round(microBolus, 2)} ")
                }
// =====================================================
// LOW-STEPS-THRESHOLD FAST-RISE EXTRA CUT (daytime, low HP2 only)
// =====================================================
                // User-requested additional trim on top of the fast-rise caps above. Tighter step
                // thresholds than the HIGH STEPS block above (that one's a coarse catch-all at
                // 1500/600/1500 over 30/60/180min) -- these are meant to catch smaller amounts of
                // movement specifically while HP2 (hypo-prediction) says a real hypo risk is present.
                // HP2 is calculated live by OpenAPSAutoISFPlugin with UKF delta and gated COBt,
                // then passed here so this dosing cut uses exactly the checked history formula. "Daytime" deliberately NARROWER than isDaytime elsewhere in
                // this codebase (01:01-22:00) -- per user request this excludes both TWILIGHT/OTHER
                // HOURS (6-8am) and the EARLY MORNING FAST RISE GUARD (6-10am) above by simply not
                // overlapping their hour windows at all, rather than tracking which named branch fired.
                // 09:00-21:00 (nowHour 9..20). Gated on microBolus != microBolusFullUncapped so this
                // only trims an SMB the fast-rise logic above already reduced, not an otherwise-uncapped
                // one. NOT validated against real data yet -- the 300/100/30/10 step thresholds and 6.5
                // HP2 cutoff are first guesses from the user; revisit if too aggressive or too loose.
                val isDaytimeForStepsCut = nowHour in 9..20
                if (microBolus != microBolusFullUncapped &&
                    isDaytimeForStepsCut &&
                    ((Steps60M ?: 0) > 300 ||
                        (Steps30M ?: 0) > 100 ||
                        (Steps15M ?: 0) > 30 ||
                        (Steps5M ?: 0) > 10)
                ) {
                    val hp2Now = profile.hypo_prediction_2
                    if (hp2Now != null && hp2Now <= 6.5) {
                        microBolus = microBolus * 0.75
                        rT.reason.append("microBolus = microBolus * 0.75 low-steps fast-rise extra cut (HP2=${round(hp2Now, 2)}); microBolus = ${round(microBolus, 2)} ")
                    }
                }
// =====================================================
// RECENT DELIVERY BOOST: SKIP ALL FAST-RISE CAPS
// =====================================================
                // If BolusGiven bg3 or BolusGivenMild fired within the last 30 min, restore the full
                // uncapped SMB — an unexpectedly high spike now reverts more readily (the raw-delta-driven
                // reversal logic), so the fast-rise reductions above aren't needed in that window.
                // NB: microBolusFullUncapped was snapshotted AFTER the anti-stacking x0.9 trim, so that
                // trim survives this restore — only the fast-rise caps are undone.
                // (Earlier profile_percentage>100 variant of this bypass was removed by user choice.)
                if (smbBoostRecent && microBolus != microBolusFullUncapped) {
                    rT.reason.append(" fast-rise caps skipped (BolusGiven/Mild boost within 30 min): microBolus ${round(microBolus, 2)} -> ${round(microBolusFullUncapped, 2)} ")
                    microBolus = microBolusFullUncapped
                }
// =====================================================
// RECENT-LOW REBOUND GUARD
// =====================================================
                // Halves the SMB when a low happened recently AND there is carb activity -- the signature
                // of correcting a REBOUND rather than a fresh excursion. Motivated by a real overnight
                // episode (28 Jul): BG fell 11.7 -> 5.6 on ~4.9U IOB, rescue carbs were taken, BG rebounded
                // to 9.6, ~2.3U was delivered against that rebound over 40 min, and BG then went to 3.8.
                // At the moment of that dosing every signal looked benign -- BG 9.0-9.6 and rising, HP2 7.2
                // -- so no BGL/HP2 threshold could have caught it. Only the CONTEXT (a low, then carbs, then
                // a rise) distinguishes it, which is what this reads.
                //
                // Placed deliberately AFTER the smbBoostRecent restore above: that restore undoes the
                // fast-rise caps, and a recent low should outrank a recent boost, so this must not be
                // something the boost can bypass. Before the rounding below so the result still lands on a
                // valid pump increment.
                //
                // Carb evidence is COB **or** uci, not COB alone: rescue carbs are frequently under-logged
                // or not logged at all, which is exactly when COB reads zero while the rebound is real.
                // uci is this file's own unclamped deviation-based carb-impact estimate (the same value
                // exported as UAMci and drawn as the UAM line), converted to g/5min via csf so the
                // threshold is readable in grams. 0.3 g/5min sits just above the observed noise floor
                // (UAMci idles at roughly +/-0.2 in the exports) and well below a real absorption rate
                // (~0.6 measured off COB decay), so it distinguishes genuine carb action from baseline
                // drift. Both that threshold and the 0.5 factor are first estimates -- the reason string
                // logs COB, uci and the factor so they can be tuned against real fires.
                if (recentLowActive && microBolus > 0.0) {
                    val uciGrams = if (csf > 0.0) uci / csf else 0.0
                    if (COB > 0.0 || uciGrams >= 0.3) {
                        val beforeLowGuard = microBolus
                        microBolus = microBolus * 0.5
                        rT.reason.append(" recent-low rebound guard: SMB ${round(beforeLowGuard, 2)} -> ${round(microBolus, 2)} (LowBG=50recent, COB=${round(COB, 1)}, uci=${round(uciGrams, 2)}g/5m) ")
                    }
                }
// =====================================================
// LATE FAST-RISE TAPER + CUMULATIVE SMB CAP (rolling 30 min)
// =====================================================
                // A fast rise can keep requesting SMBs after substantial insulin has already accumulated.
                // Leave the early response unchanged, then progressively trim only the later FastRise
                // requests. This runs after smbBoostRecent restoration so that boost cannot undo it.
                val fastRiseNow =
                    libreActive &&
                        bg > 6.0 * 18 &&
                        bg < 12.0 * 18 &&
                        COB <= 25 &&
                        Delta >= 0.25 * 18 &&
                        SDelta >= 0.10 * 18 &&
                        rawDelta5Mgdl >= 0.25 * 18 &&
                        rawDelta1Mgdl >= 0.25 * 18 &&
                        aapsDelta1Mgdl >= 0.25 * 18

                if (fastRiseNow && microBolus > 0.0) {
                    val lateFastRiseFactor = when {
                        smbSum30Min >= 1.9 -> 0.50
                        smbSum30Min >= 1.5 -> 0.75
                        else -> 1.0
                    }
                    if (lateFastRiseFactor < 1.0) {
                        val beforeLateFastRise = microBolus
                        microBolus *= lateFastRiseFactor
                        rT.reason.append(" late FastRise SMB30 ${round(smbSum30Min, 2)}U: x${round(lateFastRiseFactor, 2)} ${round(beforeLateFastRise, 2)} -> ${round(microBolus, 2)} ")
                    }
                }

                // Do not gate the final allowance on fastRiseNow: at the end of the Aug 8 event the raw
                // rise flattened for two cycles while SMB continued, precisely when accumulated insulin
                // still needed protection. Trim to the remaining budget rather than always zeroing.
                val smbCap30Min = 2.1
                val smbAllowance30Min = (smbCap30Min - smbSum30Min).coerceAtLeast(0.0)
                if (microBolus > smbAllowance30Min) {
                    val before30MinCap = microBolus
                    microBolus = smbAllowance30Min
                    rT.reason.append(" 30min SMB cap: ${round(before30MinCap, 2)} -> ${round(microBolus, 2)} (last30min ${round(smbSum30Min, 2)}U of ${round(smbCap30Min, 2)}U cap) ")
                    consoleError.add("Cumulative SMB cap: ${round(smbSum30Min, 2)}U already delivered in last 30min vs ${round(smbCap30Min, 2)}U cap -> microBolus ${round(before30MinCap, 2)} trimmed to ${round(microBolus, 2)} ")
                }
// =====================================================
// CUMULATIVE SMB CAP (rolling 10 min)
// =====================================================
                // Limits TOTAL SMB units delivered in any rolling 10-min window. This is the only control
                // here that measures cumulative delivery rather than a level (iobTH, the IOB gate) or a
                // rate (the anti-stack interval test), and the 6->7 Aug 2026 night is why it exists: both
                // damaging bursts ran at ~60s intervals, which at 1-minute sensor cadence is simply normal
                // operation and so invisible to an interval test, and both started from low IOB, which a
                // level ceiling cannot restrain -- it only caps the endpoint. Cumulative amount is the one
                // dimension that separates them from routine dosing.
                //
                // Values are measured, not guessed: across recent exports the 10-min SMB total runs a
                // median of 0.20U with the 90th percentile at 0.60U, while the two bursts were 1.50U and
                // 1.10U. So the tight cap of 0.6U sits exactly at p90 -- it leaves ~90% of normal
                // operation completely untouched and clips only the top decile. Outside the window it is
                // 1.5U, a pure backstop just under the 1.95U maximum ever observed, since meals
                // legitimately need more.
                //
                // Window is 00:30-04:00, NOT the 22:00-08:00 used by the stack trim's IOB gate. That is
                // deliberate and narrower: of the two bursts on 6->7 Aug, only the 01:08 one was harmful.
                // The 23:10 burst was appropriate -- BGL rose to 8.9 afterwards and held 8.5-8.9, so it
                // was matching a real rise, and a cap covering it would have left the night higher for no
                // benefit. A cumulative limiter cannot tell the two apart on size or rate (1.50U vs 1.10U,
                // both ~60s apart); they differed only in the residual IOB underneath. Restricting the
                // window to the hours when eating is implausible and a stack-on-residue is the likely
                // explanation is the way to separate them.
                //
                // Placed LAST, after every other modifier including the smbBoostRecent restore, so nothing
                // can bypass it -- a cumulative safety limit should outrank any single-cycle boost.
                // Trims to the remaining allowance rather than zeroing outright, so a partial dose still
                // goes out when only part of the budget is left.
                val inDeepNightSmbWindow = nowMinsOfDay >= 30 && nowMinsOfDay < 240   // 00:30 - 04:00
                val smbCap10Min = if (inDeepNightSmbWindow) 0.6 else 1.5
                val smbAllowanceLeft = smbCap10Min - smbSum10Min
                if (microBolus > smbAllowanceLeft) {
                    val beforeCumCap = microBolus
                    microBolus = if (smbAllowanceLeft > 0.0) smbAllowanceLeft else 0.0
                    rT.reason.append(" 10min SMB cap: ${round(beforeCumCap, 2)} -> ${round(microBolus, 2)} (last10min ${round(smbSum10Min, 2)}U of ${round(smbCap10Min, 2)}U cap) ")
                    consoleError.add("Cumulative SMB cap: ${round(smbSum10Min, 2)}U already delivered in last 10min vs ${round(smbCap10Min, 2)}U cap -> microBolus ${round(beforeCumCap, 2)} trimmed to ${round(microBolus, 2)} ")
                }
// =====================================================
// SUB-7.5MMOL HEAVY-DELIVERY COOLDOWN (hard pause, not a rolling cap)
// =====================================================
                if (sub75HeavyDeliveryCooldown) {
                    val beforeSub75Cooldown = microBolus
                    microBolus = 0.0
                    rT.reason.append(" Sub75HeavyDelivery cooldown: ${round(beforeSub75Cooldown, 2)} -> 0.0 ")
                    consoleError.add("Sub75HeavyDelivery cooldown active -> microBolus ${round(beforeSub75Cooldown, 2)} zeroed ")
                }
// =====================================================
// ROUND / ZERO / APPLY SMB
// =====================================================
                microBolus = Math.floor(microBolus * roundSMBTo) / roundSMBTo

                if (offsetSoZeroSMB) {
                    microBolus = 0.0
                    rT.reason.append(" offsetSoZeroSMB($offsetSoZeroSMB) Microbolusing := 0")
                } else if (microBolus <= 0) {
                    microBolus = 0.0
                }

                // IOB-Action patch (2026-07-12): tolerance 6s -> 15s. The bolus RECORD lands ~10s
                // after the decision (queue + enact start), so with a 60s BG cadence the next
                // cycle always saw lastBolusAge ~50s < 54s and waited: SMBInterval=1 effectively
                // delivered every OTHER minute during sustained demand (log-verified 15:44-15:51:
                // deltas 119-121s). 15s tolerance (gate 45s) absorbs the enact offset so the
                // setting means what it says; amounts stay capped by smb ratio and the iobTH bands.
                if (lastBolusAge > SMBInterval - 15.0) {   // 15s tolerance (enact offset, see above)
                    if (microBolus > 0) {
                        rT.units = microBolus
                        rT.reason.append("Microbolusing ${round(microBolus, 2)}U. ")
                    }
                } else {
                    val nextBolusMins = (SMBInterval - lastBolusAge) / 60.0
                    val nextBolusSeconds = (SMBInterval - lastBolusAge) % 60
                    val waitingSeconds = round(nextBolusSeconds, 0) % 60
                    val waitingMins = round(nextBolusMins - waitingSeconds / 60.0, 0)
                    rT.reason.append("Waiting ${waitingMins.withoutZeros()}m ${waitingSeconds.withoutZeros()}s to microbolus again.")
                }

                if (durationReq > 0) {
                    rT.rate = smbLowTempReq
                    rT.duration = durationReq
                    return rT
                }
            }
            var maxSafeBasal = getMaxSafeBasal(profile)
            if (rate > maxSafeBasal) {
                rT.reason.append("adj. req. rate: ${basalForDisplay(rate)} to maxSafeBasal: ${basalForDisplay(maxSafeBasal)}, ")
                rate = round_basal(maxSafeBasal)
            }

            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            if (insulinScheduled >= TDDfactor * insulinReq * 2) {
                rT.reason.append("${currenttemp.duration}m@${basalForDisplay(currenttemp.rate)} ov 2 * insulinReq. Setting temp basal of ${basalForDisplay(rate)}U/hr. ")
                return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
            }

            if (currenttemp.duration == 0) {
                rT.reason.append("no temp, setting " + basalForDisplay(rate) + "U/hr. ")
                return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
            }

            if (currenttemp.duration > 5 &&  (round_basal(rate) <= round_basal(currenttemp.rate))) {
                rT.reason.append("temp ${basalForDisplay(currenttemp.rate)} ov~ req ${basalForDisplay(rate)}U/hr. ")
                return rT
            }

            rT.reason.append("temp ${basalForDisplay(currenttemp.rate)} un ${basalForDisplay(rate)}U/hr. ")
            return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
        }
    }
}

/*
DetermineBasalAutoISF.ktaisf321_593
*/
