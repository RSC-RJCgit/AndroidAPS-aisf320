package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.AIV
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.graph.data.A1DeltaDataPoint
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.GlucoseValueDataPoint
import app.aaps.core.graph.data.IsfIndicesDataPoint
import app.aaps.core.graph.data.L1DeltaDataPoint
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.NoisyBgDeltaDataPoint
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.StepsExtraDataPoint
import app.aaps.core.graph.data.StepsStackedDataPoint
import com.jjoe64.graphview.series.DataPoint
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

class PrepareBgDataWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var automationStateService: AutomationStateInterface

    // Themed context for rh.gac() theme-attribute color lookups (acce/bg/dura ISF colors in
    // isfIndicesSeries below) — applicationContext alone doesn't carry the app's light/dark theme,
    // same reasoning as PrepareIobAutosensGraphDataWorker's own ctx.
    private var ctx: Context

    init {
        ctx = rh.getThemedCtx(context)
    }

    class PrepareBgData(
        val iobCobCalculator: IobCobCalculator, // cannot be injected : HistoryBrowser uses different instance
        val overviewData: OverviewData
    )

    override suspend fun doWorkAndLog(): Result {

        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as PrepareBgData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        val toTime = data.overviewData.toTime
        val fromTime = data.overviewData.fromTime
        data.overviewData.maxBgValue = Double.MIN_VALUE
        data.overviewData.bgReadingsArray = persistenceLayer.getBgReadingsDataFromTimeToTime(fromTime, toTime, false)
        // Same dominant-ISF-weight color logic/colors as the SMB arrow override in PrepareTreatmentsDataWorker.kt.
        val aivList = persistenceLayer.getAutoIsfValuesFromTimeToTime(fromTime, toTime)
        val bgListArray: MutableList<DataPointWithLabelInterface> = ArrayList()
        for (bg in data.overviewData.bgReadingsArray) {
            if (bg.timestamp < fromTime || bg.timestamp > toTime) continue
            if (bg.value > data.overviewData.maxBgValue) data.overviewData.maxBgValue = bg.value
            val dp = GlucoseValueDataPoint(bg, profileUtil, rh, dateUtil)
            if (aivList.isNotEmpty()) {
                val nearest = aivList.minByOrNull { aiv -> kotlin.math.abs(aiv.timestamp - bg.timestamp) }
                if (nearest != null && kotlin.math.abs(nearest.timestamp - bg.timestamp) < T.mins(15).msecs()) {
                    val acce = kotlin.math.abs(nearest.acceIsf - 1.0)
                    val bgDev = kotlin.math.abs(nearest.bgIsf - 1.0)
                    val pp = kotlin.math.abs(nearest.ppIsf - 1.0)
                    val dura = kotlin.math.abs(nearest.duraIsf - 1.0)
                    val maxDev = maxOf(acce, bgDev, pp, dura)
                    if (maxDev > 0.01) {
                        dp.colorOverride = when {
                            acce >= maxDev  -> rh.gac(null, app.aaps.core.ui.R.attr.acceIsfColor)
                            bgDev >= maxDev -> rh.gac(null, app.aaps.core.ui.R.attr.bgIsfColor)
                            pp >= maxDev    -> rh.gac(null, app.aaps.core.ui.R.attr.ppIsfColor)
                            else            -> rh.gac(null, app.aaps.core.ui.R.attr.duraIsfColor)
                        }
                    }
                }
            }
            bgListArray.add(dp)
        }
        bgListArray.sortWith { o1: DataPointWithLabelInterface, o2: DataPointWithLabelInterface -> o1.x.compareTo(o2.x) }
        data.overviewData.bgReadingGraphSeries = PointsWithLabelGraphSeries(Array(bgListArray.size) { i -> bgListArray[i] })
        data.overviewData.maxBgValue = profileUtil.fromMgdlToUnits(data.overviewData.maxBgValue)
        if (preferences.get(UnitDoubleKey.OverviewHighMark) > data.overviewData.maxBgValue)
            data.overviewData.maxBgValue = preferences.get(UnitDoubleKey.OverviewHighMark)
        data.overviewData.maxBgValue = addUpperChartMargin(data.overviewData.maxBgValue)

        // Raw BG line (red) — gv.noise: NS unfiltered (xDrip raw) when FslSmoothing ON, or NS mgdl pre-calibration when OFF
        val rawPoints = data.overviewData.bgReadingsArray
            .filter { it.timestamp in fromTime..toTime && it.noise != null && it.noise!! > 10.0 }
            .sortedBy { it.timestamp }
            .map { DataPoint(it.timestamp.toDouble(), profileUtil.fromMgdlToUnits(it.noise!!)) }
        data.overviewData.rawBgSeries = LineGraphSeries(rawPoints.toTypedArray()).also {
            it.color = android.graphics.Color.RED
            it.thickness = 4
        }

        // Live "L=<noisy bgl> A1=<aaps 1-min delta> L1=<libre 1-min delta> A5=<aaps 5-min delta>
        // L5=<libre 5-min delta>" annotation at the current reading.
        val latest = data.overviewData.bgReadingsArray.firstOrNull()
        val noisyBg = currentNoisyBg(data.overviewData.bgReadingsArray)
        val aapsDelta = aapsOneMinuteDelta(data.overviewData.bgReadingsArray)
        val libreDelta = libreOneMinuteDelta(data.overviewData.bgReadingsArray)
        val aapsDelta5 = aapsFiveMinuteDelta(data.overviewData.bgReadingsArray)
        val libreDelta5 = libreFiveMinuteDelta(data.overviewData.bgReadingsArray)
        // Fixed 2h lookback independent of the graph's own display range, so Step30max always has its full window.
        val stepsWindowFrom = toTime - T.hours(2).msecs()
        val rawStepsCountList = persistenceLayer.getStepsCountFromTimeToTime(stepsWindowFrom, toTime)
        // On a client build (aapsclient/aapsclient2), NS device-status sync only creates local AIV +
        // APSResult records — it never populates the local StepsCount table — so stepsCountList is
        // otherwise always empty there even though the master's real step data reached the client fine,
        // just embedded as text in APSResult.reason rather than a structured field. Same fallback as
        // AutoISFHistoryDialog.kt's stepsValue()/stepsFromReason(), reconstructed per-result here since
        // this needs a max-over-window rather than a single nearest-timestamp lookup.
        val recentApsResults = persistenceLayer.getApsResults(stepsWindowFrom, toTime)
        val stepsCountList = rawStepsCountList.ifEmpty {
            stepsFromReason(recentApsResults)
        }
        val latestSteps = stepsCountList.maxByOrNull { it.timestamp }
        // Same client-sync-fallback pattern as steps above: DR=/acWt=/Lslope= must come from the synced
        // APSResult.reason text (see the dedicated reason lines in OpenAPSAutoISFPlugin.kt), not from
        // local preferences — preferences never sync via NS, so reading them directly would show the
        // client's OWN (irrelevant) local values instead of the master's, on an AAPSClient build.
        val latestApsReason = recentApsResults.maxByOrNull { it.date }?.reason
        // AIV window (2h, same rounding-margin reasoning as elsewhere in this file) — shared by the
        // green line's acce=/IOd5= fields below and isfIndicesSeries further down, so only queried once.
        val recentAiv = persistenceLayer.getAutoIsfValuesFromTimeToTime(toTime - T.hours(2).msecs(), toTime)
        val latestAiv = recentAiv.maxByOrNull { it.timestamp }
        // SMB boluses for the green line's Sint= field (mirrors AutoIsfHistoryExporter's
        // smbInterval5SecStr) — real treatment records, sync to the client normally like any other
        // bolus. 2h query window (not just 5min from toTime) for the same reason as the AIV window
        // above: toTime can sit up to ~59min ahead of "now", so a narrow window anchored to toTime
        // could miss every recent SMB entirely; smbInterval5SecStr does its own precise 5-min
        // filtering (anchored to latest.timestamp, not toTime) once this is fetched.
        val recentSmbBoluses = persistenceLayer.getBolusesFromTimeToTime(toTime - T.hours(2).msecs(), toTime, ascending = false)
            .filter { it.type == BS.Type.SMB }
        data.overviewData.noisyBgDeltaSeries =
            if (latest != null && noisyBg != null && aapsDelta != null && libreDelta != null) {
                val delta5Txt = " A5=${aapsDelta5?.let { formatMmolDelta(it) } ?: "--"} L5=${libreDelta5?.let { formatMmolDelta(it) } ?: "--"}"
                // Current MJ state (value only, no "MJ=" prefix), from the most recent MJ-lifecycle
                // careportal note. Uses notes (which sync to the client) rather than automationStateService
                // (which doesn't sync), so it's correct on both master and client — same source as the
                // history table's MJ column, so this is just "the latest MJ value".
                val mjTxt = " " + latestMjState(latest.timestamp)
                val label = "L=${profileUtil.fromMgdlToStringInUnits(noisyBg)} " +
                    "A1=${formatMmolDelta(aapsDelta)} L1=${formatMmolDelta(libreDelta)}" + delta5Txt + mjTxt
                PointsWithLabelGraphSeries(
                    arrayOf<DataPointWithLabelInterface>(
                        NoisyBgDeltaDataPoint(latest.timestamp, profileUtil.fromMgdlToUnits(noisyBg), label, rh)
                    )
                )
            } else PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

        // Libre 1-min delta label (green), attached directly to the current Libre graph point (same
        // x/timestamp and y/BG value as the actual plotted point) rather than a fixed row.
        data.overviewData.l1DeltaSeries =
            if (latest != null && noisyBg != null && libreDelta != null) {
                // Sign repeated 4 times at the end too (e.g. "+0.25++++") for visibility — the leading
                // sign alone is easy to miss on a small rotated graph label.
                val formatted = formatMmolDelta(libreDelta)
                val label = formatted + formatted.first().toString().repeat(4)
                PointsWithLabelGraphSeries(
                    arrayOf<DataPointWithLabelInterface>(
                        L1DeltaDataPoint(latest.timestamp, profileUtil.fromMgdlToUnits(noisyBg), label, rh)
                    )
                )
            } else PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

        // AAPS (smoothed) 1-min delta label (orange), same style as the Libre one above but attached to
        // the smoothed/plotted BG value (latest.value) rather than the raw noise value — the two lines
        // usually sit at slightly different heights, so anchoring each label to its own line's actual
        // point keeps them from landing on top of each other. 5 leading spaces additionally offset this
        // one from the Libre label so the two rotated texts don't run into each other when close together.
        data.overviewData.a1DeltaSeries =
            if (latest != null && aapsDelta != null) {
                val formatted = formatMmolDelta(aapsDelta)
                val label = "     " + formatted + formatted.first().toString().repeat(4)
                PointsWithLabelGraphSeries(
                    arrayOf<DataPointWithLabelInterface>(
                        A1DeltaDataPoint(latest.timestamp, profileUtil.fromMgdlToUnits(latest.value), label, rh)
                    )
                )
            } else PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

        // Two rows near the top (just under the green raw-BG/delta line), split so the steps row is
        // just steps: "S5=<5min> S15=<15min> S30=<30min> S60=<60min>". The extra row above it
        // (DR=/AW=/LS=/acce=/Sint=/IOd5=) is a separate line with its own spacing.
        data.overviewData.stepsStackedSeries =
            if (latest != null && latestSteps != null) {
                val avgInterval = avgReadingIntervalSec(data.overviewData.bgReadingsArray)
                val label = "S5=${latestSteps.steps5min} S15=${latestSteps.steps15min}" +
                    " S30=${latestSteps.steps30min} S60=${latestSteps.steps60min}" +
                    " I5=${avgInterval?.let { it.roundToInt().toString() } ?: "--"}"
                PointsWithLabelGraphSeries(
                    arrayOf<DataPointWithLabelInterface>(
                        StepsStackedDataPoint(latest.timestamp, profileUtil.fromMgdlToUnits(latest.value), label, rh)
                    )
                )
            } else PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

        // "DR= AW= LS= acce= Sint= IOd5=" — spaced (this row isn't cramped like the steps row is), with
        // acce=/Sint=/IOd5= from the AIV table, matching AutoISFHistoryDialog's own "acce"(BG group)/
        // SMBi5/IOBΔ5 columns.
        data.overviewData.stepsExtraSeries =
            if (latest != null) {
                val drVal = latestApsReason?.let { doubleFromReason(it, smbRatioRegex) }
                val acWtVal = latestApsReason?.let { doubleFromReason(it, acceWeightRegex) }
                val lSlopeVal = latestApsReason?.let { doubleFromReason(it, fslSlopeRegex) }
                val drLabel = "DR=${drVal?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "--"}"
                val acWtLabel = "AW=${acWtVal?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "--"}"
                val lSlopeLabel = "LS=${lSlopeVal?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "--"}"
                val acceTxt = "acce=${latestAiv?.let { String.format(Locale.getDefault(), "%.2f", it.bgAcceleration) } ?: "--"}"
                val sintTxt = "Sint=${smbInterval5SecStr(latest.timestamp, recentSmbBoluses)}"
                val iod5Txt = "IOd5=${latestAiv?.let { iob5MinChangeStr(it, recentAiv) } ?: "--"}"
                val label = "$drLabel $acWtLabel $lSlopeLabel $acceTxt $sintTxt $iod5Txt"
                PointsWithLabelGraphSeries(
                    arrayOf<DataPointWithLabelInterface>(
                        StepsExtraDataPoint(latest.timestamp, profileUtil.fromMgdlToUnits(latest.value), label, rh)
                    )
                )
            } else PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

        // "f=<final> ac=<acce> bg=<bg> pp=<pp> du=<dura> smb=<delivered>" row for graph3, one color
        // per field — matching AutoISFHistoryDialog's own column colors exactly, INCLUDING
        // its dynamic ones: f= and smb= are colored by whichever of acce/bg/pp/dura deviates most from
        // 1.0 (dominantIsfColor()/smbIsfColor() there), falling back to red/blue respectively only when
        // nothing dominates (or, for smb=, when nothing was delivered) — not flat colors. Same
        // underlying AIV table the dialog reads from, so this is correct on both master and client:
        // NSDeviceStatusHandler reconstructs a client's local AIV row from synced RT fields every
        // cycle, same as the master writes it directly.
        // latestAiv/recentAiv computed once, above, alongside the green line's own acce=/IOd5= fields.
        data.overviewData.isfIndicesSeries =
            if (latestAiv != null) {
                // "--" for neutral (1.0) adjustment values, matching AutoISFHistoryDialog's adjStr().
                fun adjStr(v: Double) = if (v == 1.0) "--" else String.format(Locale.getDefault(), "%.2f", v)
                fun insulinStr(v: Double) = if (v == 0.0) "--" else String.format(Locale.getDefault(), "%.2f", v)
                val colorFinal = android.graphics.Color.parseColor("#FF6060")
                val colorInsulin = android.graphics.Color.parseColor("#4A9EFF")
                val colorAcce = rh.gac(ctx, app.aaps.core.ui.R.attr.acceIsfColor)
                val colorBg = rh.gac(ctx, app.aaps.core.ui.R.attr.bgIsfColor)
                val colorPp = rh.gac(ctx, app.aaps.core.ui.R.attr.ppIsfColor)
                val colorDura = rh.gac(ctx, app.aaps.core.ui.R.attr.duraIsfColor)
                // Mirrors AutoISFHistoryDialog's dominantIsfColor(): whichever of acce/bg/pp/dura is
                // furthest from 1.0 wins; ties (all within 0.01 of neutral) fall back to [fallback].
                fun dominantIsfColor(fallback: Int): Int {
                    val acceDev = kotlin.math.abs(latestAiv.acceIsf - 1.0)
                    val bgDev = kotlin.math.abs(latestAiv.bgIsf - 1.0)
                    val ppDev = kotlin.math.abs(latestAiv.ppIsf - 1.0)
                    val duraDev = kotlin.math.abs(latestAiv.duraIsf - 1.0)
                    val maxDev = maxOf(acceDev, bgDev, ppDev, duraDev)
                    return when {
                        maxDev <= 0.01 -> fallback
                        acceDev >= maxDev -> colorAcce
                        bgDev >= maxDev -> colorBg
                        ppDev >= maxDev -> colorPp
                        else -> colorDura
                    }
                }
                val colorFinalDominant = dominantIsfColor(colorFinal)
                val colorSmbDominant = if (latestAiv.smbDelivered == 0.0) colorInsulin else dominantIsfColor(colorInsulin)
                val segments = listOf(
                    "f=${String.format(Locale.getDefault(), "%.2f", latestAiv.finalIsf)}" to colorFinalDominant,
                    "ac=${adjStr(latestAiv.acceIsf)}" to colorAcce,
                    "bg=${adjStr(latestAiv.bgIsf)}" to colorBg,
                    "pp=${adjStr(latestAiv.ppIsf)}" to colorPp,
                    "du=${adjStr(latestAiv.duraIsf)}" to colorDura,
                    "smb=${insulinStr(latestAiv.smbDelivered)}" to colorSmbDominant
                )
                PointsWithLabelGraphSeries(
                    arrayOf<DataPointWithLabelInterface>(
                        IsfIndicesDataPoint(latestAiv.timestamp, segments, rh)
                    )
                )
            } else PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

        return Result.success()
    }

    private fun addUpperChartMargin(maxBgValue: Double) =
        if (profileUtil.units == GlucoseUnit.MGDL) Round.roundTo(maxBgValue, 40.0) + 80 else Round.roundTo(maxBgValue, 2.0) + 4

    // `readings` is expected newest-first (data.overviewData.bgReadingsArray, ascending=false above).
    // Delta is between whatever the two most recent readings actually are, normalized to a per-5-minute
    // rate the same way DeltaCalculator.kt does (change / minutesAgo * 5), so A1/L1 are directly
    // comparable to the Delta/SDelta/LDelta values shown elsewhere instead of being a raw per-tick diff.

    // gv.noise: same field already plotted as the red raw-BG line in doWorkAndLog() above.
    private fun currentNoisyBg(readings: List<GV>): Double? = readings.firstOrNull()?.noise

    // gv.value delta between the two newest readings, scaled to a 5-minute-equivalent rate.
    private fun aapsOneMinuteDelta(readings: List<GV>): Double? {
        if (readings.size < 2) return null
        val minutesAgo = (readings[0].timestamp - readings[1].timestamp) / 60_000.0
        if (minutesAgo <= 0.0) return null
        return (readings[0].value - readings[1].value) / minutesAgo * 5
    }

    // gv.noise delta between the two newest readings (Libre/xDrip raw native signal, not gv.value),
    // scaled to a 5-minute-equivalent rate.
    private fun libreOneMinuteDelta(readings: List<GV>): Double? {
        if (readings.size < 2) return null
        val newest = readings[0].noise ?: return null
        val previous = readings[1].noise ?: return null
        val minutesAgo = (readings[0].timestamp - readings[1].timestamp) / 60_000.0
        if (minutesAgo <= 0.0) return null
        return (newest - previous) / minutesAgo * 5
    }

    // gv.value delta against the reading nearest 5 minutes before the newest one — an actual 5-minute
    // delta (unscaled), not the 1-min-sample-rescaled-to-a-5-min-rate the *OneMinuteDelta functions
    // above compute. Null if there's no reading at least 5 minutes old to compare against.
    private fun aapsFiveMinuteDelta(readings: List<GV>): Double? {
        val newest = readings.firstOrNull() ?: return null
        val target = newest.timestamp - 5 * 60_000L
        val prior = readings.filter { it.timestamp <= target }.maxByOrNull { it.timestamp } ?: return null
        return newest.value - prior.value
    }

    // gv.noise equivalent of aapsFiveMinuteDelta above (Libre/xDrip raw native signal, not gv.value).
    private fun libreFiveMinuteDelta(readings: List<GV>): Double? {
        val newest = readings.firstOrNull() ?: return null
        val newestNoise = newest.noise ?: return null
        val target = newest.timestamp - 5 * 60_000L
        val prior = readings.filter { it.timestamp <= target && it.noise != null }.maxByOrNull { it.timestamp } ?: return null
        val priorNoise = prior.noise ?: return null
        return newestNoise - priorNoise
    }

    // Average gap in SECONDS between readings in the trailing 5 minutes (newest.timestamp-5min ..
    // newest.timestamp) — same units/style as Sint (smbInterval5SecStr): a Libre reporting every ~60s
    // shows ~60, not a flat "1.00" minutes that hides the actual jitter. Null if fewer than 2 readings
    // fell in that window. `readings` must be newest-first.
    private fun avgReadingIntervalSec(readings: List<GV>): Double? {
        val newest = readings.firstOrNull() ?: return null
        val windowStart = newest.timestamp - 5 * 60_000L
        val inWindow = readings.filter { it.timestamp in windowStart..newest.timestamp }
        if (inWindow.size < 2) return null
        val spanSec = (inWindow.maxOf { it.timestamp } - inWindow.minOf { it.timestamp }) / 1000.0
        return spanSec / (inWindow.size - 1)
    }

    // deltaMgdl -> signed mmol string, 2 decimal places (e.g. "+0.34", "-0.11").
    private fun formatMmolDelta(deltaMgdl: Double): String =
        String.format(Locale.US, "%+.2f", profileUtil.fromMgdlToUnits(deltaMgdl))

    // Current MJ automation state from the most recent MJ-lifecycle careportal note at/before [atTime]:
    // "MJ"->MJa, "MJ2"->MJ2, "MJ3"->MJ3, "MJoff*"/"A1"->NOM. Defaults to "NOM" when no note exists in
    // the 24h lookback — the midnight MJoff check resets to NOMJremains, so note-less means NOM, not
    // unknown (keeps this consistent with the history table's MJ column). Notes are TE.Type.NOTE rows
    // and sync to the client, so this shows correctly on both master and client (unlike
    // automationStateService, which doesn't sync).
    private fun latestMjState(atTime: Long): String {
        val notes = persistenceLayer.getTherapyEventDataFromTime(atTime - T.hours(24).msecs(), TE.Type.NOTE, ascending = false)
        val note = notes.firstOrNull {
            val t = it.note ?: ""
            it.timestamp <= atTime && (t == "MJ" || t == "MJ2" || t == "MJ3" || t == "MoreMJ" || t == "A1" || t == "NOMJremains" || t.startsWith("MJoff"))
        } ?: return "NOM"
        return when (note.note) {
            "MJ"     -> "MJa"
            "MJ2"    -> "MJ2"
            "MJ3"    -> "MJ3"
            "MoreMJ" -> "MJ3"   // MoreMJ advances NOMJremains -> MJ3 directly
            else     -> "NOM"   // "A1", "MJoff*", or anything unexpected → not in an MJ state
        }
    }

    // Step counts get written into DetermineBasalAutoISF.kt's reason text as "StepsXM: <value> ;"
    // (also accepts "stepsXmin is <value>", matching a second phrasing observed in synced data).
    private fun stepsFieldRegex(label: String) = Regex("""\b${label}min\s+is\s+([0-9]+)\b|\b${label}M\s*[:=]\s*([0-9]+)\b""", RegexOption.IGNORE_CASE)
    private val steps5Regex = stepsFieldRegex("[Ss]teps5")
    private val steps15Regex = stepsFieldRegex("[Ss]teps15")
    private val steps30Regex = stepsFieldRegex("[Ss]teps30")
    private val steps60Regex = stepsFieldRegex("[Ss]teps60")
    private val steps180Regex = stepsFieldRegex("[Ss]teps180")

    private fun stepsField(reason: String, regex: Regex): Int? {
        val m = regex.find(reason) ?: return null
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    // DR=/acWt=/Lslope= client-sync fallback: these match the dedicated, unconditional reason lines
    // OpenAPSAutoISFPlugin.kt appends every cycle ("SMB delivery ratio: X.XX ;", "AcceIsfWeight: X.XX ;",
    // "FslCalSlope: X.XX ;") — see its own comment on that block for why those lines exist.
    private val smbRatioRegex = Regex("""SMB delivery ratio:\s*(-?[0-9.]+)""", RegexOption.IGNORE_CASE)
    private val acceWeightRegex = Regex("""AcceIsfWeight:\s*(-?[0-9.]+)""", RegexOption.IGNORE_CASE)
    private val fslSlopeRegex = Regex("""FslCalSlope:\s*(-?[0-9.]+)""", RegexOption.IGNORE_CASE)

    private fun doubleFromReason(reason: String, regex: Regex): Double? =
        regex.find(reason)?.groupValues?.get(1)?.toDoubleOrNull()

    // Mirrors AutoIsfHistoryExporter.smbInterval5SecStr(): average gap (seconds) between SMB boluses
    // in the 5-min window ending at [atTime]. "--" if fewer than 2 SMBs in that window (no interval to
    // measure). smbBoluses is expected pre-filtered to BS.Type.SMB.
    private fun smbInterval5SecStr(atTime: Long, smbBoluses: List<BS>): String {
        val windowStart = atTime - T.mins(5).msecs()
        val inWindow = smbBoluses.filter { it.timestamp in windowStart..atTime }
        if (inWindow.size < 2) return "--"
        val spanSec = (inWindow.maxOf { it.timestamp } - inWindow.minOf { it.timestamp }).toDouble() / 1000.0
        return Math.round(spanSec / (inWindow.size - 1)).toString()
    }

    // Mirrors AutoIsfHistoryExporter.iob5MinChangeStr(): change in AIV.iob over ~5 minutes, using
    // whichever AIV record is nearest the 5-min-ago mark (within a 3-min tolerance, since AIV rows
    // aren't guaranteed to land on exact 5-min boundaries). "--" if no record is close enough.
    private fun iob5MinChangeStr(current: AIV, allAiv: List<AIV>): String {
        val target = current.timestamp - T.mins(5).msecs()
        val prior = allAiv.minByOrNull { kotlin.math.abs(it.timestamp - target) } ?: return "--"
        if (kotlin.math.abs(prior.timestamp - target) > T.mins(3).msecs()) return "--"
        return String.format(Locale.getDefault(), "%.2f", current.iob - prior.iob)
    }

    // Synthetic StepsCount records reconstructed from each APSResult's reason text, one per result,
    // used only when the local StepsCount table is empty (see stepsCountList above).
    private fun stepsFromReason(apsResults: List<APSResult>): List<SC> =
        apsResults.mapNotNull { r ->
            val steps5min = stepsField(r.reason, steps5Regex) ?: return@mapNotNull null
            SC(
                duration = 0,
                timestamp = r.date,
                steps5min = steps5min,
                steps10min = 0,
                steps15min = stepsField(r.reason, steps15Regex) ?: 0,
                steps30min = stepsField(r.reason, steps30Regex) ?: 0,
                steps60min = stepsField(r.reason, steps60Regex) ?: 0,
                steps180min = stepsField(r.reason, steps180Regex) ?: 0,
                device = "reason-text"
            )
        }
}
