package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.time.T
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.GlucoseValueDataPoint
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.NoisyBgDeltaDataPoint
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import com.jjoe64.graphview.series.DataPoint
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
                            acce >= maxDev  -> android.graphics.Color.CYAN
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

        // Live "L=<noisy bgl> A1=<aaps delta> L=<libre delta>" annotation at the current reading.
        val latest = data.overviewData.bgReadingsArray.firstOrNull()
        val noisyBg = currentNoisyBg(data.overviewData.bgReadingsArray)
        val aapsDelta = aapsOneMinuteDelta(data.overviewData.bgReadingsArray)
        val libreDelta = libreOneMinuteDelta(data.overviewData.bgReadingsArray)
        data.overviewData.noisyBgDeltaSeries =
            if (latest != null && noisyBg != null && aapsDelta != null && libreDelta != null) {
                val label = "L=${profileUtil.fromMgdlToStringInUnits(noisyBg)} " +
                    "A1=${formatMmolDelta(aapsDelta)} L=${formatMmolDelta(libreDelta)}"
                PointsWithLabelGraphSeries(
                    arrayOf<DataPointWithLabelInterface>(
                        NoisyBgDeltaDataPoint(latest.timestamp, profileUtil.fromMgdlToUnits(noisyBg), label, rh)
                    )
                )
            } else PointsWithLabelGraphSeries<DataPointWithLabelInterface>()

        return Result.success()
    }

    private fun addUpperChartMargin(maxBgValue: Double) =
        if (profileUtil.units == GlucoseUnit.MGDL) Round.roundTo(maxBgValue, 40.0) + 80 else Round.roundTo(maxBgValue, 2.0) + 4

    // `readings` is expected newest-first (data.overviewData.bgReadingsArray, ascending=false above).
    // Delta is between whatever the two most recent readings actually are — assumes ~1-minute sensor
    // cadence rather than enforcing an exact 1-minute gap, since that's this fork's typical interval.

    // gv.noise: same field already plotted as the red raw-BG line in doWorkAndLog() above.
    private fun currentNoisyBg(readings: List<GV>): Double? = readings.firstOrNull()?.noise

    // gv.value delta between the two newest readings.
    private fun aapsOneMinuteDelta(readings: List<GV>): Double? {
        if (readings.size < 2) return null
        return readings[0].value - readings[1].value
    }

    // gv.noise delta between the two newest readings (Libre/xDrip raw native signal, not gv.value).
    private fun libreOneMinuteDelta(readings: List<GV>): Double? {
        if (readings.size < 2) return null
        val newest = readings[0].noise ?: return null
        val previous = readings[1].noise ?: return null
        return newest - previous
    }

    // deltaMgdl -> signed mmol string, 2 decimal places (e.g. "+0.34", "-0.11").
    private fun formatMmolDelta(deltaMgdl: Double): String =
        String.format(Locale.US, "%+.2f", profileUtil.fromMgdlToUnits(deltaMgdl))
}