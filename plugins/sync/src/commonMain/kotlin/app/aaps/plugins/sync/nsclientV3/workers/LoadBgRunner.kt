package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.source.NSClientSource
import app.aaps.core.interfaces.source.SecondaryNsSource
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.interfaces.NSAndroidClient
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.objects.workflow.WorkOutcome
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import app.aaps.plugins.sync.nsclientV3.NsIncomingDataProcessor
import dev.zacsweers.metro.Inject
import kotlin.math.max

/**
 * Fetches glucose values (and the calibration entries that ride the same cursor) from Nightscout.
 *
 * The body of `LoadBgWorker`, split out so it is plain Kotlin. Nothing here knows about WorkManager;
 * the worker is a shim that maps [WorkOutcome] onto a `ListenableWorker.Result`.
 */
@Inject
class LoadBgRunner(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val nsClientV3Plugin: NSClientV3Plugin,
    private val nsClientSource: NSClientSource,
    private val nsIncomingDataProcessor: NsIncomingDataProcessor,
    private val storeDataForDb: StoreDataForDb,
    private val nsClientRepository: NSClientRepository,
    private val loadSecondaryTreatmentsRunner: LoadSecondaryTreatmentsRunner,
    private val activePlugin: ActivePlugin
) {

    suspend fun run(): WorkOutcome {
        if (!nsClientSource.isEnabled() && !preferences.get(BooleanKey.NsClientAcceptCgmData) && !nsClientV3Plugin.doingFullSync)
            return WorkOutcome.Skipped("Load not enabled")
        // A Full synchronization is an explicit request for the history held on this connection, so it is never skipped
        // for the second site: with the switch on, a client was left with only the short windows the second site returns.
        // 2026-10-30, per explicit request: one BG source, a full sync included, and no fallback either way. With "Get BG
        // from this connection" on, the BG history comes from the second site only; when it gives nothing the graph stays
        // empty rather than taking this site's BG. With it off, a full sync reads this site only, never the second one.
        if (glucoseFromSecondarySite(
                preferences.get(BooleanKey.NsClientSecondaryEnabled),
                preferences.get(BooleanKey.NsClientBgFromLiveSite),
                preferences.get(BooleanKey.NsClientRawFromSecondary)
            )
        ) {
            if (!nsClientV3Plugin.doingFullSync) return WorkOutcome.Skipped("secondary")
            // 2026-10-31, per explicit request: the Secondary NS BG source counts too. A master with its own CGM as the
            // BG source still never takes the second site's BG, but with this source that CGM is not in use.
            if (nsClientSource.isEnabled() || preferences.get(BooleanKey.NsClientAcceptCgmData) || activePlugin.activeBgSource is SecondaryNsSource) {
                val received = loadSecondaryTreatmentsRunner.downloadGlucoseForFullSync(dateUtil.now() - nsClientV3Plugin.maxAge)
                if (received != null && received > 0) nsClientRepository.addLog("◄ RCV BG END", "Full sync took BG from the secondary NS")
                else nsClientRepository.addLog("◄ RCV BG END", "Secondary NS gave no BG, none taken from this site")
            }
            // This site's BG is skipped for this sync. Normal syncs go on from now, not from 100 days back.
            nsClientV3Plugin.lastLoadedSrvModified.collections.entries = dateUtil.now()
            nsClientV3Plugin.storeLastLoadedSrvModified()
            return WorkOutcome.Success
        }

        val nsAndroidClient = nsClientV3Plugin.nsAndroidClient ?: return WorkOutcome.Failure("AndroidClient is null")
        var continueLoading = true
        try {
            while (continueLoading) {
                val isFirstLoad = nsClientV3Plugin.isFirstLoad(NsClient.Collection.ENTRIES)
                val lastLoaded =
                    if (isFirstLoad) max(nsClientV3Plugin.firstLoadContinueTimestamp.collections.entries, dateUtil.now() - nsClientV3Plugin.maxAge)
                    else max(nsClientV3Plugin.lastLoadedSrvModified.collections.entries, dateUtil.now() - nsClientV3Plugin.maxAge)
                if ((nsClientV3Plugin.newestDataOnServer?.collections?.entries ?: Long.MAX_VALUE) > lastLoaded) {
                    val sgvs: List<NSSgvV3>
                    var response: NSAndroidClient.ReadResponse<List<NSSgvV3>>?
                    // Replaces the old `response.code != 304` brake.
                    //
                    // A 304 could only ever appear because OkHttp's disk cache revalidated a GET, and
                    // that cache is gone with Retrofit. What the 304 actually meant is tested directly
                    // now: the server has nothing newer, so the cursor cannot move and another page
                    // would return the same rows for ever.
                    //
                    // Only the modified-since path can stall like this. The first load pages by date
                    // and always moves forward, and its response carries no server timestamp at all.
                    var cursorStalled = false
                    if (isFirstLoad) response = nsAndroidClient.getSgvsNewerThan(lastLoaded, NSClientV3Plugin.RECORDS_TO_LOAD)
                    else {
                        response = nsAndroidClient.getSgvsModifiedSince(lastLoaded, NSClientV3Plugin.RECORDS_TO_LOAD)
                        aapsLogger.debug(LTag.NSCLIENT, "lastLoadedSrvModified: ${response.lastServerModified}")
                        cursorStalled = response.lastServerModified?.let { it <= lastLoaded } == true
                        response.lastServerModified?.let { nsClientV3Plugin.lastLoadedSrvModified.collections.entries = it }
                        // 2026-10-20, per explicit request: readings written with the v1 API (iAPS, older uploaders) carry no
                        // srvModified, so "modified since" never returns them, while the server's newest entry (taken from the
                        // reading time) is newer. Then ask by reading time instead, and move the mark to the newest reading.
                        // An AAPS site never gets here: its readings come back from the first question.
                        val newestOnServer = nsClientV3Plugin.newestDataOnServer?.collections?.entries
                        if (response.values.isEmpty() && newestOnServer != null && newestOnServer > lastLoaded) {
                            val byDate = nsAndroidClient.getSgvsNewerThan(lastLoaded, NSClientV3Plugin.RECORDS_TO_LOAD)
                            val newestDate = byDate.values.mapNotNull { it.date }.maxOrNull()
                            if (newestDate != null && newestDate > lastLoaded) {
                                nsClientRepository.addLog("◄ RCV", "${byDate.values.size} SVGs without a server change time, read by reading time")
                                response = byDate
                                cursorStalled = false
                                nsClientV3Plugin.lastLoadedSrvModified.collections.entries = newestDate
                            }
                        }
                        nsClientV3Plugin.storeLastLoadedSrvModified()
                        nsClientV3Plugin.scheduleIrregularExecution() // Idea is to run after 5 min after last BG
                    }
                    sgvs = response.values
                    // Calibration mbg entries ride the same entries fetch + cursor; ingest them
                    // regardless of whether there were any sgvs in this page.
                    if (response.calibrations.isNotEmpty()) {
                        nsClientRepository.addLog("◄ RCV", "${response.calibrations.size} calibrations from ${dateUtil.dateAndTimeAndSecondsString(lastLoaded)}")
                        nsIncomingDataProcessor.processCalibrations(response.calibrations, nsClientV3Plugin.doingFullSync)
                    }
                    // Count only (2026-10-27): the page itself is summed up by processSgvs
                    aapsLogger.debug(LTag.NSCLIENT, "SGVS: ${sgvs.size}")
                    if (sgvs.isNotEmpty()) {
                        val action = if (isFirstLoad) "RCV-F" else "RCV"
                        nsClientRepository.addLog("◄ $action", "${sgvs.size} SVGs from ${dateUtil.dateAndTimeAndSecondsString(lastLoaded)}")
                        // Schedule processing of fetched data and continue of loading
                        continueLoading = !cursorStalled && nsIncomingDataProcessor.processSgvs(sgvs, nsClientV3Plugin.doingFullSync, advanceInitialLoadCursor = true)
                    } else {
                        // End first load
                        if (isFirstLoad) {
                            nsClientV3Plugin.lastLoadedSrvModified.collections.entries = lastLoaded
                            nsClientV3Plugin.storeLastLoadedSrvModified()
                        }
                        nsClientRepository.addLog("◄ RCV BG END", "No data from ${dateUtil.dateAndTimeAndSecondsString(lastLoaded)}")
                        continueLoading = false
                    }
                } else {
                    // End first load
                    if (isFirstLoad) {
                        nsClientV3Plugin.lastLoadedSrvModified.collections.entries = lastLoaded
                        nsClientV3Plugin.storeLastLoadedSrvModified()
                    }
                    nsClientRepository.addLog("◄ RCV BG END", "No new data from ${dateUtil.dateAndTimeAndSecondsString(lastLoaded)}")
                    continueLoading = false
                }
            }
        } catch (error: Exception) {
            aapsLogger.error("Error: ", error)
            nsClientRepository.addLog("◄ ERROR", error.message)
            nsClientV3Plugin.lastOperationError = error.message
            return WorkOutcome.Failure(error.message ?: "error")
        }

        storeDataForDb.storeGlucoseValuesToDb()
        storeDataForDb.storeCalibrationEntriesToDb()
        // iOS: this site's BG is stored, so its readings without raw can now take the second site's (SecondaryRawStore)
        if (nsClientV3Plugin.doingFullSync &&
            rawFromSecondarySite(preferences.get(BooleanKey.NsClientSecondaryEnabled), preferences.get(BooleanKey.NsClientRawFromSecondary))
        ) loadSecondaryTreatmentsRunner.fillRawForFullSync(dateUtil.now() - nsClientV3Plugin.maxAge)
        nsClientV3Plugin.lastOperationError = null
        return WorkOutcome.Success
    }
}
