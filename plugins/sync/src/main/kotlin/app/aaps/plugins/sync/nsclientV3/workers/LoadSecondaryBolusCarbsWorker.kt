package app.aaps.plugins.sync.nsclientV3.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.BS
import app.aaps.core.data.time.T
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.interfaces.NSAndroidClient
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSBolusWizard
import app.aaps.core.nssdk.localmodel.treatment.NSCarbs
import app.aaps.core.nssdk.localmodel.treatment.NSTherapyEvent
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.CodedAutomationNames
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.sync.nsclient.data.NSDeviceStatusHandler
import app.aaps.plugins.sync.nsShared.NsIncomingDataProcessor
import app.aaps.plugins.sync.nsShared.fullAapsOnVirtualPump
import app.aaps.plugins.sync.nsShared.isIapsEntry
import app.aaps.plugins.sync.nsShared.isFollowerPhone
import app.aaps.plugins.sync.nsclientV3.extensions.toBolus
import app.aaps.plugins.sync.nsclientV3.extensions.toBolusCalculatorResult
import app.aaps.plugins.sync.nsclientV3.extensions.toCarbs
import app.aaps.plugins.sync.nsclientV3.extensions.toTherapyEvent
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Downloads manual boluses and carbs from a secondary Nightscout site (e.g. main phone's NS).
 * Used when the follower phone sources BGL from a separate NS but needs bolus/carb history
 * from the main phone for accurate IOB/COB calculations.
 * Enqueued on its own unique work name — not chained after primary LoadStatus — so a 401 on
 * the follower's own NS (Virtual 5 Sep) cannot skip Live bolus/carbs/pod imports.
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
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var config: Config
    @Inject lateinit var nsIncomingDataProcessor: NsIncomingDataProcessor
    @Inject lateinit var nsDeviceStatusHandler: NSDeviceStatusHandler

    companion object {

        private const val PAGE_SIZE = 500
        private const val MAX_PAGES = 64
        private const val CURSOR_OVERLAP_MS = 2L * 60 * 60 * 1000
        // Covers the complete 0-15 day SensorAge adjustment range on the first upgraded run.
        private const val RECOVERY_LOOKBACK_MS = 16L * 24 * 60 * 60 * 1000

        // Therapy-event types imported from the secondary NS: the device-lifecycle events the
        // cannula/sensor-age automations read (TE.Type.CANNULA_CHANGE / SENSOR_CHANGE via
        // getLastTherapyRecordUpToNow), plus their close siblings so pod/sensor sessions stay complete.
        // StLow/legacy StorageLow Notes carry storage alerts to Virtual Pump; the exact ADesk Note
        // carries the reverse-direction Tasker command to the real-pump phone. Route-specific AcNSx,
        // AcTTx and AcLTx Notes report AAPS receipt/dispatch; legacy AckDesk remains accepted.
        private val secondaryTherapyEventTypes = setOf(
            TE.Type.SENSOR_CHANGE,
            TE.Type.SENSOR_STARTED,
            TE.Type.CANNULA_CHANGE,
            TE.Type.INSULIN_CHANGE,
            TE.Type.PUMP_BATTERY_CHANGE,
            TE.Type.NOTE
        )
    }

    // 2026-09-27, per explicit request: a full AAPS on VirtualPump takes its profile store from the secondary NS site (the primary's
    // store is ignored, see NsIncomingDataProcessor.processProfile). Same first-load / modified-since logic as LoadProfileStoreWorker,
    // with its own cursor. Never fails the treatment import: errors are logged and the last stored profile stays.
    // 2026-09-27, per explicit request: when "Get BG from this connection" is on (Settings, dependent on this connection being enabled),
    // glucose comes from here instead of from this phone's own main site -- see NsIncomingDataProcessor.bgFromLiveSite/processSgvs. Same
    // modified-since cursor pattern as LoadBgWorker, using the treatment cursor's own last-modified value as a simple starting point since
    // SGVs and treatments share the same NS "last modified" clock; a short 10-minute overlap keeps it safe either way.
    private suspend fun loadSecondaryGlucose(client: NSAndroidClient) {
        if (!activePlugin.isFollowerPhone(config) || !preferences.get(BooleanKey.NsClientBgFromLiveSite)) return
        try {
            val cursor = preferences.get(LongKey.NsClientSecondaryBgLastModified)
            val from = if (cursor == 0L) dateUtil.now() - T.hours(24).msecs() else (cursor - T.mins(10).msecs()).coerceAtLeast(0L)
            val response = client.getSgvsModifiedSince(from, 500)
            val sgvs = response.values
            response.lastServerModified?.takeIf { it > cursor }?.let { preferences.put(LongKey.NsClientSecondaryBgLastModified, it) }
            if (sgvs.isNotEmpty()) {
                rxBus.send(EventNSClientNewLog("◄ SEC-NS", "${sgvs.size} SGVs from Live's NS site"))
                nsIncomingDataProcessor.processSgvs(sgvs, doFullSync = false, fromLiveSite = true)
                storeDataForDb.storeGlucoseValuesToDb()
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS glucose failed", e)
            rxBus.send(EventNSClientNewLog("◄ SEC-NS ERR", "glucose: ${e.message ?: "Unknown error"}"))
        }
    }

    private suspend fun loadSecondaryProfileStore(client: NSAndroidClient) {
        if (!activePlugin.isFollowerPhone(config)) return
        try {
            val cursor = preferences.get(LongKey.NsClientSecondaryProfileLastModified)
            val response = if (cursor == 0L) client.getLastProfileStore() else client.getProfileModifiedSince(cursor)
            val profile = response.values.lastOrNull()
            if (profile == null) {
                response.lastServerModified?.takeIf { it > cursor }?.let { preferences.put(LongKey.NsClientSecondaryProfileLastModified, it) }
                return
            }
            val newCursor = response.lastServerModified
                ?: JsonHelper.safeGetLongAllowNull(profile, "srvModified")
                ?: dateUtil.now()
            preferences.put(LongKey.NsClientSecondaryProfileLastModified, newCursor)
            rxBus.send(EventNSClientNewLog("◄ SEC-NS", "1 PROFILE store from secondary NS"))
            nsIncomingDataProcessor.processProfile(profile, doFullSync = false, fromSecondary = true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS profile store failed", e)
            rxBus.send(EventNSClientNewLog("◄ SEC-NS ERR", "profile store: ${e.message ?: "Unknown error"}"))
        }
    }

    // 2026-09-27, per explicit request: the device status (Live's result text: mirrored steps, loop-phone snapshot) comes from this site
    // on a full AAPS on VirtualPump; the primary's is ignored in NSDeviceStatusHandler. Same 7-minute window as LoadDeviceStatusWorker.
    private suspend fun loadSecondaryDeviceStatus(client: NSAndroidClient) {
        if (!activePlugin.isFollowerPhone(config)) return
        try {
            val from = dateUtil.now() - T.mins(7).msecs()
            val statuses = client.getDeviceStatusModifiedSince(from)
            if (statuses.isNotEmpty()) nsDeviceStatusHandler.handleNewData(statuses.toTypedArray(), fromSecondary = true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS device status failed", e)
            rxBus.send(EventNSClientNewLog("◄ SEC-NS ERR", "device status: ${e.message ?: "Unknown error"}"))
        }
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
                            if (bolus.type != BS.Type.SMB && !isIapsEntry(treatment.enteredBy)) {
                                storeDataForDb.addToBoluses(bolus)
                                pageBoluses++
                            }
                        }

                        is NSCarbs -> {
                            storeDataForDb.addToCarbs(treatment.toCarbs())
                            pageCarbs++
                        }

                        // The bolus calculator result ("calc" in Treatments) of a meal entered on the other phone.
                        // It was dropped by the else branch, so the meal arrived without its calculation.
                        is NSBolusWizard -> {
                            treatment.toBolusCalculatorResult()?.let { storeDataForDb.addToBolusCalculatorResults(it) }
                        }

                        is NSTherapyEvent -> {
                            if (acceptTherapyEvents) {
                                val te = treatment.toTherapyEvent()
                                val note = te.note.orEmpty()
                                val anyDeskCommand = te.type == TE.Type.NOTE && note.trim() == "ADesk"
                                // Receipt Notes are stored for display only and never drive another command.
                                // Fifth character: R=real pump, V=Virtual Pump, C=Client.
                                val trimmedNote = note.trim()
                                val anyDeskAck = te.type == TE.Type.NOTE && (
                                    trimmedNote == "AckDesk" ||
                                        trimmedNote.startsWith("AcNS") ||
                                        trimmedNote.startsWith("AcTT") ||
                                        trimmedNote.startsWith("AcLT")
                                    )
                                // "SetRole <prefKey>=<profile>" carries a coded-profile-role assignment made in the
                                // ProfileSwitchDialog on the other device; stored locally so the OpenAPSAutoISFPlugin
                                // receiver block can apply it on the loop phone. Display/store only here, like the acks.
                                val setRoleCommand = te.type == TE.Type.NOTE && trimmedNote.startsWith("SetRole ")
                                val acceptedSecondaryEvent = te.type in secondaryTherapyEventTypes && (te.type != TE.Type.NOTE ||
                                    note.startsWith("StLow ") || note.startsWith("StorageLow ") || anyDeskCommand || anyDeskAck || setRoleCommand ||
                                    // 2026-09-27: on Virtual the Live notes it acts on (MJ active, Steroids, battery) come from this site.
                                    // A Client mirrors Live, so it takes every note Live wrote from the Live-pointing connection; Virtual
                                    // only takes the ones it acts on.
                                    config.AAPSCLIENT || (activePlugin.fullAapsOnVirtualPump(config) && CodedAutomationNames.isLiveEchoKeptOnVirtual(note)))
                                if (acceptedSecondaryEvent) {
                                    storeDataForDb.addToTherapyEvents(te)
                                    pageTherapyEvents++
                                }
                                // Do not execute commands found by the first 16-day recovery scan. On
                                // ordinary incremental syncs, srvModified makes the two-hour overlap safe:
                                // the same NS record cannot create another command revision.
                                if (anyDeskCommand && !recoveryScan) {
                                    val commandRevision = treatment.srvModified ?: treatment.srvCreated ?: te.timestamp
                                    if (commandRevision > preferences.get(LongKey.ApsAutoIsfAnyDeskSecondaryCommandAt))
                                        preferences.put(LongKey.ApsAutoIsfAnyDeskSecondaryCommandAt, commandRevision)
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
                        "$totalTherapyEvents therapy events from secondary NS"
                )
            )
            if (page >= MAX_PAGES && continueLoading) {
                rxBus.send(EventNSClientNewLog("◄ SEC-NS", "Recovery paused after $MAX_PAGES pages; continuing next sync"))
            }
            loadSecondaryProfileStore(client)
            loadSecondaryDeviceStatus(client)
            loadSecondaryGlucose(client)
            Result.success()
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS fetch failed", e)
            rxBus.send(EventNSClientNewLog("◄ SEC-NS ERR", e.message ?: "Unknown error"))
            Result.failure(workDataOf("Error" to e.message))
        }
    }
}
