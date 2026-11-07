package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.interfaces.NSAndroidClient
import app.aaps.core.objects.workflow.WorkOutcome
import app.aaps.core.utils.safeGetLongAllowNull
import app.aaps.core.utils.safeGetStringAllowNull
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import app.aaps.plugins.sync.nsclientV3.NsIncomingDataProcessor
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.JsonObject
import kotlin.math.max

@Inject
class LoadProfileStoreRunner(

    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,

    private val nsClientV3Plugin: NSClientV3Plugin,
    private val dateUtil: DateUtil,
    private val nsIncomingDataProcessor: NsIncomingDataProcessor,
    private val nsClientRepository: NSClientRepository
) {

    suspend fun run(): WorkOutcome {
        val isFirstLoad = nsClientV3Plugin.isFirstLoad(NsClient.Collection.PROFILE)
        if (preferences.get(BooleanKey.NsClientSecondaryEnabled) && !isFirstLoad) {
            nsClientRepository.addLog("◄ RCV PROFILE END", "Profile store comes from the secondary Nightscout")
            return WorkOutcome.Skipped("secondary")
        }
        val nsAndroidClient = nsClientV3Plugin.nsAndroidClient ?: return WorkOutcome.Failure("AndroidClient is null")

        try {
            // On first load, read primary source units even when the secondary site supplies the imported profiles.
            // processProfile keeps the existing secondary-only import rule.
            val lastLoaded = max(nsClientV3Plugin.lastLoadedSrvModified.collections.profile, dateUtil.now() - nsClientV3Plugin.maxAge)
            // A current profile can be older than the history download window and still be needed for source units.
            if (isFirstLoad || (nsClientV3Plugin.newestDataOnServer?.collections?.profile ?: Long.MAX_VALUE) > lastLoaded) {
                val response: NSAndroidClient.ReadResponse<List<JsonObject>> =
                    if (isFirstLoad) nsAndroidClient.getLastProfileStore()
                    else nsAndroidClient.getProfileModifiedSince(lastLoaded)
                val profiles = response.values
                if (profiles.isNotEmpty()) {
                    val profile = profiles[profiles.size - 1]
                    // if srvModified found in response
                    aapsLogger.debug(LTag.NSCLIENT, "lastLoadedSrvModified: ${response.lastServerModified}")
                    response.lastServerModified?.let { nsClientV3Plugin.lastLoadedSrvModified.collections.profile = it } ?:
                    // if srvModified found in record
                    profile.safeGetLongAllowNull("srvModified")?.let { nsClientV3Plugin.lastLoadedSrvModified.collections.profile = it } ?:
                    // if created_at found in record
                    profile.safeGetStringAllowNull("created_at", null)?.let { nsClientV3Plugin.lastLoadedSrvModified.collections.profile = dateUtil.fromISODateString(it) } ?:
                    // if not found reset to now
                    { nsClientV3Plugin.lastLoadedSrvModified.collections.profile = dateUtil.now() }
                    nsClientV3Plugin.storeLastLoadedSrvModified()
                    aapsLogger.debug(LTag.NSCLIENT, "PROFILE: $profile")
                    nsClientRepository.addLog("◄ RCV", "1 PROFILE from ${dateUtil.dateAndTimeAndSecondsString(lastLoaded)}")
                    nsIncomingDataProcessor.processProfile(profile, nsClientV3Plugin.doingFullSync)
                    // Trio's profile may be among the downloaded ones without being the last (2026-11-07)
                    nsIncomingDataProcessor.processTrioPushInfo(profiles)
                } else {
                    nsClientRepository.addLog("◄ RCV PROFILE END", "No new data from ${dateUtil.dateAndTimeAndSecondsString(lastLoaded)}")
                }
            } else {
                nsClientRepository.addLog("◄ RCV PROFILE END", "No data from ${dateUtil.dateAndTimeAndSecondsString(lastLoaded)}")
            }
            // The newest profile by date can be an AAPS one, and Trio's have no date at all, so on a first load or a
            // full sync look through the most recently saved profiles for Trio's (2026-11-07)
            if (isFirstLoad || nsClientV3Plugin.doingFullSync)
                runCatching { nsAndroidClient.getRecentProfiles(TRIO_PROFILE_SEARCH).values }
                    .onSuccess { nsIncomingDataProcessor.processTrioPushInfo(it.asReversed()) }
                    .onFailure { aapsLogger.error(LTag.NSCLIENT, "Looking for Trio's profile failed", it) }
        } catch (error: Exception) {
            aapsLogger.error("Error: ", error)
            nsClientRepository.addLog("◄ ERROR", error.message)
            return WorkOutcome.Failure(error.message ?: "error")
        }

        return WorkOutcome.Success
    }

    private companion object {

        /** How many of the most recently saved profiles are searched for Trio's */
        const val TRIO_PROFILE_SEARCH = 20
    }
}
