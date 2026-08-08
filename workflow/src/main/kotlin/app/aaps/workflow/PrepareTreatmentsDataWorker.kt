package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.graph.data.BolusDataPoint
import app.aaps.core.graph.data.CarbsDataPoint
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.EffectiveProfileSwitchDataPoint
import app.aaps.core.graph.data.ExtendedBolusDataPoint
import app.aaps.core.graph.data.HeartRateDataPoint
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.StepsDataPoint
import app.aaps.core.graph.data.TherapyEventDataPoint
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventIobCalculationProgress
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.Translator
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class PrepareTreatmentsDataWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var translator: Translator
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var preferences: Preferences

    class PrepareTreatmentsData(
        val overviewData: OverviewData
    )

    override suspend fun doWorkAndLog(): Result {

        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as PrepareTreatmentsData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        val endTime = data.overviewData.endTime
        val fromTime = data.overviewData.fromTime
        rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_TREATMENTS_DATA, 0, null))
        data.overviewData.maxTreatmentsValue = 0.0
        data.overviewData.maxTherapyEventValue = 0.0
        data.overviewData.maxEpsValue = 0.0
        val filteredTreatments: MutableList<DataPointWithLabelInterface> = ArrayList()
        val filteredTherapyEvents: MutableList<DataPointWithLabelInterface> = ArrayList()
        // Plain TE.Type.NOTE events only (our custom automation notes) — kept separate from
        // filteredTherapyEvents (Announcements/MBG/finger-stick/settings-export/exercise) so the two can
        // render on different graphs: notes on graph2, everything else stays on the main graph, which is
        // where it was before notes got their own dedicated home.
        val filteredNotes: MutableList<DataPointWithLabelInterface> = ArrayList()
        val filteredEps: MutableList<DataPointWithLabelInterface> = ArrayList()

        val aivList = persistenceLayer.getAutoIsfValuesFromTimeToTime(fromTime, endTime)
        // Every "fast rise" branch in DetermineBasalAutoISF.kt appends this exact phrase with the real
        // multiplier as plain text (e.g. "microBolus = microBolus * 0.7 ; ..."); at most one can match
        // per cycle since the branches are if/else-if chained. Matches the same nearest-within-15-min
        // join pattern as aivList above, but against APSResult.reason instead of AIV fields.
        val apsResultsList = persistenceLayer.getApsResults(fromTime, endTime)
        // Actual reason text is "<threshold>  = microBolus  * <factor> ; microBolus = <result>"
        // (the token before "=" is a threshold number, not the word "microBolus" again).
        val fastRiseRegex = Regex("""=\s*microBolus\s*\*\s*([0-9.]+)\s*;""")
        val bolusDataPoints = persistenceLayer.getBolusesFromTimeToTime(fromTime, endTime, true)
            .map { BolusDataPoint(it, rh, activePlugin.activePump.pumpDescription.bolusStep, preferences, decimalFormatter) }
            .filter { it.data.type == BS.Type.NORMAL || it.data.type == BS.Type.SMB }
        bolusDataPoints.forEach { dp ->
            dp.y = getNearestBg(data.overviewData, dp.x.toLong())
            if (dp.data.type == BS.Type.SMB && aivList.isNotEmpty()) {
                val nearest = aivList.minByOrNull { aiv -> kotlin.math.abs(aiv.timestamp - dp.x.toLong()) }
                if (nearest != null && kotlin.math.abs(nearest.timestamp - dp.x.toLong()) < T.mins(15).msecs()) {
                    val acce = kotlin.math.abs(nearest.acceIsf - 1.0)
                    val bg   = kotlin.math.abs(nearest.bgIsf   - 1.0)
                    val pp   = kotlin.math.abs(nearest.ppIsf   - 1.0)
                    val dura = kotlin.math.abs(nearest.duraIsf - 1.0)
                    val maxDev = maxOf(acce, bg, pp, dura)
                    if (maxDev > 0.01) {
                        dp.colorOverride = when {
                            acce >= maxDev -> rh.gac(null, app.aaps.core.ui.R.attr.acceIsfColor)
                            bg   >= maxDev -> rh.gac(null, app.aaps.core.ui.R.attr.bgIsfColor)
                            pp   >= maxDev -> rh.gac(null, app.aaps.core.ui.R.attr.ppIsfColor)
                            else           -> rh.gac(null, app.aaps.core.ui.R.attr.duraIsfColor)
                        }
                    }
                }
            }
            if (dp.data.type == BS.Type.SMB && apsResultsList.isNotEmpty()) {
                val nearestResult = apsResultsList.minByOrNull { r -> kotlin.math.abs(r.date - dp.x.toLong()) }
                if (nearestResult != null && kotlin.math.abs(nearestResult.date - dp.x.toLong()) < T.mins(15).msecs()) {
                    val factor = fastRiseRegex.find(nearestResult.reason)?.groupValues?.get(1)?.toDoubleOrNull()
                    if (factor != null) dp.fastRiseLabel = Math.round(factor * 10).toString()
                }
            }
            filteredTreatments.add(dp)
        }
        // Delayed-bolus detection: see DelayedBolusWorker.kt, enqueued from BolusWizard.kt.
        // The delayed portion is delivered 10-30 min later with notes containing this marker;
        // flag both it and the earlier bolus that triggered it so the pair shows yellow.
        // The old "Split bolus attempt" marker is still matched for boluses recorded before
        // the mechanism was renamed to "delayed bolus".
        val delayedBolusMarkers = listOf("Delayed bolus attempt", "Split bolus attempt")
        bolusDataPoints
            .filter { dp -> delayedBolusMarkers.any { m -> dp.data.notes?.contains(m) == true } }
            .forEach { splitDp ->
                splitDp.hasDelayedComponent = true
                bolusDataPoints
                    .filter { it !== splitDp && it.x < splitDp.x && (splitDp.x - it.x) <= T.mins(40).msecs() }
                    .maxByOrNull { it.x }
                    ?.hasDelayedComponent = true
            }
        persistenceLayer.getCarbsFromTimeToTimeExpanded(fromTime, endTime, true)
            .map { CarbsDataPoint(it, rh) }
            .forEach {
                it.y = getNearestBg(data.overviewData, it.x.toLong())
                filteredTreatments.add(it)
            }

        // ProfileSwitch
        persistenceLayer.getEffectiveProfileSwitchesFromTimeToTime(fromTime, endTime, true)
            .map { EffectiveProfileSwitchDataPoint(it, rh, data.overviewData.epsScale) }
            .forEach {
                data.overviewData.maxEpsValue = maxOf(data.overviewData.maxEpsValue, it.data.originalPercentage.toDouble())
                filteredEps.add(it)
            }

        // Extended bolus
        if (!activePlugin.activePump.isFakingTempsByExtendedBoluses) {
            persistenceLayer.getExtendedBolusesStartingFromTimeToTime(fromTime, endTime, true)
                .map { ExtendedBolusDataPoint(it, rh) }
                .filter { it.duration != 0L }
                .forEach {
                    it.y = getNearestBg(data.overviewData, it.x.toLong())
                    filteredTreatments.add(it)
                }
        }

        // Careportal — split plain notes (graph2) from everything else (main graph, unchanged).
        persistenceLayer.getTherapyEventDataFromToTime(fromTime - T.hours(6).msecs(), endTime).blockingGet()
            .map { TherapyEventDataPoint(it, rh, profileUtil, translator) }
            .filterTimeframe(fromTime, endTime)
            .forEach {
                if (it.y == 0.0) it.y = getNearestBg(data.overviewData, it.x.toLong())
                if (it.data.type == TE.Type.NOTE) filteredNotes.add(it) else filteredTherapyEvents.add(it)
            }

        // increase maxY if a treatment forces it's own height that's higher than a BG value
        filteredTreatments.maxOfOrNull { it.y }
            ?.let(::addUpperChartMargin)
            ?.let { data.overviewData.maxTreatmentsValue = maxOf(data.overviewData.maxTreatmentsValue, it) }
        filteredTherapyEvents.maxOfOrNull { it.y }
            ?.let(::addUpperChartMargin)
            ?.let { data.overviewData.maxTherapyEventValue = maxOf(data.overviewData.maxTherapyEventValue, it) }

        data.overviewData.treatmentsSeries = PointsWithLabelGraphSeries(filteredTreatments.toTypedArray())

        // SMB labels for graph 2 — fixed at Y=0 so they sit on the IOB baseline
        val smbLabels = bolusDataPoints
            .filter { it.data.type == BS.Type.SMB && it.label.isNotEmpty() }
            .map { dp ->
                object : DataPointWithLabelInterface {
                    override fun getX(): Double = dp.x
                    override fun getY(): Double = 0.0
                    override fun setY(y: Double) {}
                    override val label: String = dp.label
                    override val duration: Long = 0L
                    override val shape = app.aaps.core.graph.data.Shape.SMB_GRAPH2
                    override val size: Float = 1.0f
                    override val paintStyle = android.graphics.Paint.Style.FILL
                    override fun color(context: android.content.Context?) = if (dp.colorOverride != 0) dp.colorOverride else android.graphics.Color.WHITE
                }
            }
        data.overviewData.smbLabelSeries = PointsWithLabelGraphSeries(smbLabels.toTypedArray())

        // Reconstructed SMB-stack starts + the IOB change over the 10 minutes following each one, drawn
        // at the top of graph2 in white (Shape.SMB_STACK_DELTA_IOB). Mirrors the LIVE stacking state
        // machine exactly (ApsAutoIsfSmbStackStart in DetermineBasalAutoISF.kt: avg gap <=70s over the
        // trailing 5 min of SMBs, new stack only when none is active or the previous one's 10-min window
        // has elapsed) rather than a fresh definition — same avg-gap formula as
        // AutoIsfHistoryExporter.smbInterval5SecStr(). Historical-only: there is no persisted history of
        // past ApsAutoIsfSmbStackStart values (it's a single live scalar, overwritten each cycle), so
        // stack starts are re-derived here from the SMB dose timestamps themselves.
        //
        // KNOWN IMPRECISION: the IOB at "10 minutes after start" is taken from the nearest AutoIsfValues
        // record to that instant (aivList, ~1-min cadence at this user's current loop interval), not an
        // exact value AT T+10 — a dose landing right on that boundary could end up counted just inside or
        // just outside the window depending on which side the nearest record falls. Not a concern for
        // doses clearly inside/outside the window, only ones within roughly one loop cycle of the edge.
        val smbTimestampsForStack = bolusDataPoints.filter { it.data.type == BS.Type.SMB }.map { it.x.toLong() }.sorted()
        fun avgGapTrailing5MinSec(at: Long): Double? {
            val windowStart = at - 5 * 60_000L
            val inWindow = smbTimestampsForStack.filter { it in windowStart..at }
            if (inWindow.size < 2) return null
            val spanSec = (inWindow.max() - inWindow.min()).toDouble() / 1000.0
            return spanSec / (inWindow.size - 1)
        }
        val stackDeltaIobLabels: MutableList<DataPointWithLabelInterface> = ArrayList()
        var reconstructedStackStart = 0L
        aivList.sortedBy { it.timestamp }.forEach { aiv ->
            val gap = avgGapTrailing5MinSec(aiv.timestamp)
            if (gap != null && gap <= 70.0) {
                if (reconstructedStackStart == 0L || aiv.timestamp - reconstructedStackStart >= 10 * 60_000L) {
                    reconstructedStackStart = aiv.timestamp
                    val iobAtStart = aiv.iob
                    val targetT10 = reconstructedStackStart + 10 * 60_000L
                    val nearestT10 = aivList.filter { it.timestamp >= reconstructedStackStart }
                        .minByOrNull { kotlin.math.abs(it.timestamp - targetT10) }
                    if (nearestT10 != null && kotlin.math.abs(nearestT10.timestamp - targetT10) < T.mins(15).msecs()) {
                        val deltaIob = nearestT10.iob - iobAtStart
                        val stackStartTs = reconstructedStackStart
                        val labelText = (if (deltaIob >= 0) "+" else "") + String.format("%.2f", deltaIob)
                        stackDeltaIobLabels.add(object : DataPointWithLabelInterface {
                            override fun getX(): Double = stackStartTs.toDouble()
                            override fun getY(): Double = 0.0
                            override fun setY(y: Double) {}
                            override val label: String = labelText
                            override val duration: Long = 0L
                            override val shape = app.aaps.core.graph.data.Shape.SMB_STACK_DELTA_IOB
                            override val size: Float = 1.0f
                            override val paintStyle = android.graphics.Paint.Style.FILL
                            override fun color(context: android.content.Context?) = android.graphics.Color.WHITE
                        })
                    }
                }
            } else if (reconstructedStackStart != 0L) {
                reconstructedStackStart = 0L
            }
        }
        data.overviewData.stackDeltaIobSeries = PointsWithLabelGraphSeries(stackDeltaIobLabels.toTypedArray())

        data.overviewData.therapyEventSeries = PointsWithLabelGraphSeries(filteredTherapyEvents.toTypedArray())
        data.overviewData.noteEventSeries = PointsWithLabelGraphSeries(filteredNotes.toTypedArray())

        // Same notes as noteEventSeries above, rendered as plain unscaled arrowheads (Shape.SMB's own
        // BGL-point arrowhead, no dose-size scaling) fixed at graph3's old ISF-row spot — an additional,
        // simpler view alongside the full note display on graph2.
        val noteArrowheads = filteredNotes
            .map { note ->
                object : DataPointWithLabelInterface {
                    override fun getX(): Double = note.x
                    override fun getY(): Double = 0.0
                    override fun setY(y: Double) {}
                    override val label: String = ""
                    override val duration: Long = 0L
                    override val shape = app.aaps.core.graph.data.Shape.NOTE_ARROWHEAD_GRAPH3
                    override val size: Float = 1.0f
                    override val paintStyle = android.graphics.Paint.Style.FILL
                    override fun color(context: android.content.Context?) = android.graphics.Color.YELLOW
                }
            }
        data.overviewData.noteArrowheadSeries = PointsWithLabelGraphSeries(noteArrowheads.toTypedArray())

        data.overviewData.epsSeries = PointsWithLabelGraphSeries(filteredEps.toTypedArray())

        data.overviewData.heartRateGraphSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>(
            persistenceLayer.getHeartRatesFromTimeToTime(fromTime, endTime)
                .map { hr -> HeartRateDataPoint(hr, rh) }
                .toTypedArray()).apply { color = rh.gac(null, app.aaps.core.ui.R.attr.heartRateColor) }

        data.overviewData.stepsCountGraphSeries = PointsWithLabelGraphSeries<DataPointWithLabelInterface>(
            persistenceLayer.getStepsCountFromTimeToTime(fromTime, endTime)
                .map { steps -> StepsDataPoint(steps, rh) }
                .toTypedArray()).apply { color = rh.gac(null, app.aaps.core.ui.R.attr.stepsColor) }


        rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_TREATMENTS_DATA, 100, null))
        return Result.success()
    }

    private fun addUpperChartMargin(maxBgValue: Double) =
        if (profileUtil.units == GlucoseUnit.MGDL) Round.roundTo(maxBgValue, 40.0) + 80 else Round.roundTo(maxBgValue, 2.0) + 4

    private fun getNearestBg(overviewData: OverviewData, date: Long): Double {
        overviewData.bgReadingsArray.let { bgReadingsArray ->
            for (reading in bgReadingsArray) {
                if (reading.timestamp > date) continue
                return profileUtil.fromMgdlToUnits(reading.value)
            }
            return if (bgReadingsArray.isNotEmpty()) profileUtil.fromMgdlToUnits(bgReadingsArray[0].value)
            else profileUtil.fromMgdlToUnits(100.0)
        }
    }

    private fun <E : DataPointWithLabelInterface> List<E>.filterTimeframe(fromTime: Long, endTime: Long): List<E> =
        filter { it.x + it.duration >= fromTime && it.x <= endTime }
}
