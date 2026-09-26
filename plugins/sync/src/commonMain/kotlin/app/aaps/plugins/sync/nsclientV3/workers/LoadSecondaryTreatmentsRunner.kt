package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.insulin.InsulinType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSCarbs
import app.aaps.core.nssdk.localmodel.treatment.NSTherapyEvent
import app.aaps.core.objects.workflow.WorkOutcome
import app.aaps.plugins.sync.nsclientV3.NsIncomingDataProcessor
import app.aaps.plugins.sync.nsclientV3.extensions.toBolus
import app.aaps.plugins.sync.nsclientV3.extensions.toCarbs
import app.aaps.plugins.sync.nsclientV3.extensions.toTherapyEvent
import dev.zacsweers.metro.Inject

/**
 * Downloads manual boluses, carbs, and the profile store from a second Nightscout site.
 *
 * Runs on its own, not after the primary status check. A rejected token on this phone's own
 * Nightscout must not stop the virtual pump from receiving carbs and boluses entered elsewhere.
 * SMBs are left out. Device events are included only when that option is on.
 */
@Inject
class LoadSecondaryTreatmentsRunner(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val storeDataForDb: StoreDataForDb,
    private val profileFunction: ProfileFunction,
    private val nsClientRepository: NSClientRepository,
    private val nsIncomingDataProcessor: NsIncomingDataProcessor,
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

    private suspend fun download(client: NSAndroidClientImpl): WorkOutcome {
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
                        val bolus = treatment.toBolus(insulin)
                        if (secondaryBolusAccepted(bolus.type)) {
                            storeDataForDb.addToBoluses(bolus)
                            pageBoluses++
                        }
                    }

                    is NSCarbs -> {
                        storeDataForDb.addToCarbs(treatment.toCarbs())
                        pageCarbs++
                    }

                    is NSTherapyEvent -> {
                        if (!acceptTherapyEvents) continue
                        val therapyEvent = treatment.toTherapyEvent()
                        if (secondaryTherapyEventAccepted(therapyEvent.type, therapyEvent.note)) {
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
        downloadProfile(client)
        return WorkOutcome.Success
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

    private fun fallbackInsulin(): ICfg =
        profileFunction.runningICfg.value ?: InsulinType.OREF_RAPID_ACTING.iCfg

    private companion object {

        const val PAGE_SIZE = 500
        const val MAX_PAGES = 64
        const val CURSOR_OVERLAP_MS = 2L * 60 * 60 * 1000
        const val RECOVERY_LOOKBACK_MS = 16L * 24 * 60 * 60 * 1000
    }
}
