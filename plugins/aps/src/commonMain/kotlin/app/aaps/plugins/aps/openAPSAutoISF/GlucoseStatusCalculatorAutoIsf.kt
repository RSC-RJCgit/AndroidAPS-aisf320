package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.smoothing.DisplayRawSmoothing
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.plugins.aps.openAPSAutoISF.extensions.asRounded
import app.aaps.plugins.aps.openAPSAutoISF.extensions.log
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@SingleIn(AppScope::class)
@Inject
class GlucoseStatusCalculatorAutoIsf(
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val decimalFormatter: DecimalFormatter,
    private val deltaCalculator: DeltaCalculator,
    private val preferences: Preferences,
    private val displayRawSmoothing: DisplayRawSmoothing,
) {

    fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatusAutoIsf? {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null
        // 2026-11-14, per explicit request: the raw readings too, for the 1 minute parabola fit of v3 (below)
        val orig = iobCobCalculator.ads.getBgReadingsDataTableCopy()

        var sizeRecords = data.size
        if (sizeRecords == 0) {
            aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==0")
            return null
        }
        if (data[0].timestamp < dateUtil.now() - 7 * 60 * 1000L && !allowOldData) {
            aapsLogger.debug(LTag.GLUCOSE, "oldData")
            return null
        }
        val now = data[0]
        val nowDate = now.timestamp
        val cgm = now.sourceSensor
        // As v3 (AutoISF 3.2.1): Libre 2/3, or any source with "apply smoothing" on, may fit on its own 1 minute readings
        val fslReally = cgm == SourceSensor.LIBRE_2 || cgm == SourceSensor.LIBRE_3 || preferences.get(BooleanKey.FslApplySmoothing)
        var fslMinDur = 15
        if (sizeRecords == 1) {
            aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==1")
            return GlucoseStatusAutoIsf(
                glucose = now.recalculated,
                noise = 0.0,
                delta = 0.0,
                shortAvgDelta = 0.0,
                longAvgDelta = 0.0,
                date = nowDate,
                duraISFminutes = 0.0,
                duraISFaverage = now.value,
                parabolaMinutes = 0.0,
                deltaPl = 0.0,
                deltaPn = 0.0,
                bgAcceleration = 0.0,
                a0 = now.value,
                a1 = 0.0,
                a2 = 0.0,
                corrSqu = 0.0
            ).asRounded()
        }

        val deltaResult = deltaCalculator.calculateDeltas(data)

        // calculate 2 variables for 5% range; still using 5 minute data
        val bw = 0.05
        var sumBG: Double = now.recalculated
        var oldAvg: Double = sumBG
        var minutesDur = 0L
        var n = 1
        for (i in 1 until sizeRecords) {
            if (data[i].value > 39 && !data[i].filledGap) {
                n += 1
                val then = data[i]
                val thenDate: Long = then.timestamp
                //  stop the series if there was a CGM gap greater than 13 minutes, i.e. 2 regular readings
                //  needs shorter gap for Libre?
                if (((nowDate - thenDate) / (1000.0 * 60)).roundToInt() - minutesDur > 13) {
                    break
                }
                if (then.recalculated > oldAvg * (1 - bw) && then.recalculated < oldAvg * (1 + bw)) {
                    sumBG += then.recalculated
                    oldAvg = sumBG / n  // was: (i + 1)
                    minutesDur = ((nowDate - thenDate) / (1000.0 * 60)).roundToLong()
                } else {
                    break
                }
            }
        }

        // calculate best parabola and determine delta by extending it 5 minutes into the future
        // after https://www.codeproject.com/Articles/63170/Least-Squares-Regression-for-Quadratic-Curve-Fitti
        //
        //  y = a2*x^2 + a1*x + a0      or
        //  y = a*x^2  + b*x  + c       respectively
        @Suppress("SpellCheckingInspection")
        var duraP = 0.0
        var deltaPl = 0.0
        var deltaPn = 0.0
        var bgAcceleration = 0.0
        var corrMax = 0.0
        var a0 = 0.0
        var a1 = 0.0
        var a2 = 0.0
        //var b = 0.0

        // 2026-11-14, per explicit request: the parabola fit of v3 (AutoISF 3.2.1). With 1 minute readings
        // (the newest three within 3 minutes) it fits on the raw readings, shortest fit 10 minutes, instead of the
        // 5 minute grid, where a 1 minute source leaves most points filled in and the fit skips them (bg_acce and
        // the parabola stayed at zero). Other sources keep the grid and a shortest fit of 15 minutes, as before.
        // Times are in seconds and rescaled by 300 (5 minutes), the same numbers as the former scaleTime = 300.
        var use1MinuteRaw = false
        if (fslReally && orig.size > 2 && orig[0].timestamp - orig[2].timestamp < 3 * 60000) {
            use1MinuteRaw = true
            sizeRecords = orig.size
            // Shortest parabola with a 1 minute CGM: a setting, 10 minutes as in v3 (2026-11-14, from blaqone's AAPS-autoISF)
            fslMinDur = preferences.get(IntKey.ApsAutoIsfParabolaMinMinutes)
        }
        // The values the 1 minute fit uses: the readings as in v3, or with "Parabola on smoothed values" on (off by default),
        // the same readings smoothed by the UKF (the smoothing of the raw-BG graph line). Falls back to the readings.
        val fitSmoothed = use1MinuteRaw && preferences.get(BooleanKey.ApsAutoIsfParabolaSmoothed)
        val fitValues: List<Double> =
            if (!use1MinuteRaw) emptyList()
            else if (fitSmoothed) displayRawSmoothing.smoothForDisplay(orig.map { it.timestamp to it.value }).takeIf { it.size == orig.size } ?: orig.map { it.value }
            else orig.map { it.value }
        aapsLogger.debug(
            LTag.GLUCOSE, "BgReadings stamp=${orig.firstOrNull()?.timestamp}; value=${orig.firstOrNull()?.value}; Libre=$fslReally; " +
                "FitUsesRaw=$use1MinuteRaw; FitSmoothed=$fitSmoothed; fitMinutes=$fslMinDur; BgBucketed value=${now.value}; recalc=${now.recalculated}; filled=${now.filledGap}; CGM=$cgm"
        )

        if (sizeRecords > 3) {
            var sy = 0.0 // y
            var sx = 0.0 // x
            var sx2 = 0.0 // x^2
            var sx3 = 0.0 // x^3
            var sx4 = 0.0 // x^4
            var sxy = 0.0 // x*y
            var sx2y = 0.0 // x^2*y
            val time0 = if (use1MinuteRaw) orig[0].timestamp else data[0].timestamp
            var tiLast = 0.0
            val scaleTime = 1.0
            val scaleBg = 1.0

            var n = 0
            for (i in 0 until sizeRecords) {
                val noGap = if (use1MinuteRaw) true else !data[i].filledGap
                val usableValue = if (use1MinuteRaw) fitValues[i] else data[i].value
                if (usableValue > 39 && noGap) {
                    n += 1
                    val thenDate: Long
                    val bg: Double
                    if (use1MinuteRaw) {
                        thenDate = orig[i].timestamp
                        bg = fitValues[i] / scaleBg
                    } else {    // all other including standard 5m CGM smoothed
                        val then = data[i]
                        thenDate = then.timestamp
                        bg = then.recalculated / scaleBg
                    }
                    val ti = (thenDate - time0) / 1000.0 / scaleTime
                    if (-ti * scaleTime > 47 * 60) {                       // skip records older than 47.5 minutes
                        break
                    } else if (ti < tiLast - 11.0 * 60 / scaleTime) {      // stop scan if a CGM gap > 11 minutes is detected
                        if (i < 3 || -ti * scaleTime < fslMinDur * 60) {   // history too short for fit
                            duraP = -tiLast * scaleTime / 60.0
                            deltaPl = 0.0
                            deltaPn = 0.0
                            bgAcceleration = 0.0
                            corrMax = 0.0
                            a0 = 0.0
                            a1 = 0.0
                            a2 = 0.0
                        }
                        break
                    }
                    tiLast = ti
                    sx += ti
                    sx2 += ti.pow(2.0)
                    sx3 += ti.pow(3.0)
                    sx4 += ti.pow(4.0)
                    sy += bg
                    sxy += ti * bg
                    sx2y += ti.pow(2.0) * bg
                    var detH = 0.0
                    var detA = 0.0
                    var detB = 0.0
                    var detC = 0.0
                    if (n > 3 && -ti * scaleTime > fslMinDur * 60) {
                        detH = sx4 * (sx2 * n - sx * sx) - sx3 * (sx3 * n - sx * sx2) + sx2 * (sx3 * sx - sx2 * sx2)
                        detA = sx2y * (sx2 * n - sx * sx) - sxy * (sx3 * n - sx * sx2) + sy * (sx3 * sx - sx2 * sx2)
                        detB = sx4 * (sxy * n - sy * sx) - sx3 * (sx2y * n - sy * sx2) + sx2 * (sx2y * sx - sxy * sx2)
                        detC = sx4 * (sx2 * sy - sx * sxy) - sx3 * (sx3 * sy - sx * sx2y) + sx2 * (sx3 * sxy - sx2 * sx2y)
                    }
                    if (detH != 0.0) {
                        val a: Double = detA / detH * scaleBg * (300 / scaleTime).pow(2.0)
                        val b = detB / detH * scaleBg * (300 / scaleTime)
                        val c: Double = detC / detH * scaleBg
                        val yMean = sy / n
                        var sSquares = 0.0
                        var sResidualSquares = 0.0
                        for (j in 0..i) {
                            val beforeValue: Double
                            val beforeDate: Long
                            if (use1MinuteRaw) {
                                beforeValue = fitValues[j]
                                beforeDate = orig[j].timestamp
                            } else {
                                beforeValue = data[j].recalculated
                                beforeDate = data[j].timestamp
                            }
                            val scaledBg = beforeValue / scaleBg
                            sSquares += (scaledBg - yMean).pow(2.0)
                            val deltaT: Double = (beforeDate - time0) / 1000.0 / 300.0
                            val bgj: Double = a * deltaT.pow(2.0) + b * deltaT + c
                            sResidualSquares += (scaledBg - bgj / scaleBg).pow(2.0)
                        }
                        var rSqu = 0.0
                        if (sSquares != 0.0) {
                            rSqu = 1 - sResidualSquares / sSquares
                        }
                        if (rSqu >= corrMax) {
                            corrMax = rSqu
                            duraP = -ti * scaleTime / 60.0 // remember we are going backwards in time
                            val delta5Min = 1.0
                            deltaPl = -(a * (-delta5Min).pow(2.0) - b * delta5Min)    // 5 minute slope from last fitted bg ending at this bg, i.e. t=0
                            deltaPn = (a * delta5Min.pow(2.0) + b * delta5Min)        // 5 minute slope to next fitted bg starting from this bg, i.e. t=0
                            bgAcceleration = 2 * a
                            a0 = c
                            a1 = b
                            a2 = a
                        }
                    }
                }
            }
        }
        // End parabola fit

        return GlucoseStatusAutoIsf(
            glucose = now.recalculated,
            date = nowDate,
            noise = 0.0, //for now set to nothing as not all CGMs report noise
            shortAvgDelta = deltaResult.shortAvgDelta,
            delta = deltaResult.delta,
            longAvgDelta = deltaResult.longAvgDelta,
            duraISFminutes = minutesDur.toDouble(),
            duraISFaverage = oldAvg,
            parabolaMinutes = duraP,
            deltaPl = deltaPl,
            deltaPn = deltaPn,
            corrSqu = corrMax,
            bgAcceleration = bgAcceleration,
            a0 = a0,
            a1 = a1,
            a2 = a2,
        ).also { aapsLogger.debug(LTag.GLUCOSE, it.log(decimalFormatter)) }.asRounded()
    }

    companion object {
        //this will be useful in the next step when I replace magic numbers with vals
    }
}