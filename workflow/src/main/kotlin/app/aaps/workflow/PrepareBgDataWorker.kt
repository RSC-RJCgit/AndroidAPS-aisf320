package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.GlucoseValueDataPoint
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.NoisyBgDeltaDataPoint
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
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
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import javax.inject.Inject

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
            // Transparency follows the basal-long-press cycle (PointsWithLabelGraphSeries.basalToggleIndex):
            // transparent (~55% opacity) on preset 0, fully opaque red otherwise.
            it.color = if (PointsWithLabelGraphSeries.noisyLineTransparent) android.graphics.Color.parseColor("#8CFF0000") else android.graphics.Color.RED
            it.thickness = 4
        }

        // Live "L=<noisy bgl> A1=<aaps delta> L1=<libre delta> St15=<steps> St60=<steps>" annotation at the current reading.
        val latest = data.overviewData.bgReadingsArray.firstOrNull()
        val noisyBg = currentNoisyBg(data.overviewData.bgReadingsArray)
        val aapsDelta = aapsOneMinuteDelta(data.overviewData.bgReadingsArray)
        val libreDelta = libreOneMinuteDelta(data.overviewData.bgReadingsArray)
        // Fixed 2h lookback independent of the graph's own display range, so Step30max always has its full window.
        val stepsWindowFrom = toTime - T.hours(2).msecs()
        val rawStepsCountList = persistenceLayer.getStepsCountFromTimeToTime(stepsWindowFrom, toTime)
        // On a client build (aapsclient/aapsclient2), NS device-status sync only creates local AIV +
        // APSResult records — it never populates the local StepsCount table — so stepsCountList is
        // otherwise always empty there even though the master's real step data reached the client fine,
        // just embedded as text in APSResult.reason rather than a structured field. Same fallback as
        // AutoISFHistoryDialog.kt's stepsValue()/stepsFromReason(), reconstructed per-result here since
        // this needs a max-over-window rather than a single nearest-timestamp lookup.
        val stepsCountList = rawStepsCountList.ifEmpty {
            stepsFromReason(persistenceLayer.getApsResults(stepsWindowFrom, toTime))
        }
        val latestSteps = stepsCountList.maxByOrNull { it.timestamp }
        data.overviewData.noisyBgDeltaSeries =
            if (latest != null && noisyBg != null && aapsDelta != null && libreDelta != null) {
                val stepsTxt = latestSteps?.let { " St15=${it.steps15min} St60=${it.steps60min}" } ?: ""
                // Current MJ state (value only, no "MJ=" prefix), from the most recent MJ-lifecycle
                // careportal note. Uses notes (which sync to the client) rather than automationStateService
                // (which doesn't sync), so it's correct on both master and client — same source as the
                // history table's MJ column, so this is just "the latest MJ value".
                val mjTxt = " " + latestMjState(latest.timestamp)
                val label = "L=${profileUtil.fromMgdlToStringInUnits(noisyBg)} " +
                    "A1=${formatMmolDelta(aapsDelta)} L1=${formatMmolDelta(libreDelta)}" + stepsTxt + mjTxt
                PointsWithLabelGraphSeries(
                    arrayOf<DataPointWithLabelInterface>(
                        NoisyBgDeltaDataPoint(latest.timestamp, profileUtil.fromMgdlToUnits(noisyBg), label, rh)
                    )
                )
            } else PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

        // Single row, near the top (just under the green raw-BG/delta line):
        // "Steps5=<current 5min>/<max 5min over last 30min>  Steps30=<current 30min>/<max 30min over last 2h>".
        data.overviewData.stepsStackedSeries =
            if (latest != null && latestSteps != null) {
                // maxOf(..., latestSteps.stepsXmin) so the max always includes the current reading even if
                // it falls outside the lookback window (e.g. a data gap leaves the latest record >30min old).
                val step5max = maxOf(
                    stepsCountList.filter { it.timestamp >= toTime - T.mins(30).msecs() }.maxOfOrNull { it.steps5min } ?: 0,
                    latestSteps.steps5min
                )
                val step30max = maxOf(stepsCountList.maxOfOrNull { it.steps30min } ?: 0, latestSteps.steps30min)
                val steps60Label = if (automationStateService.inState("stepsHigh", "StepsLow")) "  ${latestSteps.steps60min}" else ""
                val lSlopeLabel = "  Lslope=${String.format(Locale.getDefault(), "%.2f", preferences.get(DoubleKey.FslCalSlope))}"
                val label = "Steps5=${latestSteps.steps5min}/$step5max  Steps30=${latestSteps.steps30min}/$step30max$steps60Label$lSlopeLabel"
                PointsWithLabelGraphSeries(
                    arrayOf<DataPointWithLabelInterface>(
                        StepsStackedDataPoint(latest.timestamp, profileUtil.fromMgdlToUnits(latest.value), label, rh)
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
