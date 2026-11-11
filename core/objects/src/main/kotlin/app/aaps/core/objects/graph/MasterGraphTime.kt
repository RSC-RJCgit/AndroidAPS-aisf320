package app.aaps.core.objects.graph

import app.aaps.core.keys.time.AppTimeZone
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.text.format.DateFormat
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone
import kotlin.math.roundToInt

object MasterGraphTime {

    const val FIELD = "masterTimeZone"
    const val MANUAL_OFFSET_PATTERN = "[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00)"
    private val manualOffsetPattern = Regex(MANUAL_OFFSET_PATTERN)

    fun forUpload(config: Config): String? =
        if (config.appInitialized && config.APS && !config.AAPSCLIENT) AppTimeZone.current().id else null

    // Master free storage beside the clock under the hours drop-down (2026-11-11, per explicit request). The master sends its
    // free storage in whole GB (1 GB = 1,000,000,000 bytes, as Android Settings shows it) with masterTimeZone; Client and
    // Virtual show the mirrored value while it is under 30 min old. The master itself shows its own.
    const val FREE_STORAGE_FIELD = "masterFreeGB"
    private const val FREE_STORAGE_MAX_AGE_MS = 30 * 60_000L

    private class MirroredFreeStorage(val timestamp: Long, val gb: Int)
    @Volatile private var mirroredFreeStorage: MirroredFreeStorage? = null

    fun localFreeStorageGb(): Int? =
        runCatching { (StatFs(Environment.getDataDirectory().path).availableBytes / 1_000_000_000.0).roundToInt() }.getOrNull()

    fun freeStorageForUpload(config: Config): Int? =
        if (config.appInitialized && config.APS && !config.AAPSCLIENT) localFreeStorageGb() else null

    @Synchronized
    fun rememberFreeStorage(preferences: Preferences, fromSecondary: Boolean, gb: Int, timestamp: Long, now: Long) {
        if (site(preferences, fromSecondary).isEmpty() || gb < 0 || timestamp <= 0L || timestamp > now + 5 * 60_000L) return
        val previous = mirroredFreeStorage
        if (previous == null || timestamp > previous.timestamp) mirroredFreeStorage = MirroredFreeStorage(timestamp, gb)
    }

    /** The clock text with the master's free storage after it, e.g. "14:32  48 GB"; just the time when none is known. */
    fun withFreeStorage(time: String, config: Config, now: Long): String {
        val mirrored = mirroredFreeStorage?.takeIf { now - it.timestamp in 0L..FREE_STORAGE_MAX_AGE_MS }?.gb
        val gb = mirrored ?: if (config.APS && !config.AAPSCLIENT) localFreeStorageGb() else null
        return if (gb == null) time else "$time  $gb GB"
    }

    @Synchronized
    fun remember(preferences: Preferences, fromSecondary: Boolean, zone: String, timestamp: Long, now: Long): Boolean {
        val site = site(preferences, fromSecondary)
        if (site.isEmpty() || timestamp <= 0L || timestamp > now + 5 * 60_000L || parseZone(zone) == null) return false
        val previous = read(preferences, site)
        if (previous != null && timestamp <= previous.optLong("timestamp")) return false
        val report = JSONObject().put("site", site).put("timestamp", timestamp).put("zone", zone)
        preferences.put(StringNonKey.ClientGraphMasterTimeZone, report.toString())
        return previous?.optString("zone") != zone
    }

    fun forClient(config: Config, preferences: Preferences): TimeZone? {
        if (!config.AAPSCLIENT && !config.APS) return null
        return resolve(preferences)
    }

    private class CachedZone(val preferences: Preferences, val readAt: Long, val zone: TimeZone?)
    @Volatile private var cachedZone: CachedZone? = null

    fun invalidate() { cachedZone = null }

    /** Avoid reading settings for every minute of historical basal calculations. */
    fun forVirtualMaster(preferences: Preferences): TimeZone? {
        val now = System.nanoTime()
        val cached = cachedZone
        if (cached != null && cached.preferences === preferences && now - cached.readAt in 0L..1_000_000_000L) return cached.zone
        return resolve(preferences).also { cachedZone = CachedZone(preferences, now, it) }
    }

    private fun resolve(preferences: Preferences): TimeZone? {
        val imported = if (preferences.get(BooleanKey.ClientGraphFollowMasterTimeZone)) {
            val site = site(preferences, usesSecondarySite(preferences))
            read(preferences, site)?.optString("zone")?.let(::parseZone)
        } else null
        val zone = imported ?: preferences.get(StringKey.ClientGraphManualUtcOffset).trim()
            .takeIf(manualOffsetPattern::matches)?.let(ZoneOffset::of) ?: return null
        return TimeZone.getTimeZone(zone)
    }

    fun timeString(context: Context, dateUtil: DateUtil, timestamp: Long, zone: TimeZone?): String =
        if (zone == null) dateUtil.timeString(timestamp)
        else DateFormat.getTimeFormat(context).apply { timeZone = zone }.format(Date(timestamp))

    fun dateAndTimeString(context: Context, dateUtil: DateUtil, timestamp: Long, zone: TimeZone?): String =
        if (zone == null) dateUtil.dateAndTimeString(timestamp)
        else {
            val date = DateFormat.getDateFormat(context).apply { timeZone = zone }.format(Date(timestamp))
            "$date ${timeString(context, dateUtil, timestamp, zone)}"
        }

    private fun usesSecondarySite(preferences: Preferences): Boolean =
        preferences.get(BooleanKey.NsClientSecondaryEnabled) && preferences.get(StringKey.NsClientSecondaryUrl).isNotBlank()

    private fun site(preferences: Preferences, fromSecondary: Boolean): String {
        if (fromSecondary != usesSecondarySite(preferences)) return ""
        val key = if (fromSecondary) StringKey.NsClientSecondaryUrl else StringKey.NsClientUrl
        return preferences.get(key).trim().trimEnd('/')
    }

    private fun read(preferences: Preferences, site: String): JSONObject? {
        if (site.isEmpty()) return null
        val saved = preferences.get(StringNonKey.ClientGraphMasterTimeZone)
        if (saved.isEmpty()) return null
        return runCatching { JSONObject(saved) }.getOrNull()?.takeIf { it.optString("site") == site }
    }

    private fun parseZone(zone: String): ZoneId? = runCatching { ZoneId.of(zone) }.getOrNull()
}
