package app.aaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.sync.nsclient.data.NSDeviceStatusHandler
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class LoadDeviceStatusWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.IO) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var context: Context
    @Inject lateinit var nsClientV3Plugin: NSClientV3Plugin
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var nsDeviceStatusHandler: NSDeviceStatusHandler
    @Inject lateinit var config: Config

    override suspend fun doWorkAndLog(): Result {
        val nsAndroidClient = nsClientV3Plugin.nsAndroidClient ?: return Result.failure(workDataOf("Error" to "AndroidClient is null"))

        try {
            // Notify plugin we loaded al missed data
            nsClientV3Plugin.initialLoadFinished = true

            // Fork (2026-11-26, per explicit request): once per start, a client reads the last day's battery fields (a small download; 1000 is the server's usual most per request, about 16 h of minute uploads), so the
            // battery forecast starts at the loop phone's real unplug time. A failure only leaves the forecast as before.
            if (config.AAPSCLIENT && !nsDeviceStatusHandler.batteryHistorySeeded) {
                nsDeviceStatusHandler.batteryHistorySeeded = true
                try {
                    val day = nsAndroidClient.getDeviceStatusBatterySince(dateUtil.now() - T.hours(24).msecs(), 1000)
                    nsDeviceStatusHandler.seedUploaderBattery(day)
                    rxBus.send(EventNSClientNewLog("◄ RCV", "${day.size} battery readings of the last 24 h"))
                } catch (error: Exception) {
                    aapsLogger.error("Battery history: ", error)
                }
            }
            val from = dateUtil.now() - T.mins(7).msecs()
            val deviceStatuses = nsAndroidClient.getDeviceStatusModifiedSince(from)
            aapsLogger.debug("DEVICESTATUSES: $deviceStatuses")
            // A Virtual-pump master watches its main site for a real looper (2026-11-19), see NSClientV3Plugin.mainSiteUploadBlock
            nsClientV3Plugin.noteRealLoopers(deviceStatuses)
            if (deviceStatuses.isNotEmpty()) {
                rxBus.send(EventNSClientNewLog("◄ RCV", "${deviceStatuses.size} DSs from ${dateUtil.dateAndTimeAndSecondsString(from)}"))
                nsDeviceStatusHandler.handleNewData(deviceStatuses.toTypedArray())
                rxBus.send(EventNSClientNewLog("● DONE PROCESSING DS", ""))
            } else {
                rxBus.send(EventNSClientNewLog("◄ RCV DS END", "No data from ${dateUtil.dateAndTimeAndSecondsString(from)}"))
            }
        } catch (error: Exception) {
            aapsLogger.error("Error: ", error)
            rxBus.send(EventNSClientNewLog("◄ ERROR", error.localizedMessage))
            nsClientV3Plugin.lastOperationError = error.localizedMessage
            return Result.failure(workDataOf("Error" to error.localizedMessage))
        }

        nsClientV3Plugin.lastOperationError = null
        return Result.success()
    }
}