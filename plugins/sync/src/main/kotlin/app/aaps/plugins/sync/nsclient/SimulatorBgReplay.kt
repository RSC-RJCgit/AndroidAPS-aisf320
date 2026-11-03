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
import app.aaps.core.interfaces.plugin.PluginBase
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

    fun start() { stopped = false }

    fun stop() {
        stopped = true
        rxBus.send(EventDismissNotification(Notification.NSCLIENT_SIMULATOR_BG_REPLAY))
        lastWarning = null
    }

    private fun eligible(): Boolean = !stopped && runCatching {
        val syncs = activePlugin.activeSyncs.mapNotNull { it as? PluginBase }.filter { it.isEnabled() }
        config.APS && !config.AAPSCLIENT && activePlugin.activePump is VirtualPump &&
            syncs.any { it is NSClientPlugin } && syncs.none { it is NSClientV3Plugin }
    }.getOrDefault(false)

    @Synchronized
    fun run() {
        if (!preferences.get(BooleanKey.NsClientSimulatorBgReplay)) {
            preferences.put(LongNonKey.NsClientSimulatorBgReplayPauseUntil, 0L)
            if (lastWarning != null) rxBus.send(EventDismissNotification(Notification.NSCLIENT_SIMULATOR_BG_REPLAY))
            lastWarning = null
            return
        }
        if (!eligible()) {
            log("Not running: requires full AAPS, Virtual pump, legacy NSClient enabled and NSClientV3 disabled")
            return
        }
        val now = dateUtil.now()
        val paused = preferences.get(LongNonKey.NsClientSimulatorBgReplayPauseUntil) > now
        warn(paused)
        if (paused) return
        if (!preferences.get(BooleanKey.NsClientSecondaryEnabled)) {
            log("Not running: secondary Nightscout connection is disabled")
            return
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
            val target = entries(primary, now - PAUSE_MS, now, 1000, hash = hash)
            if (!eligible() || !preferences.get(BooleanKey.NsClientSimulatorBgReplay)) return
            val ownUploads = runCatching {
                val rows = JSONArray(preferences.get(StringNonKey.NsClientSimulatorBgReplayUploads))
                (0 until rows.length()).map { rows.getJSONObject(it) }
                    .filter { it.optString("site") == primary && it.optLong("date") > now - PAUSE_MS }
            }.getOrDefault(emptyList())
            // Server metadata alone must not make this relay pause on its own upload.
            if (target.any { row -> row.optBoolean("isValid", true) && row.optLong("srvModified", 0L) > 0L &&
                    row.optLong("date", 0L) in (now - PAUSE_MS + 1)..now &&
                    ownUploads.none { own -> own.optLong("date") == row.optLong("date") && own.optDouble("sgv") == row.optDouble("sgv") } }) {
                preferences.put(LongNonKey.NsClientSimulatorBgReplayPauseUntil, now + PAUSE_MS)
                warn(true)
                log("Paused for 10 minutes: main site returned a recent BG with srvModified")
                return
            }
            preferences.put(LongNonKey.NsClientSimulatorBgReplayPauseUntil, 0L)
            val due = now - DELAY_MS
            stage = "reading secondary site 48 hours ago"
            val source = entries(secondary, due - SAMPLE_WINDOW_MS + 1, due, 1000, token = token)
                .filter { it.optBoolean("isValid", true) && it.optDouble("sgv").let { value -> value.isFinite() && value > 0 } }
                .maxByOrNull { it.optLong("date", 0L) } ?: run {
                    log("No secondary BG between ${dateUtil.toISOString(due - SAMPLE_WINDOW_MS + 1)} and ${dateUtil.toISOString(due)}; no substitute reading generated")
                    return
                }
            val at = source.getLong("date") + DELAY_MS
            if (!eligible() || !preferences.get(BooleanKey.NsClientSimulatorBgReplay) ||
                !preferences.get(BooleanKey.NsClientSecondaryEnabled) ||
                preferences.get(StringKey.NsClientUrl).trim().trimEnd('/') != primary ||
                preferences.get(StringKey.NsClientSecondaryUrl).trim().trimEnd('/') != secondary ||
                preferences.get(StringKey.NsClientApiSecret) != secret ||
                preferences.get(StringKey.NsClientSecondaryAccessToken) != token
            ) return
            val mgdl = source.getDouble("sgv") * if (source.optString("units").startsWith("mmol", true)) 18.0182 else 1.0
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
                log("Secondary BG ${source.getDouble("sgv")} ${source.optString("units", "mg/dl")}: source ${dateUtil.toISOString(source.getLong("date"))}, replay ${dateUtil.toISOString(at)} (+48 hours); saved locally")
            }
            if (target.any { it.optLong("date", 0L) == at }) {
                log("Replay timestamp already present on main site; upload skipped")
                return
            }
            stage = "uploading replay BG to main site"
            // Keep identification on this installation, without altering the uploaded v1 payload.
            // Record before POST so a lost response cannot cause a false pause after server acceptance.
            val uploads = JSONArray()
            ownUploads.filter { it.optLong("date") != at }.forEach { uploads.put(it) }
            uploads.put(JSONObject().put("site", primary).put("date", at).put("sgv", mgdl))
            preferences.put(StringNonKey.NsClientSimulatorBgReplayUploads, uploads.toString())
            request(primary + "/api/v1/entries.json", hash, JSONArray().put(entry).toString())
            log("One replay BG uploaded to main Nightscout using v1")
            logger.debug(LTag.NSCLIENT, "Simulator BG replay: one secondary reading from 48 hours ago uploaded as v1")
        } catch (error: Exception) {
            val detail = if (error is ReplayHttpException) "HTTP ${error.status}" else error.javaClass.simpleName
            log("Failed while $stage ($detail); retry on next tick")
            logger.warn(LTag.NSCLIENT, "Simulator BG replay failed while $stage ($detail)")
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
        const val SAMPLE_WINDOW_MS = 5 * 60_000L
        const val PAUSE_MS = 10 * 60_000L
    }
}
