package app.aaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
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
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSCarbs
import app.aaps.core.nssdk.localmodel.treatment.NSTherapyEvent
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.plugins.sync.nsclientV3.extensions.toBolus
import app.aaps.plugins.sync.nsclientV3.extensions.toCarbs
import app.aaps.plugins.sync.nsclientV3.extensions.toTherapyEvent
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Downloads manual boluses and carbs from a secondary Nightscout site (e.g. main phone's NS).
 * Used when the follower phone sources BGL from a separate NS but needs bolus/carb history
 * from the main phone for accurate IOB/COB calculations.
 * SMBs are always excluded from the secondary download.
 * Also imports device-lifecycle therapy events (sensor/site/insulin/pump-battery changes), since
 * those are set on the main phone (and land only on its NS) but the follower's cannula/sensor-age
 * automations read them from the local TE table — without this they never arrive.
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

    companion object {

        private const val PAGE_SIZE = 500
        private const val MAX_PAGES = 64
        private const val CURSOR_OVERLAP_MS = 2L * 60 * 60 * 1000
        // Covers the complete 0-15 day SensorAge adjustment range on the first upgraded run.
        private const val RECOVERY_LOOKBACK_MS = 16L * 24 * 60 * 60 * 1000

        // Therapy-event types imported from the secondary NS: the device-lifecycle events the
        // cannula/sensor-age automations read (TE.Type.CANNULA_CHANGE / SENSOR_CHANGE via
        // getLastTherapyRecordUpToNow), plus their close siblings so pod/sensor sessions stay complete.
        private val secondaryTherapyEventTypes = setOf(
            TE.Type.SENSOR_CHANGE,
            TE.Type.SENSOR_STARTED,
            TE.Type.CANNULA_CHANGE,
            TE.Type.INSULIN_CHANGE,
            TE.Type.PUMP_BATTERY_CHANGE
        )
    }

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
            var cursor = preferences.get(LongKey.NsClientSecondaryLastModified)
            var queryFrom = if (cursor == 0L) {
                // One-time migration/recovery: server-modified time finds treatments entered now but
                // backdated to their real event time (Sensor Change and delayed bolus entries included).
                (dateUtil.now() - RECOVERY_LOOKBACK_MS).coerceAtLeast(0L)
            } else {
                (cursor - CURSOR_OVERLAP_MS).coerceAtLeast(0L)
            }
            val recoveryScan = cursor == 0L
            val acceptTherapyEvents = preferences.get(BooleanKey.NsClientSecondaryAcceptTherapyEvent)
            var totalTreatments = 0
            var totalBoluses = 0
            var totalCarbs = 0
            var totalTherapyEvents = 0
            var page = 0
            var continueLoading = true

            rxBus.send(
                EventNSClientNewLog(
                    "◄ SEC-NS",
                    "Fetching secondary treatments by server-modified time since ${dateUtil.dateAndTimeAndSecondsString(queryFrom)}" +
                        if (recoveryScan) " (16-day recovery)" else ""
                )
            )

            while (continueLoading && page < MAX_PAGES) {
                val response = client.getTreatmentsModifiedSince(queryFrom, PAGE_SIZE)
                val treatments = response.values
                if (treatments.isEmpty()) {
                    // An ETag can advance even for an empty/304 response. Persist it only after all
                    // previously queued treatment data has been flushed successfully.
                    storeDataForDb.storeTreatmentsToDb(false)
                    response.lastServerModified?.takeIf { it > cursor }?.let {
                        cursor = it
                        preferences.put(LongKey.NsClientSecondaryLastModified, cursor)
                    }
                    break
                }

                var pageBoluses = 0
                var pageCarbs = 0
                var pageTherapyEvents = 0
                for (treatment in treatments) {
                    when (treatment) {
                        is NSBolus -> {
                            val bolus = treatment.toBolus()
                            if (bolus.type != BS.Type.SMB) {
                                storeDataForDb.addToBoluses(bolus)
                                pageBoluses++
                            }
                        }

                        is NSCarbs -> {
                            storeDataForDb.addToCarbs(treatment.toCarbs())
                            pageCarbs++
                        }

                        is NSTherapyEvent -> {
                            if (acceptTherapyEvents) {
                                val te = treatment.toTherapyEvent()
                                if (te.type in secondaryTherapyEventTypes) {
                                    storeDataForDb.addToTherapyEvents(te)
                                    pageTherapyEvents++
                                }
                            }
                        }

                        else -> Unit
                    }
                }

                // Database first, cursor second: a process stop can repeat a page, but can no longer
                // advance past entries that had only been held in StoreDataForDb's in-memory queues.
                storeDataForDb.storeTreatmentsToDb(false)
                val nextCursor = response.lastServerModified
                if (nextCursor != null && nextCursor > cursor) {
                    cursor = nextCursor
                    preferences.put(LongKey.NsClientSecondaryLastModified, cursor)
                }

                totalTreatments += treatments.size
                totalBoluses += pageBoluses
                totalCarbs += pageCarbs
                totalTherapyEvents += pageTherapyEvents
                page++

                val cursorAdvanced = nextCursor != null && nextCursor > queryFrom
                continueLoading = response.code != 304 && treatments.size >= PAGE_SIZE && cursorAdvanced
                if (continueLoading) queryFrom = nextCursor!!
            }

            rxBus.send(
                EventNSClientNewLog(
                    "◄ SEC-NS",
                    "$totalTreatments treatments in $page page(s): $totalBoluses boluses $totalCarbs carbs " +
                        "$totalTherapyEvents device events from secondary NS"
                )
            )
            if (page >= MAX_PAGES && continueLoading) {
                rxBus.send(EventNSClientNewLog("◄ SEC-NS", "Recovery paused after $MAX_PAGES pages; continuing next sync"))
            }
            Result.success()
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS fetch failed", e)
            rxBus.send(EventNSClientNewLog("◄ SEC-NS ERR", e.message ?: "Unknown error"))
            Result.failure(workDataOf("Error" to e.message))
        }
    }
}
