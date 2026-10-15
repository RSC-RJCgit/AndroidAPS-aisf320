package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.LiveSteps
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.InsulinType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSBolusWizard
import app.aaps.core.nssdk.localmodel.treatment.NSCarbs
import app.aaps.core.nssdk.localmodel.treatment.NSExtendedBolus
import app.aaps.core.nssdk.localmodel.treatment.NSProfileSwitch
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import app.aaps.core.nssdk.localmodel.treatment.NSTherapyEvent
import app.aaps.core.objects.workflow.WorkOutcome
import app.aaps.plugins.sync.nsclientV3.NsIncomingDataProcessor
import app.aaps.plugins.sync.nsclientV3.data.NSDeviceStatusHandler
import app.aaps.plugins.sync.nsclientV3.extensions.toBolus
import app.aaps.plugins.sync.nsclientV3.extensions.toBolusCalculatorResult
import app.aaps.plugins.sync.nsclientV3.extensions.toCarbs
import app.aaps.plugins.sync.nsclientV3.extensions.toTherapyEvent
import dev.zacsweers.metro.Inject

/**
 * Downloads the live phone's treatments from the secondary Nightscout.
 *
 * Runs when that site is turned on. The two addresses are not compared. Manual boluses and carbs
 * are automatic for clients and the Virtual Pump master; real pumps follow the receive switches.
 * Temp basals and extended boluses are never taken. Notes stay on
 * the short keep-list, including the one note "MJ active". The profile store and the live step
 * counts come from this site as well. The full app and a client take glucose from this site
 * when "Get BG from this connection" is on.
 */
@Inject
class LoadSecondaryTreatmentsRunner(
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val storeDataForDb: StoreDataForDb,
    private val profileFunction: ProfileFunction,
    private val nsClientRepository: NSClientRepository,
    private val nsIncomingDataProcessor: NsIncomingDataProcessor,
    private val nsDeviceStatusHandler: NSDeviceStatusHandler,
    private val activePlugin: ActivePlugin,
    private val persistenceLayer: PersistenceLayer,
) {

    suspend fun run(): WorkOutcome {
        if (!preferences.get(BooleanKey.NsClientSecondaryEnabled))
            return WorkOutcome.Skipped("Secondary NS disabled")

        val url = preferences.get(StringKey.NsClientSecondaryUrl).trim()
        val token = preferences.get(StringKey.NsClientSecondaryAccessToken).trim()
        if (url.isEmpty())
            return WorkOutcome.Failure("Secondary NS URL not configured")

        val client = NSAndroidClientImpl(
            baseUrl = url.lowercase().replace("https://", "").replace(Regex("/$"), ""),
            accessToken = token,
            logging = false,
            logger = { msg -> aapsLogger.debug(LTag.HTTP, "SecondaryNS: $msg") }
        )
        return try {
            download(client)
        } catch (error: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS fetch failed", error)
            nsClientRepository.addLog("◄ SEC-NS ERR", error.message ?: "Unknown error")
            WorkOutcome.Failure(error.message ?: "error")
        } finally {
            client.close()
        }
    }

    /**
     * Reads only the glucose from the secondary site (2026-10-08). Polled every minute while the loop runs every minute and
     * this site is the glucose source, so each reading is stored when it appears and the loop can run on it. Does not touch
     * the profile, treatments or steps; the full round does those every 5 minutes. Skipped when the site is not the glucose source.
     */
    suspend fun runGlucoseOnly(): WorkOutcome {
        if (!preferences.get(BooleanKey.NsClientSecondaryEnabled))
            return WorkOutcome.Skipped("Secondary NS disabled")
        if (!glucoseFromSecondarySite(secondaryEnabled = true, preferences.get(BooleanKey.NsClientBgFromLiveSite)))
            return WorkOutcome.Skipped("Glucose is not taken from the secondary NS")
        val url = preferences.get(StringKey.NsClientSecondaryUrl).trim()
        val token = preferences.get(StringKey.NsClientSecondaryAccessToken).trim()
        if (url.isEmpty())
            return WorkOutcome.Failure("Secondary NS URL not configured")
        val client = NSAndroidClientImpl(
            baseUrl = url.lowercase().replace("https://", "").replace(Regex("/$"), ""),
            accessToken = token,
            logging = false,
            logger = { msg -> aapsLogger.debug(LTag.HTTP, "SecondaryNS: $msg") }
        )
        return try {
            downloadGlucose(client)
            WorkOutcome.Success
        } finally {
            client.close()
        }
    }

    private suspend fun download(client: NSAndroidClientImpl): WorkOutcome {
        val isVirtualMaster = !config.AAPSCLIENT && activePlugin.activePump.selectedActivePump() is VirtualPump
        if (isVirtualMaster && preferences.get(LongNonKey.NsClientSecondaryVirtualMealRecoveryVersion) < 1L) {
            // Old scans advanced past meals when the separate receive switches were off.
            // Re-use the existing paged 16-day recovery; upserts retain already stored records.
            preferences.put(LongNonKey.NsClientSecondaryLastModified, 0L)
            preferences.put(LongNonKey.NsClientSecondaryVirtualMealRecoveryVersion, 1L)
            nsClientRepository.addLog("◄ SEC-NS", "Recovering 16 days of manual boluses and carbs for the Virtual Pump master")
        }
        var cursor = preferences.get(LongNonKey.NsClientSecondaryLastModified)
        val recoveryScan = cursor == 0L
        var queryFrom = if (recoveryScan) {
            (dateUtil.now() - RECOVERY_LOOKBACK_MS).coerceAtLeast(0L)
        } else {
            (cursor - CURSOR_OVERLAP_MS).coerceAtLeast(0L)
        }
        val acceptTherapyEvents = preferences.get(BooleanKey.NsClientSecondaryAcceptTherapyEvent)
        val insulin = fallbackInsulin()
        var totalTreatments = 0
        var totalBoluses = 0
        var totalCarbs = 0
        var totalTherapyEvents = 0
        var page = 0
        var continueLoading = true
        val recoveryNote = if (recoveryScan) " (16-day recovery)" else ""
        nsClientRepository.addLog("◄ SEC-NS", "Reading the profile from the secondary Nightscout")
        // The profile list still comes from this site. A profile switch does not. The primary
        // Nightscout applies a switch when Receive profile switches is on.
        downloadProfile(client)
        nsClientRepository.addLog(
            "◄ SEC-NS",
            "Fetching secondary treatments since ${dateUtil.dateAndTimeAndSecondsString(queryFrom)}$recoveryNote"
        )

        while (continueLoading && page < MAX_PAGES) {
            val response = client.getTreatmentsModifiedSince(queryFrom, PAGE_SIZE)
            val treatments = response.values
            if (treatments.isEmpty()) {
                storeDataForDb.storeTreatmentsToDb(fullSync = false)
                response.lastServerModified?.takeIf { it > cursor }?.let {
                    cursor = it
                    preferences.put(LongNonKey.NsClientSecondaryLastModified, cursor)
                }
                break
            }

            var pageBoluses = 0
            var pageCarbs = 0
            var pageTherapyEvents = 0
            for (treatment in treatments) {
                if (treatment.date == null) continue
                when (treatment) {
                    is NSBolus -> {
                        // A client has no Receive insulin switch (the setting is hidden there and stays off), and it takes
                        // every treatment on the main site anyway. Without this the meal boluses entered on the other
                        // phone never reached the client's graph, while their calculator records did.
                        if (!secondaryMealTreatmentsAccepted(preferences.get(BooleanKey.NsClientAcceptInsulin), config.AAPSCLIENT, isVirtualMaster)) continue
                        val bolus = treatment.toBolus(insulin)
                        if (secondaryBolusAccepted(bolus.type)) {
                            storeDataForDb.addToBoluses(bolus)
                            pageBoluses++
                        }
                    }

                    is NSCarbs -> {
                        // Same reason as for the boluses above: a client has no Receive carbs switch.
                        if (!secondaryMealTreatmentsAccepted(preferences.get(BooleanKey.NsClientAcceptCarbs), config.AAPSCLIENT, isVirtualMaster)) continue
                        storeDataForDb.addToCarbs(treatment.toCarbs())
                        pageCarbs++
                    }

                    // The bolus calculator result ("calc" in Treatments) of a meal entered on the other phone. It is only
                    // a record of the wizard run, it delivers nothing, so it needs no receive switch of its own.
                    is NSBolusWizard -> {
                        treatment.toBolusCalculatorResult()?.let { storeDataForDb.addToBolusCalculatorResults(it) }
                    }

                    is NSProfileSwitch, is NSTemporaryBasal, is NSExtendedBolus -> Unit

                    is NSTherapyEvent -> {
                        if (!acceptTherapyEvents) continue
                        val therapyEvent = treatment.toTherapyEvent()
                        if (secondaryTherapyEventAccepted(therapyEvent.type, therapyEvent.note, config.AAPSCLIENT)) {
                            storeDataForDb.addToTherapyEvents(therapyEvent)
                            pageTherapyEvents++
                        }
                    }

                    else -> Unit
                }
            }

            storeDataForDb.storeTreatmentsToDb(fullSync = false)
            val nextCursor = response.lastServerModified
            if (nextCursor != null && nextCursor > cursor) {
                cursor = nextCursor
                preferences.put(LongNonKey.NsClientSecondaryLastModified, cursor)
            }

            totalTreatments += treatments.size
            totalBoluses += pageBoluses
            totalCarbs += pageCarbs
            totalTherapyEvents += pageTherapyEvents
            page++

            val cursorAdvanced = nextCursor != null && nextCursor > queryFrom
            continueLoading = response.code != 304 && treatments.size >= PAGE_SIZE && cursorAdvanced
            if (continueLoading && nextCursor != null) queryFrom = nextCursor
        }

        nsClientRepository.addLog(
            "◄ SEC-NS",
            "$totalTreatments treatments in $page page(s): $totalBoluses boluses $totalCarbs carbs $totalTherapyEvents therapy events from secondary NS"
        )
        if (page >= MAX_PAGES && continueLoading)
            nsClientRepository.addLog("◄ SEC-NS", "Recovery paused after $MAX_PAGES pages; continuing next sync")
        downloadLiveSteps(client)
        downloadGlucose(client)
        return WorkOutcome.Success
    }

    // Step buckets written into the live phone's loop reason. This phone's own Nightscout is not used.
    private suspend fun downloadLiveSteps(client: NSAndroidClientImpl) {
        if (!preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual)) return
        try {
            val from = dateUtil.now() - LiveSteps.MAX_AGE_MS
            val statuses = client.getDeviceStatusModifiedSince(from)
            val outcome = nsDeviceStatusHandler.takeLiveSteps(statuses)
            // Always logged (also when nothing came back), so a missing step import can be traced to the step that fails.
            nsClientRepository.addLog("◄ SEC-NS", "Step import: ${statuses.size} device status rows in the last 20 min; $outcome")
        } catch (error: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS step fetch failed", error)
            nsClientRepository.addLog("◄ SEC-NS ERR", error.message ?: "Steps error")
        }
    }

    // The profile store for this phone comes from the secondary site, not from its own Nightscout.
    private suspend fun downloadProfile(client: NSAndroidClientImpl) {
        try {
            val cursor = preferences.get(LongNonKey.NsClientSecondaryProfileModified)
            val response = if (cursor == 0L) client.getLastProfileStore() else client.getProfileModifiedSince(cursor)
            val profile = response.values.lastOrNull()
            if (profile == null) {
                nsClientRepository.addLog("◄ SEC-NS", "No profile store from secondary NS")
                return
            }
            nsIncomingDataProcessor.processProfile(profile, doFullSync = false, fromSecondary = true)
            val modified = response.lastServerModified ?: dateUtil.now()
            if (modified > cursor) preferences.put(LongNonKey.NsClientSecondaryProfileModified, modified)
            nsClientRepository.addLog("◄ SEC-NS", "Profile store from secondary NS")
        } catch (error: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS profile fetch failed", error)
            nsClientRepository.addLog("◄ SEC-NS ERR", error.message ?: "Profile error")
        }
    }

    // Smoothed readings live on the second site. One day fills the graph; later polls take what changed.
    // The first read is oldest-first and 500 at a time, so a full page is not the current glucose.
    // Keep reading until a page is short. 0 is not a saved place. The cursor is the newest srvModified.
    private suspend fun downloadGlucose(client: NSAndroidClientImpl) {
        if (!glucoseFromSecondarySite(
                secondaryEnabled = true,
                preferences.get(BooleanKey.NsClientBgFromLiveSite)
            )
        ) return
        try {
            val cursor = preferences.get(LongNonKey.NsClientSecondaryGlucoseModified)
            val firstCatchUp = cursor == 0L
            var from = if (firstCatchUp) dateUtil.now() - GLUCOSE_LOOKBACK_MS
            else (cursor - GLUCOSE_OVERLAP_MS).coerceAtLeast(0L)
            var page = 0
            var total = 0
            var newestModified = 0L
            var reachedNewest = false
            while (page < MAX_PAGES) {
                val response = if (firstCatchUp) client.getSgvsNewerThan(from, PAGE_SIZE)
                else client.getSgvsModifiedSince(from, PAGE_SIZE)
                val sgvs = response.values
                if (sgvs.isNotEmpty()) {
                    nsIncomingDataProcessor.processSgvs(
                        sgvs.sortedBy { it.date ?: 0L },
                        doFullSync = false,
                        fromLiveSite = true,
                    )
                }
                total += sgvs.size
                val newestDate = sgvs.maxOfOrNull { it.date ?: 0L } ?: 0L
                val pageModified = sgvs.maxOfOrNull { it.srvModified ?: 0L } ?: 0L
                if (pageModified > newestModified) newestModified = pageModified
                page++
                val next = nextGlucoseFrom(firstCatchUp, from, sgvs.size, PAGE_SIZE, newestDate, pageModified)
                if (next == null) {
                    reachedNewest = true
                    break
                }
                from = next
            }
            storeDataForDb.storeGlucoseValuesToDb()
            glucoseCursorToStore(cursor, newestModified, firstCatchUp, reachedNewest)?.let {
                preferences.put(LongNonKey.NsClientSecondaryGlucoseModified, it)
            }
            nsClientRepository.addLog("◄ SEC-NS", "$total glucose values from secondary NS")
        } catch (error: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS glucose fetch failed", error)
            nsClientRepository.addLog("◄ SEC-NS ERR", error.message ?: "Glucose error")
        }
    }

    /**
     * BG history for a full sync (2026-10-15, per explicit request). Live's site has the most complete glucose, so a full
     * sync takes the BG history from here whatever "Get BG from this connection" says; `LoadBgRunner` then skips the main
     * site's BG for that sync. Reads from [from] to now, oldest first.
     *
     * A reading is not stored when this phone already has one within [NEAR_DUPLICATE_MS]. The two sites normally carry the
     * same readings with the same times, which the database stores once anyway; the margin is for a site that ever holds
     * its own copy a few seconds apart, which would otherwise show two dots a minute.
     *
     * @return how many readings the site sent (stored or not), or null when no site is set up or the read failed. The
     * caller falls back to the main site on null or 0.
     */
    suspend fun downloadGlucoseForFullSync(from: Long): Int? {
        if (!preferences.get(BooleanKey.NsClientSecondaryEnabled)) return null
        val url = preferences.get(StringKey.NsClientSecondaryUrl).trim()
        if (url.isEmpty()) return null
        val client = NSAndroidClientImpl(
            baseUrl = url.lowercase().replace("https://", "").replace(Regex("/$"), ""),
            accessToken = preferences.get(StringKey.NsClientSecondaryAccessToken).trim(),
            logging = false,
            logger = { msg -> aapsLogger.debug(LTag.HTTP, "SecondaryNS: $msg") }
        )
        return try {
            val now = dateUtil.now()
            val existing = persistenceLayer.getBgReadingsDataFromTimeToTime(from - NEAR_DUPLICATE_MS, now + NEAR_DUPLICATE_MS, true)
                .map { it.timestamp }.sorted().toLongArray()
            var pageFrom = from
            var page = 0
            var received = 0
            var stored = 0
            while (page < MAX_FULL_SYNC_PAGES) {
                val sgvs = client.getSgvsNewerThan(pageFrom, PAGE_SIZE).values
                val missing = sgvs.filter { sgv -> sgv.date?.let { !hasReadingNear(existing, it, NEAR_DUPLICATE_MS) } == true }
                if (missing.isNotEmpty())
                    nsIncomingDataProcessor.processSgvs(missing.sortedBy { it.date ?: 0L }, doFullSync = true, fromLiveSite = true)
                received += sgvs.size
                stored += missing.size
                page++
                val newestDate = sgvs.maxOfOrNull { it.date ?: 0L } ?: 0L
                pageFrom = nextGlucoseFrom(firstCatchUp = true, pageFrom, sgvs.size, PAGE_SIZE, newestDate, 0L) ?: break
            }
            storeDataForDb.storeGlucoseValuesToDb()
            nsClientRepository.addLog("◄ SEC-NS", "Full sync: $received glucose values from secondary NS, $stored new")
            received
        } catch (error: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS full sync glucose failed", error)
            nsClientRepository.addLog("◄ SEC-NS ERR", error.message ?: "Glucose error")
            null
        } finally {
            client.close()
        }
    }

    private fun fallbackInsulin(): ICfg =
        profileFunction.runningICfg.value ?: InsulinType.OREF_RAPID_ACTING.iCfg

    private companion object {

        const val PAGE_SIZE = 500
        const val MAX_PAGES = 64
        const val CURSOR_OVERLAP_MS = 2L * 60 * 60 * 1000
        const val RECOVERY_LOOKBACK_MS = 16L * 24 * 60 * 60 * 1000
        const val GLUCOSE_LOOKBACK_MS = 24L * 60 * 60 * 1000
        const val GLUCOSE_OVERLAP_MS = 10L * 60 * 1000
        // 100 days of 1-minute readings is about 290 pages of 500.
        const val MAX_FULL_SYNC_PAGES = 400
        const val NEAR_DUPLICATE_MS = 30L * 1000
    }
}

// True when [sorted] (ascending times) has a time within [toleranceMs] of [time].
internal fun hasReadingNear(sorted: LongArray, time: Long, toleranceMs: Long): Boolean {
    var low = 0
    var high = sorted.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val value = sorted[mid]
        when {
            value < time - toleranceMs -> low = mid + 1
            value > time + toleranceMs -> high = mid - 1
            else                       -> return true
        }
    }
    return false
}

// A full page is only the oldest slice. The next read starts after the newest time on that page.
// A short page, or a page that does not move forward, means this read is finished.
internal fun nextGlucoseFrom(
    firstCatchUp: Boolean,
    from: Long,
    count: Int,
    pageSize: Int,
    newestDate: Long,
    newestModified: Long,
): Long? {
    if (count < pageSize) return null
    val next = if (firstCatchUp) newestDate else newestModified
    if (next <= from) return null
    return next
}

// 0 is not a saved place. The first walk stores a cursor only after the newest page is in.
internal fun glucoseCursorToStore(
    cursor: Long,
    newestModified: Long,
    firstCatchUp: Boolean,
    reachedNewest: Boolean,
): Long? {
    if (newestModified <= 0L || newestModified <= cursor) return null
    if (firstCatchUp && !reachedNewest) return null
    return newestModified
}
