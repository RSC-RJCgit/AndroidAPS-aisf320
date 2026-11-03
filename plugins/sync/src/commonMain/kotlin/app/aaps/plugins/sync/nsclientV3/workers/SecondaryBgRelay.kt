package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.interfaces.NSAndroidClient
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.plugins.sync.SyncStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val BG_REPLAY_DELAY_MS = 48 * 60 * 60_000L
internal const val BG_REPLAY_SAMPLE_WINDOW_MS = 5 * 60_000L
internal const val BG_RELAY_PAUSE_MS = 10 * 60_000L

/** A fresh v3 reading pauses the replay; v1 replay uploads carry no srvModified. */
internal fun normalBgResumed(readings: List<NSSgvV3>, now: Long): Boolean = readings.any {
    val at = it.date
    at != null && at in (now - BG_RELAY_PAUSE_MS + 1)..now && it.isValid && it.srvModified != null
}

/** One newest due source reading, shifted exactly 48 hours. Never catch up a missed batch. */
internal fun nextReplayReading(source: List<NSSgvV3>, target: List<NSSgvV3>, now: Long): NSSgvV3? {
    val due = now - BG_REPLAY_DELAY_MS
    val reading = source.filter {
        val at = it.date
        at != null && at in (due - BG_REPLAY_SAMPLE_WINDOW_MS + 1)..due &&
            it.isValid && it.sgv.isFinite() && it.sgv > 0
    }.maxByOrNull { it.date ?: 0L } ?: return null
    val replayAt = requireNotNull(reading.date) + BG_REPLAY_DELAY_MS
    if (target.any { it.date == replayAt }) return null
    return reading.copy(date = replayAt, identifier = null, srvModified = null, srvCreated = null,
        subject = null, isReadOnly = false)
}

/** Simulator-only replay of secondary BG from 48 hours ago. No dosing database is written. */
@SingleIn(AppScope::class)
@Inject
class SecondaryBgRelay(
    private val config: Config,
    private val preferences: Preferences,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val repository: NSClientRepository,
    private val notifications: NotificationManager,
    private val rh: TextResolver
) {
    private val mutex = Mutex()
    private var lastWarning: String? = null

    /** Replay is permitted only with legacy NSClient selected, never with NSClientV3. */
    fun legacyMainSelected(): Boolean {
        if (!config.appInitialized) return false
        val selected = activePlugin.activeSyncs.mapNotNull { it as? PluginBase }.filter { it.isEnabled() }
        return selected.any { it.pluginId == "NSClientPlugin" } &&
            selected.none { it.pluginId == "NSClientV3Plugin" }
    }

    fun clearIfDisabled() {
        if (preferences.get(BooleanKey.NsClientTemporaryBgRelay) && legacyMainSelected()) return
        preferences.put(LongNonKey.NsClientBgRelayPauseUntil, 0L)
        notifications.dismiss(NotificationId.NSCLIENT_TEMPORARY_BG_RELAY)
        lastWarning = null
    }

    suspend fun run(primary: NSAndroidClient?) = mutex.withLock {
        if (!preferences.get(BooleanKey.NsClientTemporaryBgRelay) || !legacyMainSelected()) {
            clearIfDisabled()
            return@withLock
        }
        if (!config.appInitialized || !config.APS || config.AAPSCLIENT ||
            activePlugin.activePump.selectedActivePump() !is VirtualPump
        ) return@withLock
        warn(paused = preferences.get(LongNonKey.NsClientBgRelayPauseUntil) > dateUtil.now())
        if (!preferences.get(BooleanKey.NsClientSecondaryEnabled) || primary == null) return@withLock
        val primaryUrl = preferences.get(StringKey.NsClientUrl).trim()
        val secondaryUrl = preferences.get(StringKey.NsClientSecondaryUrl).trim()
        if (primaryUrl.isBlank() || secondaryUrl.isBlank() || sameNsSite(primaryUrl, secondaryUrl)) {
            repository.addLog("● BG RELAY", "Two different Nightscout sites are required")
            return@withLock
        }
        val now = dateUtil.now()
        if (preferences.get(LongNonKey.NsClientBgRelayPauseUntil) > now) {
            warn(paused = true)
            return@withLock
        }
        val secondary = NSAndroidClientImpl(
            baseUrl = secondaryUrl.lowercase().replace("https://", "").trimEnd('/'),
            accessToken = preferences.get(StringKey.NsClientSecondaryAccessToken).trim(),
            logging = false, logger = { aapsLogger.debug(LTag.HTTP, "BG relay: $it") }
        )
        try {
            val target = readWindow(primary, now - BG_RELAY_PAUSE_MS, now)
            if (!stillEnabled(primaryUrl, secondaryUrl)) return@withLock
            if (normalBgResumed(target, now)) {
                preferences.put(LongNonKey.NsClientBgRelayPauseUntil, now + BG_RELAY_PAUSE_MS)
                warn(paused = true)
                repository.addLog("● BG RELAY", "Normal BG resumed; paused for 10 minutes")
                return@withLock
            }
            preferences.put(LongNonKey.NsClientBgRelayPauseUntil, 0L)
            val due = now - BG_REPLAY_DELAY_MS
            val source = readWindow(secondary, due - BG_REPLAY_SAMPLE_WINDOW_MS, due)
            val reading = nextReplayReading(source, target, now) ?: return@withLock
            if (!stillEnabled(primaryUrl, secondaryUrl)) return@withLock
            val response = primary.createSgvV1(reading)
            check(response.response in 200..299) { "BG replay upload failed (${response.response})" }
            repository.addLog("● BG RELAY", "One secondary reading from 48 hours ago replayed through v1")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Temporary BG relay failed", error)
            repository.addLog("● BG RELAY ERR", "Replay failed; will retry without changing existing BG")
        } finally {
            secondary.close()
        }
    }

    private fun stillEnabled(primary: String, secondary: String): Boolean =
        preferences.get(BooleanKey.NsClientTemporaryBgRelay) &&
            legacyMainSelected() &&
            preferences.get(BooleanKey.NsClientSecondaryEnabled) &&
            preferences.get(StringKey.NsClientUrl).trim() == primary &&
            preferences.get(StringKey.NsClientSecondaryUrl).trim() == secondary

    private suspend fun readWindow(client: NSAndroidClient, start: Long, end: Long): List<NSSgvV3> {
        var from = start
        val readings = mutableListOf<NSSgvV3>()
        repeat(64) {
            val response = client.getSgvsNewerThan(from, 500)
            check(response.code in 200..299) { "BG history read failed (${response.code})" }
            val page = response.values
            readings += page.filter { (it.date ?: Long.MAX_VALUE) <= end }
            if (page.size < 500 || page.any { (it.date ?: 0L) >= end }) return readings
            val next = page.mapNotNull { it.date }.maxOrNull() ?: error("Missing BG page time")
            check(next > from) { "BG page did not advance" }
            from = next
        }
        error("BG window exceeded page limit")
    }

    private fun warn(paused: Boolean) {
        if (!preferences.get(BooleanKey.NsClientTemporaryBgRelay)) return
        val text = rh.gs(if (paused) SyncStrings.ns_bg_relay_paused else SyncStrings.ns_bg_relay_active)
        if (text == lastWarning) return
        notifications.post(NotificationId.NSCLIENT_TEMPORARY_BG_RELAY, text)
        lastWarning = text
    }
}
