package app.aaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.plugins.sync.nsShared.NsIncomingDataProcessor
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import kotlin.math.max

/**
 * Downloads manual boluses and carbs from a secondary Nightscout site (e.g. main phone's NS).
 * Used when the follower phone sources BGL from a separate NS but needs bolus/carb history
 * from the main phone for accurate IOB/COB calculations.
 * SMBs are always excluded from the secondary download.
 */
class LoadSecondaryBolusCarbsWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.IO) {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var context: Context
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var storeDataForDb: StoreDataForDb
    @Inject lateinit var nsIncomingDataProcessor: NsIncomingDataProcessor

    override suspend fun doWorkAndLog(): Result {
        if (!preferences.get(BooleanKey.NsClientSecondaryEnabled))
            return Result.success(workDataOf("Result" to "Secondary NS disabled"))

        val url = preferences.get(StringKey.NsClientSecondaryUrl).trim()
        val token = preferences.get(StringKey.NsClientSecondaryAccessToken).trim()

        if (url.isEmpty())
            return Result.failure(workDataOf("Error" to "Secondary NS URL not configured"))

        val client = NSAndroidClientImpl(
            baseUrl = url.lowercase().replace("https://", "").replace(Regex("/$"), ""),
            accessToken = token,
            context = context,
            logging = false,
            logger = { msg -> aapsLogger.debug(LTag.HTTP, "SecondaryNS: $msg") }
        )

        return try {
            // Load from last fetched timestamp, or last 48h on first run
            val lastLoaded = max(
                preferences.get(LongKey.NsClientSecondaryLastLoaded),
                dateUtil.now() - 48 * 60 * 60 * 1000L
            )
            val lastLoadedIso = dateUtil.toISOString(lastLoaded)
            rxBus.send(EventNSClientNewLog("◄ SEC-NS", "Fetching bolus+carbs since ${dateUtil.dateAndTimeAndSecondsString(lastLoaded)}"))

            val response = client.getTreatmentsNewerThan(lastLoadedIso, 500)
            val treatments = response.values

            if (treatments.isNotEmpty()) {
                rxBus.send(EventNSClientNewLog("◄ SEC-NS", "${treatments.size} treatments from secondary NS"))
                // Force accept insulin+carbs, exclude SMBs — override preferences temporarily via doFullSync=false
                // NsIncomingDataProcessor respects NsClientAcceptInsulin + NsClientAcceptInsulinExcludeSmb + NsClientAcceptCarbs
                // Ensure those keys are ON in settings; SMB exclusion handled by NsClientAcceptInsulinExcludeSmb
                nsIncomingDataProcessor.processTreatments(treatments, false)
                storeDataForDb.storeTreatmentsToDb(false)
                preferences.put(LongKey.NsClientSecondaryLastLoaded, dateUtil.now())
            } else {
                rxBus.send(EventNSClientNewLog("◄ SEC-NS", "No new treatments from secondary NS"))
            }
            Result.success()
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS fetch failed", e)
            rxBus.send(EventNSClientNewLog("◄ SEC-NS ERR", e.message ?: "Unknown error"))
            Result.failure(workDataOf("Error" to e.message))
        }
    }
}
