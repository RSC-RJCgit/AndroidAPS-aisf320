package app.aaps.plugins.sync.nsclient

import app.aaps.core.data.time.AppTimeZone

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
import app.aaps.plugins.sync.nsShared.NsIncomingDataProcessor
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone
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
    private val persistenceLayer: PersistenceLayer,
    private val nsIncomingDataProcessor: NsIncomingDataProcessor
) {
    @Volatile private var stopped = true
    private var lastWarning: String? = null
    private var lastStoredAt = 0L
    /** The newest current secondary reading relayed (0 = none since start); the replay waits GAP_MS after it */
    private var lastCurrentAt = 0L
    /** The calibrated reading saved at [lastStoredAt], reused if its upload is retried, so it is calibrated only once */
    private var lastStoredGv: GV = GV(timestamp = 0L, value = 0.0, raw = null, noise = null, trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.UNKNOWN)
    @Volatile private var fullSyncRequested = false
    private data class Recovery(val from: Long, val to: Long, val primary: String, val secondary: String)
    private var recovery: Recovery? = null
    /** Times of BG readings this replay saved locally in the last PAUSE_MS, so they are not taken for real CGM BG */
    private val storedDates = mutableListOf<Long>()

    fun requestFullSync() {
        if (!preferences.get(BooleanKey.NsClientSimulatorBgReplay)) return
        fullSyncRequested = true
        log("Full sync queued: recover missing replay BG for the last 48 hours, from ${delayHours()} hours earlier")
    }

    fun start() { stopped = false }

    // 14 days, or 15 once the 14-day source ran dry (2026-11-07, per explicit request; was 48 h / 72 h since 3 and 5 Nov:
    // travel across time zones leaves the source empty at unexpected times). Sticky: no return to 14 days until replay is
    // turned off, and full-sync recovery uses the same shift.
    // Gap while on the long delay (2026-11-06, per explicit request; replaces the 5 Nov jump back to a fixed start point, which
    // replayed the same 1 h 25 min of 2 Nov on a loop): skip forward to the next secondary reading within 6 hours, never nearer
    // than 14 days ago, so the shift shrinks and no reading is replayed twice. None there: one day earlier (shift + 24 h),
    // and past 21 days it starts again at 15.
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
        // Local CGM BG first (2026-11-09, per explicit request): the main-site check below only sees v3 uploads, so BG
        // from xDrip on this phone never stopped the replay, and both were saved and uploaded (two BG lines). While replay
        // is on, the normal v1 BG upload is blocked (DataSyncSelectorV1.replayBgOnly), so the local reading is uploaded
        // here instead of the secondary one, and nothing is relayed or replayed.
        val local = if (paused) emptyList() else realBgReadings(
            persistenceLayer.getBgReadingsDataFromTimeToTime(now - PAUSE_MS, now + 60_000L, false), replayDates(now)
        )
        warn(
            when {
                paused            -> R.string.ns_simulator_bg_replay_paused
                local.isNotEmpty() -> R.string.ns_simulator_bg_replay_local
                else              -> R.string.ns_simulator_bg_replay_active
            }
        )
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
                warn(R.string.ns_simulator_bg_replay_paused)
                log("Paused for 10 minutes: main site returned a recent BG with srvModified")
                return false
            }
            preferences.put(LongNonKey.NsClientSimulatorBgReplayPauseUntil, 0L)
            if (local.isNotEmpty()) {
                // No recovery either while local BG arrives: it would add replay readings between the local ones.
                if (recoveryFrom != null) return false
                stage = "uploading local CGM BG to main site"
                val send = localToUpload(local, recentTarget.map { it.optLong("date", 0L) }.toSet())
                for (gv in send) {
                    val mgdl = Math.round(gv.value).toDouble()
                    val entry = JSONObject().put("type", "sgv").put("date", gv.timestamp)
                        .put("dateString", dateUtil.toISOString(gv.timestamp)).put("sgv", mgdl).put("units", "mg/dl")
                        .put("device", "AAPS")
                    if (gv.trendArrow != TrendArrow.NONE) entry.put("direction", gv.trendArrow.text)
                    rememberUpload(primary, gv.timestamp, mgdl, local = true)
                    request(primary + "/api/v1/entries.json", hash, JSONArray().put(entry).toString())
                }
                val newest = local.last()
                log("Local CGM BG ${"%.0f".format(newest.value)} mg/dl at ${dateUtil.toISOString(newest.timestamp)}: " +
                    if (send.isEmpty()) "already on main site; no relay or replay" else "${send.size} uploaded to main site in place of the secondary BG; no relay or replay")
                return false
            }
            val delay = delayMs()
            val hours = hoursText(delay)
            val zone = sourceZone()
            // Current BG first (2026-11-07, per explicit request): the replay is only a stand-in. While the secondary site
            // has a reading from the last CURRENT_MS, that reading is relayed at its own time (calibrated, saved locally,
            // uploaded, no shift) and nothing is replayed. The 14-day replay takes over only after GAP_MS without one.
            val current = if (recoveryFrom != null) null else {
                stage = "reading current BG on the secondary site"
                entries(secondary, now - CURRENT_MS, now + 60_000L, 100, token = token)
                    .filter { it.optBoolean("isValid", true) && it.optDouble("sgv").let { value -> value.isFinite() && value > 0 } }
                    .maxByOrNull { it.optLong("date", 0L) }
            }
            val relayCurrent = current != null
            if (current != null) lastCurrentAt = current.getLong("date")
            else if (recoveryFrom == null && now - lastCurrentAt < GAP_MS) {
                log("No current secondary BG since ${dateUtil.toISOString(lastCurrentAt)}; the replay starts after 15 minutes without one")
                return true
            }
            val sources: List<Pair<JSONObject, Long>>
            if (current != null) sources = listOf(current to current.getLong("date"))
            else {
                // The source time that replays now (time-zone match, see replayAt()). The window around it is widened by
                // TZ_MARGIN_MS, because a reading's own UTC offset can differ from the setting's; each reading is then placed
                // by its own offset and kept only if it lands in this tick's window.
                val due = sourceTimeFor(now, delay, zone)
                stage = "reading secondary site $hours hours ago"
                val from = (recoveryFrom?.let { sourceTimeFor(it, delay, zone) } ?: (due - SAMPLE_WINDOW_MS + 1)) - TZ_MARGIN_MS
                val to = (recoveryTo?.let { sourceTimeFor(it, delay, zone) } ?: due) + TZ_MARGIN_MS
                val fetched = entries(secondary, from, to, SOURCE_QUERY_LIMIT, token = token)
                check(fetched.size < SOURCE_QUERY_LIMIT) { "Replay window exceeds query limit" }
                val valid = fetched.filter { it.optBoolean("isValid", true) &&
                    it.optDouble("sgv").let { value -> value.isFinite() && value > 0 } }
                val windowFrom = recoveryFrom ?: (now - SAMPLE_WINDOW_MS + 1)
                val windowTo = recoveryTo ?: now
                val placed = valid.distinctBy { it.optLong("date", 0L) }.map { it to replayAt(it, delay, zone) }
                    .filter { it.second in windowFrom..windowTo }
                sources = if (recoveryFrom == null) listOfNotNull(placed.maxByOrNull { it.second }) else placed.sortedBy { it.second }
                if (sources.isEmpty()) {
                    val gap = recoveryFrom == null &&
                        recentTarget.none { it.optBoolean("isValid", true) && it.optLong("date", 0L) in (now - GAP_MS + 1)..now }
                    // Live tick only: no 14-day reading and no BG on the main site for 15 minutes -> switch to 15 days.
                    if (gap && !longDelay()) {
                        preferences.put(LongNonKey.NsClientSimulatorBgReplayLongDelaySince, now)
                        preferences.put(LongNonKey.NsClientSimulatorBgReplayLongDelayMs, LONG_DELAY_MS)
                        log("No secondary BG from ${hoursText(DELAY_MS)} hours ago and no main-site BG for 15 minutes; " +
                            "switched to ${hoursText(LONG_DELAY_MS)} hours until replay is turned off")
                        logger.debug(LTag.NSCLIENT, "Simulator BG replay: switched to ${hoursText(LONG_DELAY_MS)}-hour delay")
                        return runWindow()
                    }
                    // Already switched and another 15-minute gap: skip past it to the next secondary reading (see delayMs()).
                    if (gap && longDelay()) {
                        stage = "looking past a gap in the secondary site"
                        val aheadTo = minOf(due + SKIP_AHEAD_MS, now - DELAY_MS)
                        val next = if (aheadTo <= due) null else entries(secondary, due + 1, aheadTo, 1000, token = token)
                            .filter { it.optBoolean("isValid", true) && it.optDouble("sgv").let { value -> value.isFinite() && value > 0 } }
                            .minByOrNull { it.optLong("date", 0L) }
                        if (next != null) {
                            val nextAt = next.getLong("date")
                            // Local clock to local clock, so that reading replays now (see replayAt())
                            val shift = now + here().getOffset(now) - (nextAt + sourceOffsetMs(next, zone))
                            preferences.put(LongNonKey.NsClientSimulatorBgReplayLongDelayMs, shift)
                            log("Gap in the secondary BG after ${dateUtil.toISOString(due)}: skipped ${hoursText(nextAt - due)} hours to " +
                                "${dateUtil.toISOString(nextAt)}, shift now ${hoursText(shift)} hours")
                            logger.debug(LTag.NSCLIENT, "Simulator BG replay: skipped a gap, shift ${hoursText(shift)} h")
                            return runWindow()
                        }
                        // Nothing in reach: one day earlier, tried on the next tick (no immediate retry, so this cannot spin). A long
                        // outage on the secondary site would otherwise add a day every tick, so past MAX_DELAY_MS it starts again at 15 days.
                        val earlier = if (delay + DAY_MS > MAX_DELAY_MS) LONG_DELAY_MS else delay + DAY_MS
                        preferences.put(LongNonKey.NsClientSimulatorBgReplayLongDelayMs, earlier)
                        log("No secondary BG in the ${hoursText(maxOf(0L, aheadTo - due))} hours after ${dateUtil.toISOString(due)}; " +
                            "shift now ${hoursText(earlier)} hours" +
                            if (earlier == LONG_DELAY_MS) " (over ${MAX_DELAY_MS / DAY_MS} days back, starting again at ${LONG_DELAY_MS / DAY_MS} days)" else " (one day earlier)")
                        logger.debug(LTag.NSCLIENT, "Simulator BG replay: no reading ahead, shift ${hoursText(earlier)} h")
                        return true
                    }
                    log("No secondary BG between ${dateUtil.toISOString(from)} and ${dateUtil.toISOString(to)}; no substitute reading generated")
                    return true
                }
            }
            var uploaded = 0
            for ((source, at) in sources) {
                if (!eligible() || !preferences.get(BooleanKey.NsClientSimulatorBgReplay) ||
                    !preferences.get(BooleanKey.NsClientSecondaryEnabled) ||
                    preferences.get(StringKey.NsClientUrl).trim().trimEnd('/') != primary ||
                    preferences.get(StringKey.NsClientSecondaryUrl).trim().trimEnd('/') != secondary ||
                    preferences.get(StringKey.NsClientApiSecret) != secret ||
                    preferences.get(StringKey.NsClientSecondaryAccessToken) != token
                ) return false
                val sourceMgdl = source.getDouble("sgv") * if (source.optString("units").startsWith("mmol", true)) 18.0182 else 1.0
                // The BG result, not the raw source value (2026-11-07, per explicit request): the reading goes through the
                // same Libre slope/offset and smoothing as BG received through Nightscout, once per reading. That result is
                // saved locally (what the loop uses) and uploaded to the main site. Backfill gets the slope and offset only,
                // so older readings never disturb the live smoothing state.
                val gv = if (lastStoredAt == at) lastStoredGv else GV(timestamp = at, value = sourceMgdl, raw = null, noise = null,
                    trendArrow = TrendArrow.fromString(source.optString("direction")), sourceSensor = SourceSensor.UNKNOWN)
                        .also { nsIncomingDataProcessor.applyFslCalibration(mutableListOf(it), smoothing = recoveryFrom == null) }
                val mgdl = Math.round(gv.value).toDouble()
                // Existing real/main-site BG is never replaced by historical replay.
                val existing = target.firstOrNull { it.optLong("date", 0L) == at }
                if (existing != null && existing.optDouble("sgv") != mgdl) continue
                val entry = JSONObject().put("type", "sgv").put("date", at)
                    .put("dateString", dateUtil.toISOString(at)).put("sgv", mgdl).put("units", "mg/dl")
                // Device and arrow only: the source's noise/filtered/unfiltered are its raw channels, not this result.
                for (key in listOf("device", "direction")) {
                    if (source.has(key) && !source.isNull(key)) entry.put(key, source.get(key))
                }
                // Save locally as well: a successful REST upload need not be echoed by the socket,
                // and the selected BG-source receive setting can otherwise discard that echo.
                stage = "saving replay BG locally"
                if (lastStoredAt != at) {
                    persistenceLayer.insertCgmSourceData(Sources.NSClient, mutableListOf(gv), emptyList(), null).blockingGet()
                    lastStoredAt = at
                    lastStoredGv = gv
                    storedDates += at
                    if (relayCurrent) log("Current secondary BG ${source.getDouble("sgv")} ${source.optString("units", "mg/dl")} at ${dateUtil.toISOString(at)} (relayed, no replay); result ${"%.0f".format(mgdl)} mg/dl saved locally")
                    else if (recoveryFrom == null) log("Secondary BG ${source.getDouble("sgv")} ${source.optString("units", "mg/dl")}: source ${dateUtil.toISOString(source.getLong("date"))} (UTC${offsetText(sourceOffsetMs(source, zone))}), replay ${dateUtil.toISOString(at)} (UTC${offsetText(here().getOffset(at).toLong())}, same local time +$hours hours); result ${"%.0f".format(mgdl)} mg/dl saved locally")
                }
                if (target.any { it.optLong("date", 0L) == at }) {
                    if (recoveryFrom == null) log("Replay timestamp already present on main site; upload skipped")
                    continue
                }
                stage = "uploading replay BG to main site"
                // Keep identification on this installation, without altering the uploaded v1 payload.
                // Record before POST so a lost response cannot cause a false pause after server acceptance.
                rememberUpload(primary, at, mgdl, local = false)
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

    // Time-zone match (2026-11-07, per explicit request): a reading replays at the same LOCAL clock time, the delay later, in
    // this phone's current zone, not exactly the delay later in UTC. Its original zone is its own UTC offset (utcOffset, or an
    // explicit offset in dateString/sysTime), else the "Replay source time zone" setting at that moment, with its daylight saving.
    private fun here(): TimeZone = AppTimeZone.timeZone()
    private fun sourceZone(): TimeZone = TimeZone.getTimeZone(preferences.get(StringKey.NsClientSimulatorBgReplaySourceTimeZone).trim())

    private fun sourceOffsetMs(source: JSONObject, zone: TimeZone): Long {
        if (source.has("utcOffset") && !source.isNull("utcOffset")) return source.optLong("utcOffset") * 60_000L
        for (key in listOf("dateString", "sysTime")) {
            OFFSET_IN_DATE.find(source.optString(key))?.let { match ->
                val minutes = match.groupValues[2].toLong() * 60 + match.groupValues[3].toLong()
                return (if (match.groupValues[1] == "-") -minutes else minutes) * 60_000L
            }
        }
        return zone.getOffset(source.optLong("date", 0L)).toLong()
    }

    /** When [source] replays: its local clock time plus [delay], as a moment in this phone's current zone */
    private fun replayAt(source: JSONObject, delay: Long, zone: TimeZone): Long =
        fromLocal(source.getLong("date") + sourceOffsetMs(source, zone) + delay, here())

    /** The source moment, in [zone], whose local clock time plus [delay] is [at]'s local clock time here */
    private fun sourceTimeFor(at: Long, delay: Long, zone: TimeZone): Long = fromLocal(at + here().getOffset(at) - delay, zone)

    /** A local clock time (epoch millis as if UTC) to the real moment in [zone] */
    private fun fromLocal(local: Long, zone: TimeZone): Long = local - zone.getOffset(local - zone.getOffset(local)).toLong()

    private fun offsetText(ms: Long): String {
        val minutes = kotlin.math.abs(ms) / 60_000L
        return "%s%02d:%02d".format(if (ms < 0) "-" else "+", minutes / 60, minutes % 60)
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

    /** Times of the BG this replay made in the last PAUSE_MS: saved here, or uploaded (and maybe received back from the main site).
     *  Local CGM readings it uploaded are not included: they stay real BG. */
    private fun replayDates(now: Long): Set<Long> {
        storedDates.removeAll { it <= now - PAUSE_MS }
        val uploaded = runCatching {
            val rows = JSONArray(preferences.get(StringNonKey.NsClientSimulatorBgReplayUploads))
            (0 until rows.length()).map { rows.getJSONObject(it) }.filter { !it.optBoolean("local", false) }.map { it.optLong("date") }
        }.getOrDefault(emptyList())
        return (storedDates + uploaded).toSet()
    }

    /** Records an upload before it is sent, so the srvModified check does not pause on it and it is not taken for real BG */
    private fun rememberUpload(primary: String, at: Long, mgdl: Double, local: Boolean) {
        val uploads = JSONArray()
        val previous = JSONArray(preferences.get(StringNonKey.NsClientSimulatorBgReplayUploads))
        for (index in 0 until previous.length()) {
            val item = previous.getJSONObject(index)
            if (item.optString("site") == primary && item.optLong("date") > dateUtil.now() - PAUSE_MS &&
                item.optLong("date") != at) uploads.put(item)
        }
        uploads.put(JSONObject().put("site", primary).put("date", at).put("sgv", mgdl).put("local", local))
        preferences.put(StringNonKey.NsClientSimulatorBgReplayUploads, uploads.toString())
    }

    private fun warn(textId: Int) {
        if (stopped || !preferences.get(BooleanKey.NsClientSimulatorBgReplay)) return
        val text = rh.gs(textId)
        if (text != lastWarning) {
            uiInteraction.addNotification(Notification.NSCLIENT_SIMULATOR_BG_REPLAY, text, Notification.NORMAL)
            lastWarning = text
        }
    }

    internal companion object {
        /** Valid readings that this replay did not make, i.e. real CGM BG (xDrip or another uploader), oldest first */
        fun realBgReadings(readings: List<GV>, replayDates: Set<Long>): List<GV> =
            readings.filter { it.isValid && it.timestamp !in replayDates }.sortedBy { it.timestamp }

        /** Local readings the main site does not have yet. An entry with another value is not replaced: with Libre
         *  calibration on, the site's echo is calibrated again on receipt, and a replace would then repeat every minute. */
        fun localToUpload(local: List<GV>, onSiteDates: Set<Long>): List<GV> =
            local.filter { it.timestamp !in onSiteDates }

        const val DAY_MS = 24 * 60 * 60_000L
        // 2026-11-07, per explicit request: 14 days back (was 48 h), 15 days once that source ran dry (was 72 h), at most 21 (was 7 days).
        const val DELAY_MS = 14 * DAY_MS
        const val LONG_DELAY_MS = 15 * DAY_MS
        const val GAP_MS = 15 * 60_000L
        const val RECOVERY_SPAN_MS = 48 * 60 * 60_000L
        const val SAMPLE_WINDOW_MS = 5 * 60_000L
        const val PAUSE_MS = 10 * 60_000L
        const val RECOVERY_WINDOW_MS = 6 * 60 * 60_000L
        const val SKIP_AHEAD_MS = 6 * 60 * 60_000L
        const val MAX_DELAY_MS = 21 * DAY_MS
        /** Extra source time read either side, for readings whose own UTC offset differs from the setting's */
        const val TZ_MARGIN_MS = 13 * 60 * 60_000L
        /** Up to 26 hours of readings (TZ_MARGIN_MS each side, about one a minute) in one read of the secondary site */
        const val SOURCE_QUERY_LIMIT = 3000
        /** A secondary reading this recent counts as current: it is relayed instead of replaying */
        const val CURRENT_MS = 10 * 60_000L
        /** "+10:30", "-05:00" or "+0100" at the end of an ISO date, not "Z" */
        private val OFFSET_IN_DATE = Regex("T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?([+-])(\\d{2}):?(\\d{2})$")
    }
}
