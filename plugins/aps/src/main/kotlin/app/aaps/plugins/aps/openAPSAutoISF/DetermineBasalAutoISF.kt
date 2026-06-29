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
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    fun convert_bg2(value: Double): String =
        String.format("%.2f", profileUtil.fromMgdlToUnits(value))

    fun convert_isf(value: Double): String =
        String.format("%.1f", profileUtil.fromMgdlToUnits(value))

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
        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate
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
        steps5M: Int
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
                rT.reason.append(". Replacing high temp basal of ${currenttemp.rate} with neutral temp of $basal")
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
                rT.reason.append(". Temp ${currenttemp.rate} <= current basal ${round(basal, 2)}U/hr; doing nothing. ")
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
                consoleError.add("Sensitivity ratio set to $sensitivityRatio based on temp target of ${convert_bg(target_bg)}; ")
            } else if (stepActivityDetected) {
                sensitivityRatio = activityRatio
            } else if (stepInactivityDetected) {
                sensitivityRatio = activityRatio
            }
        } else {
            consoleError.add("Sensitivity ratio unchanged: 1.0")
            sensitivityRatio = autosens_data.ratio
            consoleError.add("Autosens ratio: $sensitivityRatio; ")
        }
        var iobTH_reduction_ratio = 1.0
        if (iob_threshold_percent != 100) {
            iobTH_reduction_ratio = profile_percentage / 100.0 * sensitivityRatio
        }
        basal = profile.current_basal * sensitivityRatio
        basal = round_basal(basal)
        if (basal != profile_current_basal)
            consoleError.add("adjusting basal from $profile_current_basal to $basal;")
        else
            consoleError.add("Basal unchanged: $basal;")

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
        consoleError.add("CR: ${profile.carb_ratio}")

        if (autoIsfMode) {
            consoleError.add("----------------------------------")
            consoleError.add("start AutoISF ${profile.autoISF_version} __ 2320TDDF126")
            consoleError.add("----------------------------------")
            consoleError.add("Sensitivity: ${autosens_data.sensResult}")
            consoleError.addAll(auto_isf_consoleLog)
            consoleError.addAll(auto_isf_consoleError)
        }
        var lower_SMB = 1.00
        if (profile.smb_delivery_ratio_min > 0.8) {
            lower_SMB = profile.smb_delivery_ratio_min
        }
        var TDDfactor = lower_SMB
        if (preferences.get(BooleanKey.ApsAutoIsfTddFactor)) {
            TDDfactor = min(1.2, max(0.80, tddRatio))
            consoleError.add("TDDfactor ${round(TDDfactor, 3)} from tddRatio ${round(tddRatio, 3)} (tdd7D ${round(tdd7D, 1)}U)")
            rT.reason.append("TDDfactor ${round(TDDfactor, 3)} from tddRatio ${round(tddRatio, 3)} (tdd7D ${round(tdd7D, 1)}U)")
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

        consoleError.add("UAM Impact: $uci mg/dL per 5m; UAM Duration: $UAMduration hours")
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
        consoleError.add(" avgPredBG: ${convert_bg(avgPredBG)} COB: ${meal_data.mealCOB} / ${meal_data.carbs}")
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
            " 2320TDDF126 COB: ${round(meal_data.mealCOB, 1).withoutZeros()}, Dev: ${convert_bg(deviation.toDouble())}, BGI: ${convert_bg(bgi)}, ISF: ${convert_isf(sens)}, CR: ${
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
        consoleError.add("bgAccel_ISF_weight is ${round(profile.bgAccel_ISF_weight, 4)} ;;")
        consoleError.add("delta_accl: " + round(delta_accl, 1).withoutZeros() + " ; ")
        rT.reason.append("bgAccel_ISF_weight is ${round(profile.bgAccel_ISF_weight, 4)} ;;")
        rT.reason.append("delta_accl: ${round(delta_accl, 1).withoutZeros()} ;")
        rT.reason.append("Delta: ${convert_bg2(Delta)} ;")
        rT.reason.append("SDelta: ${convert_bg2(SDelta)} ;")
        rT.reason.append("LDelta: ${convert_bg2(LDelta)} ;")
        consoleError.add("IOB: " + round(IOB, 2) + " ; ")
        consoleError.add("Delta: " + convert_bg2(Delta) + " ; ")
        consoleError.add("SDelta: " + convert_bg2(SDelta) + " ; ")
        consoleError.add("LDelta: " + convert_bg2(LDelta) + " ; ")
        consoleError.add("pp_ISF_weight is ${round(profile.pp_ISF_weight, 4)} ;;")
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
        rT.reason.append("bgAccel_ISF_weight is ${round(profile.bgAccel_ISF_weight, 4)} ;;")
        rT.reason.append("dura_ISF_weight is ${round(profile.dura_ISF_weight, 2)} ;;")
        rT.reason.append("higher_ISFrange_weight is ${round(profile.higher_ISFrange_weight, 2)} ;;")
        consoleError.add("pp_ISF_weight is ${round(profile.pp_ISF_weight, 4)} ;;")
        rT.reason.append("pp_ISF_weight is ${round(profile.pp_ISF_weight, 4)} ;;")
        consoleError.add("Steps60M: " + Steps60M + " ; ")
        consoleError.add("Steps30M: " + Steps30M + " ; ")
        consoleError.add("TwilightTimeDec: " + TwilightTimeDec + " ; ")

        //================================================================================/
        var lastCarbAge = 361.0
        if (meal_data.carbs != null && meal_data.carbs > 0) {
            lastCarbAge = round(((systemTime - meal_data.lastCarbTime) / 60000.0), 2)
        }

        var CarbAge = lastCarbAge

        val high_SMB = profile.smb_delivery_ratio_max
        var varOffset: Double = high_SMB * 18.0  // 0.5→9, 0.6→10.8, 1.0→18

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

        var offset1 = false
        var offset2 = false
        var offset3 = false

        if (profile.temptargetSet && target_bg < 4.45 * 18 && target_bg > 4.35 * 18 && bg > 4.5 * 18 && Delta > 0.02 * 18) {
            eatSoon = true
        }

        var highProfile = false
        var carbsSugg = profile.carbsReqThreshold
        var carbsSuggOrig = carbsSugg
        var boostOrig = false

        var profileSwitch = 100

        if (carbsSugg == 1) {
            offset1 = true
        } else if (carbsSugg == 9) {
            varOffset -= 9
        } else if (carbsSugg == 10) {
            varOffset += 9
        } else if (carbsSugg == 2) {
            offset2 = true
        } else if (carbsSugg == 3) {
            offset3 = true
        } else if (carbsSugg == 8) {
            offset2 = true
            offset3 = true
        } else if (carbsSugg == 7) {
            offset1 = true
            offset3 = true
        } else if (carbsSugg == 6) {
            offset1 = true
            offset2 = true
        } else if (carbsSugg == 4) {
            offset1 = true
            offset2 = true
            offset3 = true
        }
        var targetBgOffset = min(targetBgOrig + varOffset, 126.0)

        var enableButton = false

        if (!isEven(profile.max_iob)) {
            enableButton = true
        }
        val nowHour = LocalDateTime.now().hour
        // Omnipod Dash requires durationInMinutes divisible by 30; 15-min temps are rejected.
        val standardTempDuration = 30

        if (enableButton && nowHour >= 1 && nowHour <= 7 && offset1) {
            varOffset = varOffset - 9
            rT.reason.append("offset1 ${offset1} ;")
            rT.reason.append("enableButton && nowHour ov=1 && nowHour un=7 && offset1 :varOffset = varOffset - 9 ${convert_bg(varOffset)} ;")
        } else if (nowHour >= 8 && nowHour < 10 && offset2) {
            varOffset = varOffset - 9
            rT.reason.append("offset2 ${offset2} ;")
            rT.reason.append("nowHour ov= 8 && nowHour un 10 && offset2:varOffset = varOffset - 9 ${convert_bg(varOffset)} ;")
        }
        if ((enableButton && delta_accl < -10) || (nowHour >= 20 && offset3)) {
            varOffset = varOffset + 9
            rT.reason.append("offset3 ${offset3} ;")
            rT.reason.append("enableButton && delta_accl un -1; varOffset = varOffset + 9 ${convert_bg(varOffset)} ;")
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
        rT.reason.append("offset1 ${offset1} ;")
        rT.reason.append("offset2 ${offset2} ;")
        rT.reason.append("offset3 ${offset3} ;")
        rT.reason.append("varOffset ${convert_bg(varOffset)} ;")
        rT.reason.append("varOffset ${varOffset} ;")
        consoleError.add("offset1 ${offset1} ;")
        consoleError.add("offset2 ${offset2} ;")
        consoleError.add("offset3 ${offset3} ;")
        consoleError.add("varOffset ${convert_bg(varOffset)} ;")
        consoleError.add("varOffset ${varOffset} ;")
        varOffset = min(36.0, varOffset)

        rT.reason.append("varOffset ($varOffset)")
        targetBgOffset = min(targetBgOrig + varOffset, 126.0)
        rT.reason.append("targetBgOffset = min(targetBgOrig + varOffset, 7.0): ${convert_bg(targetBgOffset)} ;")
        if (bg < targetBgOffset && (COB == 0.0 || (COB < 5 && CarbAge > 120))) {
            offsetSoZeroSMB = true
            rT.reason.append("bg un targetBgOffset && no/low COB: ")
        }

        consoleError.add("target_bgOrigmmcarbsSuggOrig: " + carbsSuggOrig + " ; ")
        consoleError.add("target_bgOrigmm: " + target_bgOrigmm + " ; ")
        consoleError.add("targetBgOrig: " + convert_bg(targetBgOrig) + " ; ")
        consoleError.add("targetBgOffset: " + convert_bg(targetBgOffset) + " ; ")
        consoleError.add("offsetSoZeroSMB: " + offsetSoZeroSMB + " ; ")
        consoleError.add("enableButton: " + enableButton + " ; ")
        rT.reason.append("carbsSuggOrig: " + carbsSuggOrig + " ; ")
        rT.reason.append("offsetSoZeroSMB: ${offsetSoZeroSMB} ;")
        rT.reason.append("enableButton: ${enableButton} ;")
        rT.reason.append("targetBgOffset: ${convert_bg(targetBgOffset)} ;")
        rT.reason.append("targetBgOrig: ${convert_bg(targetBgOrig)} ;")
        rT.reason.append("target_bgOrigmm: ${target_bgOrigmm} ;")

        if (!(bg < targetBgOffset && (COB == 0.0 || (COB < 5 && CarbAge > 120)))) {
            offsetSoZeroSMB = false
            rT.reason.append("offsetSoZeroSMBcleared: BG ov targetBgOffset, SMB restored; ")
            if (bg > targetBgOffset) {
                // boostActive restored
            }
        }

        rT.reason.append(
            "COB: ${round(meal_data.mealCOB, 1).withoutZeros()}, Dev: ${convert_bg(deviation.toDouble())}, BGI: ${convert_bg(bgi)}, ISF: ${convert_isf(sens)}, CR: ${
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
            consoleError.add("maxDelta ${convert_bg(maxDelta)} > ${100 * maxDeltaPercentage}% of BG ${convert_bg(bg)} - disabling SMB")
            rT.reason.append("maxDelta " + convert_bg(maxDelta) + " > " + 100 * maxDeltaPercentage + "% of BG " + convert_bg(bg) + ": SMB disabled; ")
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
        consoleError.add("naive_eventualBG: $naive_eventualBG bgUndershoot: $bgUndershoot zeroTempDuration $zeroTempDuration zeroTempEffect: $zeroTempEffect carbsReq: $carbsReq")
        if (carbsReq >= profile.carbsReqThreshold && minutesAboveThreshold <= 45) {
            rT.carbsReq = carbsReq
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsReq add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }

        if (bg < threshold && iob_data.iob < -profile.current_basal * 20 / 60 && minDelta > 0 && minDelta > expectedDelta) {
            rT.reason.append("IOB ${iob_data.iob} un ${round(-profile.current_basal * 20 / 60, 2)}")
            rT.reason.append(" and minDelta ${convert_bg(minDelta)} > expectedDelta ${convert_bg(expectedDelta)}; ")
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
                    rT.reason.append(", but Delta ${convert_bg(tick.toDouble())} > expectedDelta ${convert_bg(expectedDelta)}")
                } else {
                    rT.reason.append(", but Min. Delta ${minDelta.toFixed2()} > Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(basal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
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
                rT.reason.append(", ${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} is a lot less than needed. ")
                return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
            }
            if (currenttemp.duration > 5 && rate >= currenttemp.rate * 0.8) {
                rT.reason.append(", temp ${currenttemp.rate} ~< req ${round(rate, 2)}U/hr. ")
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
                    rT.reason.append(", setting ${round(rate, 2)}U/hr. ")
                }
                return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
            }
        }

        if (minDelta < expectedDelta) {
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
                    return setTempBasal(basal, standardTempDuration, profile, rT, currenttemp)
                }
            }
        }
        if (min(eventualBG, minPredBG) < max_bg) {
            if (!(microBolusAllowed && enableSMB)) {
                rT.reason.append("${convert_bg(eventualBG)}-${convert_bg(minPredBG)} in range: no temp required")
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
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
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                return rT
            } else {
                rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
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
            consoleError.add("TDDfactor ${TDDfactor} max_iob ${max_iob}  insulinReq = $insulinReq")
            rT.reason.append("TDDfactor ${TDDfactor} max_iob ${max_iob}  insulinReq = $insulinReq")
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
                    consoleError.add("IOB ${iob_data.iob} ov mealInsulinReq = $mealInsulinReq")
                    consoleError.add("profile.maxUAMSMBBasalMinutes: ${profile.maxUAMSMBBasalMinutes} profile.current_basal: ${profile.current_basal}")
                    maxBolus = round(smb_max_range * profile.current_basal * profile.maxUAMSMBBasalMinutes / 60, 1)
                } else {
                    consoleError.add("profile.maxSMBBasalMinutes: ${profile.maxSMBBasalMinutes} profile.current_basal: ${profile.current_basal}")
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
                rT.reason.append(" insulinReq $insulinReq")
                if (microBolus >= maxBolus) {
                    rT.reason.append("; maxBolus $maxBolus")
                }
                if (durationReq > 0) {
                    rT.reason.append("; setting ${durationReq}m low temp of ${smbLowTempReq}U/h")
                }
                rT.reason.append(". ")

                val lastBolusAge = (systemTime - iob_data.lastBolusTime) / 1000.0
                val SMBInterval = min(10, max(1, profile.SMBInterval)) * 60.0
                consoleError.add("naive_eventualBG $naive_eventualBG,${durationReq}m ${smbLowTempReq}U/h temp needed; last bolus ${round(lastBolusAge / 60.0, 1)}m ago; maxBolus: $maxBolus")
                consoleError.add("offsetSoZeroSMB $offsetSoZeroSMB")
                val libreActive = (glucose_status as? GlucoseStatusAutoIsf)?.libreActive == true
                val LibreTrue = if (libreActive) 1.0 else 1.0

                val high_SMB2 = profile.smb_delivery_ratio_max
                val bg_range_SMB = profile.smb_delivery_ratio_bg_range
                val delivery_ratio = profile.smb_delivery_ratio
                rT.reason.append("TDDfactor = ${TDDfactor} high_SMB= ${high_SMB2} TDDfactor= ${round(TDDfactor, 2)}   ")
                rT.reason.append("TDDfactor= ${TDDfactor} high_SMB= ${high_SMB2}  ")
                var ThresholForFastRise = TDDfactor * 0.030 * profile.max_iob
                ThresholForFastRise = round(ThresholForFastRise, 2)
                consoleError.add("Delta threshold TDDfactor * 0.030 * profile.max_iob = ($TDDfactor * 0.030 * ${profile.max_iob})= ${ThresholForFastRise} ")
                rT.reason.append("Delta threshold TDDfactor * 0.030 * profile.max_iob = ($TDDfactor * 0.030 * ${profile.max_iob})= ${ThresholForFastRise} ")

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
                    iobThUser < 71
                ) {
                    val iobTHvirtualHARDshower = 0.075 * profile.max_iob
                    val microBolus1 = microBolus
                    if (microBolus > 0.02 * profile.max_iob) {
                        microBolus = 0.02 * profile.max_iob
                        rT.reason.append("microBolus fast rise 0.713 capped 0.02 * profile.max_iob = ${microBolus} ")
                    }
                    if (microBolus + IOB > iobTHvirtualHARDshower) {
                        microBolus = iobTHvirtualHARDshower - IOB
                        rT.reason.append("microBolus = iobTHvirtualHARDshower - IOB ; iobThUser ${iobThUser} IOB ${IOB} ")
                        rT.reason.append("microBolus + IOB ov iobTHvirtualHARD fast rise 0.714 shower = ${microBolus - microBolus1} diff = ")
                    }
                } else if (((nowHour >= 5) && (nowHour < 10)) &&
                    bg <= 8.0 * 18 &&
                    (Steps60M ?: 0) < 100 &&
                    COB <= 0 &&
                    !profile.temptargetSet &&
                    Delta >= 0.35 * 18 &&
                    SDelta >= 0.15 * 18 &&
                    iobThUser < 71
                ) {
                    val iobTHvirtualHARDshower = 0.075 * profile.max_iob
                    val microBolus1 = microBolus
                    if (microBolus > 0.02 * profile.max_iob) {
                        microBolus = 0.02 * profile.max_iob
                        rT.reason.append("microBolus capped fast rise 0.715 0.02 * profile.max_iob = ${microBolus} ")
                    }
                    if (microBolus + IOB > iobTHvirtualHARDshower) {
                        microBolus = iobTHvirtualHARDshower - IOB
                        rT.reason.append("microBolus = iobTHvirtualHARDshower - IOB ; iobThUser ${iobThUser} IOB ${IOB} ")
                        rT.reason.append("microBolus + IOB ov iobTHvirtualHARD fast rise 0.716 shower = ${microBolus - microBolus1} diff  ")
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
                    rT.reason.append("microBolus = microBolus * 0.7 ; microBolus = ${microBolus} ")
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
                    rT.reason.append("microBolus = microBolus * 0.5 ; microBolus = ${microBolus} ")
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
                    rT.reason.append("microBolus = microBolus * 0.5 ; microBolus = ${microBolus} ")
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
                    rT.reason.append("microBolus = microBolus * 0.7 ; microBolus = ${microBolus} ")
                    rT.reason.append(" CHANGED SIZE fast rise 0.713 for sudden glitchy 0.5 rises after  fall ; sensor swings 0.5 smb ")
// =====================================================
// HIGH TT PROTECTION [low TT 4.0 but delta High]
// =====================================================
                } else if (Delta >= 0.25 * 18 &&
                    SDelta >= 0.20 * 18 &&
                    bg < 10.0 * 18 &&
                    profile.temptargetSet &&
                    target_bg <= 4.1 * 18
                ) {
                    microBolus = microBolus * 0.5
                    rT.reason.append("Delta ov0.25 && SDelta ov0.20 && profile.temptargetSet && target_bg <= 4.1 microBolus = ${microBolus} ")
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
                        ((IOB > 0.10 * profile.max_iob)
                            || (nowHour >= 18 || nowHour <= 5))
                    ) {
                        if (Delta >= 1.0 * 18 &&
                            SDelta >= 1.0 * 18 &&
                            LDelta >= 1.0 * 18
                        ) {
                            microBolus = microBolus * 0.2
                            rT.reason.append("microBolus = microBolus * 0.2 ; microBolus = ${microBolus} ")
                            rT.reason.append(" CHANGED SIZE 0.201 for fast rise 0.201 smb ")
                        } else if (Delta >= 0.55 * 18 &&
                            SDelta >= 0.30 * 18 &&
                            Delta < 1.0 * 18 &&
                            SDelta < 1.0 * 18
                        ) {
                            if (bg > 8.8 * 18) {
                                microBolus = microBolus * 0.6
                                rT.reason.append("microBolus = microBolus * 0.8 ; microBolus = ${microBolus} ")
                                rT.reason.append(" CHANGED SIZE 0.602 for moderate fast rise 0.602 ")
                            } else if (bg > 8.0 * 18) {
                                microBolus = microBolus * 0.65
                                rT.reason.append("microBolus = microBolus * 0.7 ; microBolus = ${microBolus} ")
                                rT.reason.append(" CHANGED SIZE 0.653 for moderate fast rise 0.653 ")
                            } else if (bg <= 8.0 * 18 &&
                                (microBolus > ThresholForFastRise ||
                                    nowHour <= 8)
                            ) {
                                microBolus = microBolus * 0.5
                                rT.reason.append("microBolus ov ${ThresholForFastRise}  = microBolus * 0.5 ; microBolus = ${microBolus} ")
                                rT.reason.append(" CHANGED SIZE 0.504 for moderate fast rise 0.504 ")
                            } else if (bg <= 8.0 * 18 && microBolus <= ThresholForFastRise) {
                                rT.reason.append("smbUn  0.564 for microBolus  = ${microBolus} ")
                            }
                        } else if (Delta >= 0.25 * 18 &&
                            SDelta >= 0.15 * 18 &&
                            Delta < 0.55 * 18 &&
                            SDelta < 0.55 * 18
                        ) {
                            if (bg > 8.8 * 18) {
                                microBolus = microBolus * 0.6
                                rT.reason.append("microBolus = microBolus * 0.6 ; microBolus = ${microBolus} ")
                                rT.reason.append(" CHANGED SIZE 0.605 for mild fast rise 0.605 ")
                            } else if (bg > 8.0 * 18) {
                                microBolus = microBolus * 0.5
                                rT.reason.append("microBolus = microBolus * 0.5 ; microBolus = ${microBolus} ")
                                rT.reason.append(" CHANGED SIZE 0.506 for mild fast rise 0.506 ")
                            } else if (bg <= 8.0 * 18 &&
                                (microBolus > ThresholForFastRise ||
                                    nowHour <= 8)
                            ) {
                                microBolus = microBolus * 0.4
                                rT.reason.append("microBolus ov \${ThresholForFastRise}  = microBolus  * 0.4 ; microBolus = ${microBolus} ")
                                rT.reason.append(" CHANGED SIZE 0.407 for mild fast rise 0.507 ")
                            } else {
                                rT.reason.append("smbUn 0.407 for 0.025 * profile.max_iob microBolus = ${microBolus} ")
                            }
                        }
                    } else if (Delta >= 0.25 * 18 &&
                        SDelta >= 0.10 * 18 &&
                        Delta < 0.35 * 18
                    ) {
                        if (microBolus > ThresholForFastRise ||
                            nowHour <= 8
                        ) {
                            microBolus = microBolus * 0.6
                            rT.reason.append("microBolus ov ${ThresholForFastRise}  = microBolus  * 0.6 ; microBolus = ${microBolus} ")
                            rT.reason.append(" CHANGED SIZE 0.608 for early fast rise 0.608 ")
                        } else {
                            rT.reason.append("smbUn 0.608 for 0.030 * profile.max_iob microBolus = ${microBolus} ")
                        }
// =====================================================
// HIGHER BG FAST RISE
// =====================================================
                    } else if (Delta >= 0.9 * 18 &&
                        SDelta >= 0.7 * 18 &&
                        bg > 11.5 * 18 &&
                        bg < 13.5 * 18 &&
                        IOB > ThresholForFastRise * profile.max_iob &&
                        COB <= 25
                    ) {
                        microBolus = microBolus * 0.75
                        rT.reason.append("microBolus = microBolus * 0.75 ; microBolus = ${microBolus} ")
                        rT.reason.append(" CHANGED SIZE 0.759 for fast rise 0.759 smb ")
// =====================================================
// EARLY MORNING EXTRA FAST RISE GUARD
// =====================================================
                    } else if ((Delta >= 0.3 * 18 &&
                            SDelta >= 0.1 * 18 &&
                            nowHour < 10 &&
                            IOB > 0.075 * profile.max_iob &&
                            COB <= 25) ||
                        (nowHour < 4 &&
                            IOB > 0.10 * profile.max_iob)
                    ) {
                        microBolus = microBolus * 0.8
                        rT.reason.append("microBolus = microBolus * 0.8 ; microBolus = ${microBolus} ")
                        rT.reason.append(" CHANGED SIZE 0.810 for fast rise 0.810 smb ")
// =====================================================
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
                            rT.reason.append("CHANGED SIZE 0.0211 Twilight fast rise 0.0211 microBolus = 0.02 * profile.max_iob ${microBolus} ")
                        }
                        if (microBolus + IOB > 0.075 * profile.max_iob) {
                            microBolus = 0.075 * profile.max_iob - IOB
                            rT.reason.append("microBolus = 0.075 * profile.max_iob - IOB ; 0.075 * profile.max_iob ${0.075 * profile.max_iob} IOB ${IOB} ")
                            rT.reason.append("CHANGED SIZE 0.07512 fast rise 0.07512 microBolus + IOB ov 0.075 * profile.max_iob microBolus = 0.075 * profile.max_iob - IOB ${microBolus} ")
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
                    rT.reason.append("microBolus = microBolus * 0.7 extra; microBolus = ${microBolus} ")
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

                if (lastBolusAge > SMBInterval - 6.0) {
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

                if (durationReq > 0) {
                    rT.rate = smbLowTempReq
                    rT.duration = durationReq
                    return rT
                }
            }
            var maxSafeBasal = getMaxSafeBasal(profile)
            if (rate > maxSafeBasal) {
                rT.reason.append("adj. req. rate: ${round(rate, 2)} to maxSafeBasal: ${maxSafeBasal.withoutZeros()}, ")
                rate = round_basal(maxSafeBasal)
            }

            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            if (insulinScheduled >= TDDfactor * insulinReq * 2) {
                rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} ov 2 * insulinReq. Setting temp basal of ${round(rate, 2)}U/hr. ")
                return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
            }

            if (currenttemp.duration == 0) {
                rT.reason.append("no temp, setting " + round(rate, 2).withoutZeros() + "U/hr. ")
                return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
            }

            if (currenttemp.duration > 5 &&  (round_basal(rate) <= round_basal(currenttemp.rate))) {
                rT.reason.append("temp ${(currenttemp.rate).toFixed2()} ov~ req ${round(rate, 2).withoutZeros()}U/hr. ")
                return rT
            }

            rT.reason.append("temp ${currenttemp.rate.toFixed2()} un ${round(rate, 2).withoutZeros()}U/hr. ")
            return setTempBasal(rate, standardTempDuration, profile, rT, currenttemp)
        }
    }
}

/*
DetermineBasalAutoISF.kt320TDDF126
*/
