package app.aaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSCarbs
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.plugins.sync.nsShared.NsIncomingDataProcessor
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers

@HiltWorker
class LoadSecondaryTreatmentsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    aapsLogger: AAPSLogger,
    fabricPrivacy: FabricPrivacy,
    private val nsClientV3Plugin: NSClientV3Plugin,
    private val dateUtil: DateUtil,
    private val storeDataForDb: StoreDataForDb,
    private val nsIncomingDataProcessor: NsIncomingDataProcessor,
    private val nsClientRepository: NSClientRepository
) : LoggingWorker(context, params, Dispatchers.IO, aapsLogger, fabricPrivacy) {

    override suspend fun doWorkAndLog(): Result {
        val client = nsClientV3Plugin.secondaryNsAndroidClient ?: return Result.success()
        var lastModified = nsClientV3Plugin.secondaryTreatmentsLastModified
        val firstLoad = lastModified <= 0L
        var createdAfter = dateUtil.now() - nsClientV3Plugin.maxAge

        try {
            var continueLoading = true
            while (continueLoading) {
                val response =
                    if (firstLoad)
                        client.getTreatmentsNewerThan(dateUtil.toISOString(createdAfter), NSClientV3Plugin.RECORDS_TO_LOAD)
                    else
                        client.getTreatmentsModifiedSince(lastModified, NSClientV3Plugin.RECORDS_TO_LOAD)

                val bolusesAndCarbs = response.values.filter { it is NSBolus || it is NSCarbs }
                if (bolusesAndCarbs.isNotEmpty()) {
                    nsClientRepository.addLog("◄ RCV-2", "${bolusesAndCarbs.size} bolus/carb treatments from secondary NS")
                    nsIncomingDataProcessor.processTreatments(
                        bolusesAndCarbs,
                        doFullSync = false,
                        source = NsIncomingDataProcessor.TreatmentSource.SECONDARY
                    )
                }

                if (response.code == 304 || response.values.isEmpty()) {
                    if (!firstLoad)
                        response.lastServerModified?.takeIf { it > lastModified }?.let {
                            lastModified = it
                            nsClientV3Plugin.secondaryTreatmentsLastModified = it
                        }
                    continueLoading = false
                } else if (firstLoad) {
                    val nextCreatedAt = response.values.mapNotNull { it.date }.maxOrNull() ?: createdAfter
                    if (nextCreatedAt <= createdAfter || response.values.size < NSClientV3Plugin.RECORDS_TO_LOAD)
                        continueLoading = false
                    else
                        createdAfter = nextCreatedAt + 1L
                } else {
                    val nextModified = response.lastServerModified
                    if (nextModified == null || nextModified <= lastModified) {
                        continueLoading = false
                    } else {
                        lastModified = nextModified
                        nsClientV3Plugin.secondaryTreatmentsLastModified = lastModified
                        continueLoading = response.values.size >= NSClientV3Plugin.RECORDS_TO_LOAD
                    }
                }

                if (!continueLoading && firstLoad)
                    nsClientV3Plugin.secondaryTreatmentsLastModified = response.lastServerModified?.takeIf { it > 0L } ?: dateUtil.now()
            }
            storeDataForDb.storeTreatmentsToDb(fullSync = false)
        } catch (error: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary Nightscout treatment download failed", error)
            nsClientRepository.addLog("◄ NS2 ERROR", error.localizedMessage)
        }
        return Result.success()
    }
}
