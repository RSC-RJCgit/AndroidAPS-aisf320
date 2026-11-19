package app.aaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.plugins.sync.nsclientV3.DataSyncSelectorV3
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

class DataSyncWorker(
    context: Context, params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.IO) {

    @Inject lateinit var dataSyncSelectorV3: DataSyncSelectorV3
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var nsClientV3Plugin: NSClientV3Plugin

    override suspend fun doWorkAndLog(): Result {
        if (nsClientV3Plugin.doingFullSync) {
            rxBus.send(EventNSClientNewLog("● RUN", "Full sync finished"))
            nsClientV3Plugin.endFullSync()
        }
        // A Virtual-pump master uploads nothing to its main site while that is blocked (2026-11-19, per explicit request): it is the
        // BG site, a real looper uploads there, or the site is not checked yet. Stopped here, so the sync cursors stay put.
        nsClientV3Plugin.ensureMainSiteChecked()
        if (nsClientV3Plugin.reportMainSiteUploadBlock()) {
            rxBus.send(EventNSClientNewLog("► UPL", "Skipped: upload to the main site stopped"))
            return Result.success()
        }
        if (activePlugin.activeNsClient?.hasWritePermission == true || nsClientV3Plugin.nsClientV3Service?.wsConnected == true) {
            rxBus.send(EventNSClientNewLog("► UPL", "Start"))
            dataSyncSelectorV3.doUpload()
            rxBus.send(EventNSClientNewLog("► UPL", "End"))
        } else {
            if (activePlugin.activeNsClient?.hasWritePermission == true)
                rxBus.send(EventNSClientNewLog("► ERROR", "No write permission"))
            else if (nsClientV3Plugin.nsClientV3Service?.wsConnected == true)
                rxBus.send(EventNSClientNewLog("► ERROR", "Not connected"))
            // refresh token
            nsClientV3Plugin.scheduleIrregularExecution(refreshToken = true)
        }
        return Result.success()
    }
}