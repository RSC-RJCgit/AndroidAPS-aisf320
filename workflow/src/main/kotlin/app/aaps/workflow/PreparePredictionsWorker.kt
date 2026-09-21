package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.GlucoseValueDataPoint
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class PreparePredictionsWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var config: Config
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var loop: Loop
    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var preferences: Preferences

    class PreparePredictionsData(
        val overviewData: OverviewData
    )

    override suspend fun doWorkAndLog(): Result {
        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as PreparePredictionsData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        val apsResult = if (config.APS) loop.lastRun?.constraintsProcessed else processedDeviceStatusData.getAPSResult()
        // Do not rewrite fromTime/toTime/endTime. OverviewDataImpl.initRange() owns the rolling
        // history window; OverviewFragment extends endTime only when PRE is on. Hour-aligning here
        // (and shrinking history by 1h for predictions) emptied the 1h scale and made 6h labels
        // read ~15-20 min early.

        val bgListArray: MutableList<DataPointWithLabelInterface> = ArrayList()
        val predictions: MutableList<GlucoseValueDataPoint>? = apsResult?.predictionsAsGv
            ?.map { bg -> GlucoseValueDataPoint(bg, profileUtil, rh, dateUtil, preferences) }
            ?.toMutableList()
        if (predictions != null) {
            predictions.sortWith { o1: GlucoseValueDataPoint, o2: GlucoseValueDataPoint -> o1.x.compareTo(o2.x) }
            for (prediction in predictions) if (prediction.data.value >= 40) bgListArray.add(prediction)
        }
        data.overviewData.predictionsGraphSeries = PointsWithLabelGraphSeries(Array(bgListArray.size) { i -> bgListArray[i] })
        return Result.success()
    }
}