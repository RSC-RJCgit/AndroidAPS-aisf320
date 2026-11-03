package app.aaps.plugins.sync.nsclient

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
    private val rh: ResourceHelper
) {
    @Volatile private var stopped = true
    private var lastWarning: String? = null

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
        if (!eligible()) return
        val now = dateUtil.now()
        val paused = preferences.get(LongNonKey.NsClientSimulatorBgReplayPauseUntil) > now
        warn(paused)
        if (paused || !preferences.get(BooleanKey.NsClientSecondaryEnabled)) return
        val primary = preferences.get(StringKey.NsClientUrl).trim().trimEnd('/')
        val secondary = preferences.get(StringKey.NsClientSecondaryUrl).trim().trimEnd('/')
        val secret = preferences.get(StringKey.NsClientApiSecret)
        val token = preferences.get(StringKey.NsClientSecondaryAccessToken)
        try {
            require(primary.isNotBlank() && secondary.isNotBlank() && secret.isNotBlank())
            require(siteKey(primary) != siteKey(secondary)) { "Replay requires two different sites" }
            val hash = MessageDigest.getInstance("SHA-1").digest(secret.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val target = entries(primary, now - PAUSE_MS, now, 1000, hash = hash)
            if (!eligible() || !preferences.get(BooleanKey.NsClientSimulatorBgReplay)) return
            if (target.any { it.optBoolean("isValid", true) && it.optLong("srvModified", 0L) > 0L &&
                    it.optLong("date", 0L) in (now - PAUSE_MS + 1)..now }) {
                preferences.put(LongNonKey.NsClientSimulatorBgReplayPauseUntil, now + PAUSE_MS)
                warn(true)
                return
            }
            preferences.put(LongNonKey.NsClientSimulatorBgReplayPauseUntil, 0L)
            val due = now - DELAY_MS
            val source = entries(secondary, due - SAMPLE_WINDOW_MS + 1, due, 1000, token = token)
                .filter { it.optBoolean("isValid", true) && it.optDouble("sgv").let { value -> value.isFinite() && value > 0 } }
                .maxByOrNull { it.optLong("date", 0L) } ?: return
            val at = source.getLong("date") + DELAY_MS
            if (target.any { it.optLong("date", 0L) == at }) return
            if (!eligible() || !preferences.get(BooleanKey.NsClientSimulatorBgReplay) ||
                !preferences.get(BooleanKey.NsClientSecondaryEnabled) ||
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
            request(primary + "/api/v1/entries.json", hash, JSONArray().put(entry).toString())
            logger.debug(LTag.NSCLIENT, "Simulator BG replay: one secondary reading from 48 hours ago uploaded as v1")
        } catch (error: Exception) {
            logger.warn(LTag.NSCLIENT, "Simulator BG replay failed (${error.javaClass.simpleName}); will retry on the next tick")
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
            check(connection.responseCode in 200..299) { "Nightscout request failed (${connection.responseCode})" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
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
