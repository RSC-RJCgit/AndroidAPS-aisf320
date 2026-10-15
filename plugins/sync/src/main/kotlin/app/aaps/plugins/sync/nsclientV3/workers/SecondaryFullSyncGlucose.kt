package app.aaps.plugins.sync.nsclientV3.workers

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.StoreDataForDb
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.plugins.sync.nsShared.NsIncomingDataProcessor
import app.aaps.plugins.sync.nsShared.isFollowerPhone
import app.aaps.plugins.sync.nsShared.liveConnectionConfigured
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BG history for a full sync from the second site (2026-10-15, per explicit request).
 *
 * Live's site has the most complete glucose, so on a follower phone (a client, or a full AAPS on VirtualPump) a full sync
 * takes the BG history from there whatever "Get BG from this connection" says. [LoadBgWorker] then skips this phone's own
 * main site for that sync. When the second site gives nothing, the full sync takes BG from the main site, as before.
 *
 * A reading is not stored when this phone already has one within [NEAR_DUPLICATE_MS]. The two sites normally carry the same
 * readings with the same times, which the database stores once anyway; the margin is for a site that ever holds its own copy
 * a few seconds apart, which would otherwise show two dots a minute.
 */
@Singleton
class SecondaryFullSyncGlucose @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
    private val preferences: Preferences,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil,
    private val rxBus: RxBus,
    private val persistenceLayer: PersistenceLayer,
    private val nsIncomingDataProcessor: NsIncomingDataProcessor,
    private val storeDataForDb: StoreDataForDb
) {

    /** True on a follower phone with a second site set up. A real master with its own CGM never takes BG from it. */
    fun applies(): Boolean = activePlugin.isFollowerPhone(config) && preferences.liveConnectionConfigured()

    /**
     * Reads the second site's BG from [from] to now, oldest first.
     *
     * @return how many readings the site sent (stored or not), or null when the read failed. The caller falls back to the
     * main site on null or 0.
     */
    suspend fun download(from: Long): Int? {
        val client = NSAndroidClientImpl(
            baseUrl = preferences.get(StringKey.NsClientSecondaryUrl).trim().lowercase().replace("https://", "").replace(Regex("/$"), ""),
            accessToken = preferences.get(StringKey.NsClientSecondaryAccessToken).trim(),
            context = context,
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
            while (page < MAX_PAGES) {
                val sgvs = client.getSgvsNewerThan(pageFrom, PAGE_SIZE).values
                val missing = sgvs.filter { sgv -> sgv.date?.let { !hasReadingNear(existing, it, NEAR_DUPLICATE_MS) } == true }
                if (missing.isNotEmpty())
                    nsIncomingDataProcessor.processSgvs(missing.sortedBy { it.date ?: 0L }, doFullSync = true, fromLiveSite = true)
                received += sgvs.size
                stored += missing.size
                page++
                val newest = sgvs.maxOfOrNull { it.date ?: 0L } ?: 0L
                // A short page is the last one; a page that does not move forward would repeat for ever.
                if (sgvs.size < PAGE_SIZE || newest <= pageFrom) break
                pageFrom = newest
            }
            storeDataForDb.storeGlucoseValuesToDb()
            rxBus.send(EventNSClientNewLog("◄ SEC-NS", "Full sync: $received glucose values from secondary NS, $stored new"))
            received
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Secondary NS full sync glucose failed", e)
            rxBus.send(EventNSClientNewLog("◄ SEC-NS ERR", e.message ?: "Glucose error"))
            null
        }
    }

    private companion object {

        const val PAGE_SIZE = 500
        // 100 days of 1-minute readings is about 290 pages of 500.
        const val MAX_PAGES = 400
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
