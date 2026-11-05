package app.aaps.plugins.sync.nsclient

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Simulator-only, one-reading-at-a-time replay through the selected legacy NSClient connection. */
@Singleton
class SimulatorBgReplay @Inject constructor(
    private val preferences: Preferences,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil,
    private val logger: AAPSLogger,
    private val uiInteraction: UiInteraction,
    private val rxBus: RxBus,
    private val rh: ResourceHelper,
    private val persistenceLayer: PersistenceLayer
) {
    @Volatile private var stopped = true
    private var lastWarning: String? = null
    private var lastStoredAt = 0L
    @Volatile private var fullSyncRequested = false
    private data class Recovery(val from: Long, val to: Long, val primary: String, val secondary: String)
    private var recovery: Recovery? = null

    fun requestFullSync() {
        if (!preferences.get(BooleanKey.NsClientSimulatorBgReplay)) return
        fullSyncRequested = true
        log("Full sync queued: recover missing replay BG for the last 48 hours, from ${delayHours()} hours earlier")
    }

    fun start() { stopped = false }

    // 48 hours, or 72 once the 48-hour source ran dry (2026-11-05, per explicit request: travel across time zones
    // leaves the 48-hour source empty at unexpected times). Sticky: no return to 48 until replay is turned off,
    // and full-sync recovery uses the same shift.
    // Re-run (2026-11-05, per explicit request): another 15-minute gap while on 72 hours replays again from the first reading
    // used after the switch (the start point), so the shift grows to now minus that point. It re-arms on every later gap.
    private fun longDelay(): Boolean = preferences.get(LongNonKey.NsClientSimulatorBgReplayLongDelaySince) > 0L
    private fun delayMs(): Long = if (!longDelay()) DELAY_MS
        else preferences.get(LongNonKey.NsClientSimulatorBgReplayLongDelayMs).takeIf { it > 0L } ?: LONG_DELAY_MS
    private fun delayHours(): String = hoursText(delayMs())
    private fun hoursText(ms: Long): String =
        if (ms % (60 * 60_000L) == 0L) (ms / (60 * 60_000L)).toString() else "%.1f".format(ms / (60 * 60_000.0))

    fun stop() {
        stopped = true
        rxBus.send(EventDismissNotification(Notification.NSCLIENT_SIMULATOR_BG_REPLAY))
        lastWarning = null
    }

    private fun eligible(): Boolean = !stopped && runCatching {
        val syncs = activePlugin.getPluginsList().filter { it is NSClientPlugin || it is NSClientV3Plugin }.filter { it.isEnabled() }
        config.APS && !config.AAPSCLIENT && activePlugin.activePump is VirtualPump &&
            syncs.any { it is NSClientPlugin } && syncs.none { it is NSClientV3Plugin }
    }.getOrElse { error ->
        log("Cannot check replay eligibility (${error.javaClass.simpleName})")
        false
    }

    private fun saveRecovery() {
        val pending = recovery
        val json = if (pending == null) "" else JSONObject().put("from", pending.from).put("to", pending.to)
            .put("primary", pending.primary).put("secondary", pending.secondary).toString()
        preferences.put(StringNonKey.NsClientSimulatorBgReplayRecovery, json)
    }

    @Synchronized
    fun run() {
        if (recovery == null) {
            recovery = runCatching {
                val raw = preferences.get(StringNonKey.NsClientSimulatorBgReplayRecovery)
                if (raw.isBlank()) null else JSONObject(raw).let {
                    Recovery(it.getLong("from"), it.getLong("to"), it.getString("primary"), it.getString("secondary"))
                }
            }.getOrNull()
        }
        if (!preferences.get(BooleanKey.NsClientSimulatorBgReplay)) {
            recovery = null
            fullSyncRequested = false
            saveRecovery()
        }
        if (fullSyncRequested) {
            val now = dateUtil.now()
            recovery = Recovery(now - RECOVERY_SPAN_MS, now,
                preferences.get(StringKey.NsClientUrl).trim().trimEnd('/'),
                preferences.get(StringKey.NsClientSecondaryUrl).trim().trimEnd('/'))
            fullSyncRequested = false
            saveRecovery()
        }
        // Keep current replay moving even while a large recovery is in progress.
        if (!runWindow()) return
        val pending = recovery ?: return
        if (preferences.get(StringKey.NsClientUrl).trim().trimEnd('/') != pending.primary ||
            preferences.get(StringKey.NsClientSecondaryUrl).trim().trimEnd('/') != pending.secondary) {
            recovery = null
            saveRecovery()
            log("Replay full sync cancelled: Nightscout connection changed")
            return
        }
        val end = minOf(pending.from + RECOVERY_WINDOW_MS, pending.to)
        if (runWindow(pending.from, end)) {
            recovery = if (end < pending.to) pending.copy(from = end) else null
            saveRecovery()
            log(if (recovery == null) "Replay full sync complete" else
                "Replay full sync recovered through ${dateUtil.toISOString(end)}; continuing next tick")
        }
    }

    private fun runWindow(recoveryFrom: Long? = null, recoveryTo: Long? = null): Boolean {
        if (!preferences.get(BooleanKey.NsClientSimulatorBgReplay)) {
            preferences.put(LongNonKey.NsClientSimulatorBgReplayPauseUntil, 0L)
            preferences.put(LongNonKey.NsClientSimulatorBgReplayLongDelaySince, 0L)
            preferences.put(LongNonKey.NsClientSimulatorBgReplayStartPoint, 0L)
            preferences.put(LongNonKey.NsClientSimulatorBgReplayLongDelayMs, 0L)
            if (lastWarning != null) rxBus.send(EventDismissNotification(Notification.NSCLIENT_SIMULATOR_BG_REPLAY))
            lastWarning = null
            return false
        }
        if (!eligible()) {
            log("Not running: requires full AAPS, Virtual pump, legacy NSClient enabled and NSClientV3 disabled")
            return false
        }
        val now = dateUtil.now()
        val paused = preferences.get(LongNonKey.NsClientSimulatorBgReplayPauseUntil) > now
        warn(paused)
        if (paused) return false
        if (!preferences.get(BooleanKey.NsClientSecondaryEnabled)) {
            log("Not running: secondary Nightscout connection is disabled")
            return false
        }
        val primary = preferences.get(StringKey.NsClientUrl).trim().trimEnd('/')
        val secondary = preferences.get(StringKey.NsClientSecondaryUrl).trim().trimEnd('/')
        val secret = preferences.get(StringKey.NsClientApiSecret)
        val token = preferences.get(StringKey.NsClientSecondaryAccessToken)
        var stage = "configuration"
        try {
            require(primary.isNotBlank() && secondary.isNotBlank() && secret.isNotBlank())
            require(siteKey(primary) != siteKey(secondary)) { "Replay requires two different sites" }
            val hash = MessageDigest.getInstance("SHA-1").digest(secret.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            stage = "checking main site"
            // Read back GAP_MS (15 min): the 10-minute pause check below filters its own range.
            val recentTarget = entries(primary, now - GAP_MS, now, 1000, hash = hash)
            val target = if (recoveryFrom != null && recoveryTo != null)
                entries(primary, recoveryFrom, recoveryTo, 1000, hash = hash) else recentTarget
            check(target.size < 1000) { "Replay window exceeds query limit" }
            if (!eligible() || !preferences.get(BooleanKey.NsClientSimulatorBgReplay)) return false
            val ownUploads = runCatching {
                val rows = JSONArray(preferences.get(StringNonKey.NsClientSimulatorBgReplayUploads))
                (0 until rows.length()).map { rows.getJSONObject(it) }
                    .filter { it.optString("site") == primary && it.optLong("date") > now - PAUSE_MS }
            }.getOrDefault(emptyList())
            // Server metadata alone must not make this relay pause on its own upload.
            if (recentTarget.any { row -> row.optBoolean("isValid", true) && row.optLong("srvModified", 0L) > 0L &&
                    row.optLong("date", 0L) in (now - PAUSE_MS + 1)..now &&
                    ownUploads.none { own -> own.optLong("date") == row.optLong("date") && own.optDouble("sgv") == row.optDouble("sgv") } }) {
                preferences.put(LongNonKey.NsClientSimulatorBgReplayPauseUntil, now + PAUSE_MS)
                warn(true)
                log("Paused for 10 minutes: main site returned a recent BG with srvModified")
                return false
            }
            preferences.put(LongNonKey.NsClientSimulatorBgReplayPauseUntil, 0L)
            val delay = delayMs()
            val hours = hoursText(delay)
            val due = now - delay
            stage = "reading secondary site $hours hours ago"
            val from = recoveryFrom?.minus(delay) ?: (due - SAMPLE_WINDOW_MS + 1)
            val to = recoveryTo?.minus(delay) ?: due
            val fetched = entries(secondary, from, to, 1000, token = token)
            check(fetched.size < 1000) { "Replay window exceeds query limit" }
            val valid = fetched.filter { it.optBoolean("isValid", true) &&
                it.optDouble("sgv").let { value -> value.isFinite() && value > 0 } }
            val sources = if (recoveryFrom == null) listOfNotNull(valid.maxByOrNull { it.optLong("date", 0L) })
                else valid.distinctBy { it.optLong("date", 0L) }.sortedBy { it.optLong("date", 0L) }
            if (sources.isEmpty()) {
                val gap = recoveryFrom == null &&
                    recentTarget.none { it.optBoolean("isValid", true) && it.optLong("date", 0L) in (now - GAP_MS + 1)..now }
                // Live tick only: no 48-hour reading and no BG on the main site for 15 minutes -> switch to 72 hours.
                if (gap && !longDelay()) {
                    preferences.put(LongNonKey.NsClientSimulatorBgReplayLongDelaySince, now)
                    preferences.put(LongNonKey.NsClientSimulatorBgReplayStartPoint, 0L)
                    preferences.put(LongNonKey.NsClientSimulatorBgReplayLongDelayMs, LONG_DELAY_MS)
                    log("No secondary BG from 48 hours ago and no main-site BG for 15 minutes; switched to 72 hours until replay is turned off")
                    logger.debug(LTag.NSCLIENT, "Simulator BG replay: switched to 72-hour delay")
                    return runWindow()
                }
                // Already switched and another 15-minute gap: replay again from the start point. Only when that moves the shift,
                // so an empty start point waits for the next tick instead of looping.
                val startPoint = preferences.get(LongNonKey.NsClientSimulatorBgReplayStartPoint)
                if (gap && startPoint > 0L && now - startPoint > delay) {
                    preferences.put(LongNonKey.NsClientSimulatorBgReplayLongDelayMs, now - startPoint)
                    log("No secondary BG from $hours hours ago and no main-site BG for 15 minutes; replaying again from the start point " +
                        "${dateUtil.toISOString(startPoint)}, shift now ${hoursText(now - startPoint)} hours")
                    logger.debug(LTag.NSCLIENT, "Simulator BG replay: re-run from start point, shift ${hoursText(now - startPoint)} h")
                    return runWindow()
                }
                log("No secondary BG between ${dateUtil.toISOString(from)} and ${dateUtil.toISOString(to)}; no substitute reading generated")
                return true
            }
            var uploaded = 0
            for (source in sources) {
                val at = source.getLong("date") + delay
                if (!eligible() || !preferences.get(BooleanKey.NsClientSimulatorBgReplay) ||
                    !preferences.get(BooleanKey.NsClientSecondaryEnabled) ||
                    preferences.get(StringKey.NsClientUrl).trim().trimEnd('/') != primary ||
                    preferences.get(StringKey.NsClientSecondaryUrl).trim().trimEnd('/') != secondary ||
                    preferences.get(StringKey.NsClientApiSecret) != secret ||
                    preferences.get(StringKey.NsClientSecondaryAccessToken) != token
                ) return false
                // First reading replayed after the switch to 72 hours: the start point for a later re-run.
                if (recoveryFrom == null && longDelay() && preferences.get(LongNonKey.NsClientSimulatorBgReplayStartPoint) == 0L) {
                    preferences.put(LongNonKey.NsClientSimulatorBgReplayStartPoint, source.getLong("date"))
                    log("Start point for a later re-run: ${dateUtil.toISOString(source.getLong("date"))}")
                }
                val mgdl = source.getDouble("sgv") * if (source.optString("units").startsWith("mmol", true)) 18.0182 else 1.0
                // Existing real/main-site BG is never replaced by historical replay.
                val existing = target.firstOrNull { it.optLong("date", 0L) == at }
                if (existing != null && existing.optDouble("sgv") != mgdl) continue
                val entry = JSONObject().put("type", "sgv").put("date", at)
                    .put("dateString", dateUtil.toISOString(at)).put("sgv", mgdl).put("units", "mg/dl")
                for (key in listOf("device", "direction", "noise", "filtered", "unfiltered")) {
                    if (source.has(key) && !source.isNull(key)) entry.put(key, source.get(key))
                }
                // Save locally as well: a successful REST upload need not be echoed by the socket,
                // and the selected BG-source receive setting can otherwise discard that echo.
                stage = "saving replay BG locally"
                if (lastStoredAt != at) {
                    persistenceLayer.insertCgmSourceData(
                        Sources.NSClient,
                        mutableListOf(GV(timestamp = at, value = mgdl, raw = null, noise = null,
                            trendArrow = TrendArrow.fromString(source.optString("direction")),
                            sourceSensor = SourceSensor.UNKNOWN)),
                        emptyList(), null
                    ).blockingGet()
                    lastStoredAt = at
                    if (recoveryFrom == null) log("Secondary BG ${source.getDouble("sgv")} ${source.optString("units", "mg/dl")}: source ${dateUtil.toISOString(source.getLong("date"))}, replay ${dateUtil.toISOString(at)} (+$hours hours); saved locally")
                }
                if (target.any { it.optLong("date", 0L) == at }) {
                    if (recoveryFrom == null) log("Replay timestamp already present on main site; upload skipped")
                    continue
                }
                stage = "uploading replay BG to main site"
                // Keep identification on this installation, without altering the uploaded v1 payload.
                // Record before POST so a lost response cannot cause a false pause after server acceptance.
                val uploads = JSONArray()
                val previous = JSONArray(preferences.get(StringNonKey.NsClientSimulatorBgReplayUploads))
                for (index in 0 until previous.length()) {
                    val item = previous.getJSONObject(index)
                    if (item.optString("site") == primary && item.optLong("date") > dateUtil.now() - PAUSE_MS &&
                        item.optLong("date") != at) uploads.put(item)
                }
                uploads.put(JSONObject().put("site", primary).put("date", at).put("sgv", mgdl))
                preferences.put(StringNonKey.NsClientSimulatorBgReplayUploads, uploads.toString())
                request(primary + "/api/v1/entries.json", hash, JSONArray().put(entry).toString())
                uploaded++
                if (recoveryFrom == null) log("One replay BG uploaded to main Nightscout using v1")
                logger.debug(LTag.NSCLIENT, "Simulator BG replay: one secondary reading from $hours hours ago uploaded as v1")
            }
            if (recoveryFrom != null) log("Replay recovery window: ${sources.size} source readings, $uploaded missing readings uploaded")
            return true
        } catch (error: Exception) {
            val detail = if (error is ReplayHttpException) "HTTP ${error.status}" else error.javaClass.simpleName
            log("Failed while $stage ($detail); retry on next tick")
            logger.warn(LTag.NSCLIENT, "Simulator BG replay failed while $stage ($detail)")
            return false
        }
    }

    private fun siteKey(url: String): String {
        val uri = URI(url)
        require(uri.scheme == "https" || uri.scheme == "http")
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null)
        val port = uri.port.takeUnless { it == 80 || it == 443 } ?: -1
        return "${uri.host.lowercase()}:$port${uri.path.trimEnd('/')}"
    }

    private fun entries(base: String, from: Long, to: Long, count: Int, hash: String? = null, token: String? = null): List<JSONObject> {
        val query = linkedMapOf("find[date][\$gte]" to from.toString(), "find[date][\$lte]" to to.toString(), "count" to count.toString())
        if (!token.isNullOrBlank()) query["token"] = token
        val encoded = query.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val rows = JSONArray(request("$base/api/v1/entries/sgv.json?$encoded", hash))
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optLong("date", 0L) in from..to }
    }

    private fun request(url: String, hash: String?, body: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            hash?.let { connection.setRequestProperty("api-secret", it) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw ReplayHttpException(status)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private class ReplayHttpException(val status: Int) : Exception()

    private fun log(message: String) {
        rxBus.send(EventNSClientNewLog("● BG REPLAY", message))
    }

    private fun warn(paused: Boolean) {
        if (stopped || !preferences.get(BooleanKey.NsClientSimulatorBgReplay)) return
        val text = rh.gs(if (paused) R.string.ns_simulator_bg_replay_paused else R.string.ns_simulator_bg_replay_active)
        if (text != lastWarning) {
            uiInteraction.addNotification(Notification.NSCLIENT_SIMULATOR_BG_REPLAY, text, Notification.NORMAL)
            lastWarning = text
        }
    }

    private companion object {
        const val DELAY_MS = 48 * 60 * 60_000L
        const val LONG_DELAY_MS = 72 * 60 * 60_000L
        const val GAP_MS = 15 * 60_000L
        const val RECOVERY_SPAN_MS = 48 * 60 * 60_000L
        const val SAMPLE_WINDOW_MS = 5 * 60_000L
        const val PAUSE_MS = 10 * 60_000L
        const val RECOVERY_WINDOW_MS = 6 * 60 * 60_000L
    }
}
