package app.aaps.plugins.sync.nsclientV3.data

import app.aaps.core.data.model.LiveSteps
import app.aaps.core.data.model.SC
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.localmodel.devicestatus.NSDeviceStatus
import app.aaps.core.utils.safeGetString
import app.aaps.core.utils.safeGetStringAllowNull
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import app.aaps.plugins.sync.nsclientV3.workers.stepsFromPrimarySite
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/*
{
    "_id": "594fdcec327b83c81b6b8c0f",
    "device": "openaps://Sony D5803",
    "pump": {
        "battery": {
            "percent": 100
        },
        "status": {
            "status": "normal",
            "timestamp": "2017-06-25T15:50:14Z"
        },
        "extended": {
            "Version": "1.5-ac98852-2017.06.25",
            "PumpIOB": 1.13,
            "LastBolus": "25. 6. 2017 17:25:00",
            "LastBolusAmount": 0.3,
            "BaseBasalRate": 0.4,
            "ActiveProfile": "2016 +30%"
        },
        "reservoir": 109,
        "clock": "2017-06-25T15:55:10Z"
    },
    "openaps": {
        "suggested": {
            "temp": "absolute",
            "bg": 115.9,
            "tick": "+5",
            "eventualBG": 105,
            "snoozeBG": 105,
            "predBGs": {
                "IOB": [116, 114, 112, 110, 109, 107, 106, 105, 105, 104, 104, 104, 104, 104, 104, 104, 104, 105, 105, 105, 105, 105, 106, 106, 106, 106, 106, 107]
            },
            "sensitivityRatio": 0.81,
            "variable_sens": 137.3,
            "COB": 0,
            "IOB": -0.035,
            "reason": "COB: 0, Dev: -18, BGI: 0.43, ISF: 216, Target: 99; Eventual BG 105 > 99 but Min. Delta -2.60 < Exp. Delta 0.1; setting current basal of 0.4 as temp. Suggested rate is same as profile rate, no temp basal is active, doing nothing",
            "timestamp": "2017-06-25T15:55:10Z"
        },
        "iob": {
            "iob": -0.035,
            "basaliob": -0.035,
            "activity": -0.0004,
            "time": "2017-06-25T15:55:10Z"
        }
    },
    "uploaderBattery": 93,
    "created_at": "2017-06-25T15:55:10Z",
    "NSCLIENT_ID": 1498406118857
}
 */
@Suppress("SpellCheckingInspection")
@SingleIn(AppScope::class)
@Inject
class NSDeviceStatusHandler(
    private val preferences: Preferences,
    private val config: Config,
    private val dateUtil: DateUtil,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val aapsLogger: AAPSLogger,
    private val persistenceLayer: PersistenceLayer,
    private val overviewData: OverviewData,
    private val calculationWorkflow: CalculationWorkflow,
    private val rxBus: RxBus,
    // Plain CoroutineScope: @ApplicationScope is a javax qualifier and cannot appear in commonMain.
    private val appScope: CoroutineScope,
    private val nsClientV3Plugin: () -> NSClientV3Plugin,
    private val activePlugin: ActivePlugin,
    private val profileUtil: ProfileUtil,
) {

    /** A live push and a downloaded batch both count. The status time is what the 9-minute check uses. */
    fun handleNewData(deviceStatuses: Array<NSDeviceStatus>) {
        for (i in deviceStatuses.size - 1 downTo 0) {
            val nsDeviceStatus = deviceStatuses[i]
            if (config.AAPSCLIENT) {
                updatePumpData(nsDeviceStatus)
                updateDeviceData(nsDeviceStatus)
                updateOpenApsData(nsDeviceStatus)
                updateUploaderData(nsDeviceStatus)
            }
            if (config.APS) {
                nsDeviceStatus.pump?.let { preferences.put(BooleanNonKey.ObjectivesPumpStatusIsAvailableInNS, true) }  // Objective 0
                receiveLiveLoop(nsDeviceStatus)
            }
        }
        if (config.AAPSCLIENT && deviceStatuses.isNotEmpty()) {
            // One missed live push must not end the link. A downloaded status counts too.
            // The time on the status is used, not the time it was received. A status with no
            // time is skipped. A time older than 9 minutes does not keep the master reachable.
            val newestCreatedAt = deviceStatuses
                .mapNotNull { ds -> ds.createdAt?.let { runCatching { dateUtil.fromISODateString(it) }.getOrNull() } ?: ds.date }
                .maxOrNull() ?: 0L
            if (newestCreatedAt > 0L) nsClientV3Plugin().bumpDevicestatusHeartbeat(newestCreatedAt)
            mirrorMasterAps(deviceStatuses)
            // Rebuild predictions once for the newest data in this batch.
            appScope.launch { calculationWorkflow.runOnReceivedPredictions(overviewData) }
            rxBus.send(EventNsClientStatusUpdated())
        }
    }

    /**
     * 2026-10-16, per explicit request: a read-only client follows the APS choice (AMA, SMB or AutoISF) of its master, an AAPS
     * 3.x master that sends it as `configuration.aps` in its device status. A KMP master sends no such field; its choice comes
     * with the synced settings instead. Written as a remote value, so it is not sent back; ConfigBuilder's key observer does the
     * switch, the same way as for a choice pushed by a KMP master.
     */
    private fun mirrorMasterAps(deviceStatuses: Array<NSDeviceStatus>) {
        if (!preferences.get(BooleanKey.NsClientReadOnlyClient)) return
        val aps = (deviceStatuses.filter { it.configuration != null }.maxByOrNull { it.date ?: 0L }
            ?.configuration?.get("aps") as? JsonPrimitive)?.contentOrNull ?: return
        val plugin = activePlugin.getSpecificPluginsList(PluginType.APS).firstOrNull { (it as? APS)?.algorithm?.name == aps }
        if (plugin == null) {
            aapsLogger.debug(LTag.NSCLIENT, "Read-only client: the master runs $aps, which this build does not have")
            return
        }
        if (preferences.get(StringNonKey.ActivePluginAps) == plugin.pluginId) return
        preferences.putRemote(StringNonKey.ActivePluginAps, plugin.pluginId, 0L)
        aapsLogger.info(LTag.NSCLIENT, "Read-only client: APS set to ${plugin.pluginId}, as on the master ($aps)")
    }

    private fun updateDeviceData(deviceStatus: NSDeviceStatus) {
        val createdAt = deviceStatus.createdAt?.let { dateUtil.fromISODateString(it) } ?: return
        processedDeviceStatusData.device?.let { if (createdAt < it.createdAt) return } // take only newer record
        deviceStatusDisplayName(deviceStatus.device)?.let {
            processedDeviceStatusData.device = ProcessedDeviceStatusData.Device(createdAt, it)
        }
    }

    private fun updatePumpData(nsDeviceStatus: NSDeviceStatus) {
        val pump = nsDeviceStatus.pump ?: return
        val clock = pump.clock?.let { dateUtil.fromISODateString(it) } ?: return
        processedDeviceStatusData.pumpData?.let { if (clock < it.clock) return } // take only newer record

        // create new status and process data
        processedDeviceStatusData.pumpData = ProcessedDeviceStatusData.PumpData().also { deviceStatusPumpData ->
            deviceStatusPumpData.clock = clock
            pump.status?.status?.let { deviceStatusPumpData.status = it }
            pump.reservoir?.let { deviceStatusPumpData.reservoir = it }
            pump.reservoirDisplayOverride?.let { deviceStatusPumpData.reservoirDisplayOverride = it }
            pump.battery?.percent?.let {
                deviceStatusPumpData.isPercent = true
                deviceStatusPumpData.percent = it
            }
            pump.battery?.voltage?.let {
                deviceStatusPumpData.isPercent = false
                deviceStatusPumpData.voltage = it
            }
            pump.extended?.let {
                val extended = StringBuilder()
                // Plain text, not HTML. This used to build "<b>key:</b> value<br>" and the overview
                // stripped the markup straight back out before showing it.
                //
                // The values are whatever uploaded this device status - another AAPS, an older one,
                // or a different app entirely - so anything they marked up is flattened here, at the
                // point the foreign data arrives, rather than at every place that displays it.
                it.keys.forEach { key -> extended.appendLine("$key: ${it[key].toPlainText()}") }
                deviceStatusPumpData.extended = extended.toString()
                deviceStatusPumpData.activeProfileName = it.safeGetStringAllowNull("ActiveProfile", null)
            }
        }
    }

    private var requestedTrioProfileForSite: String? = null

    private fun readLoopResult(status: NSDeviceStatus, data: JsonObject, clock: Long): RT {
        if (status.device != "Trio") return RT.deserialize(data.toString()).apply { timestamp = clock }
        val site = preferences.get(StringKey.NsClientUrl)
        val units = trioSourceUnits(preferences.get(StringNonKey.NsClientTrioProfileUnits), site, clock)
        if (units == null && requestedTrioProfileForSite != site) {
            // Fetch the source profile again even when a previous build already imported it.
            requestedTrioProfileForSite = site
            nsClientV3Plugin().lastLoadedSrvModified.collections.profile = 0L
            aapsLogger.debug(LTag.NSCLIENT, "Trio target units are not known; source profile refresh requested")
        }
        return receivedLoopResult(status.device, data, units).apply { timestamp = clock }
    }

    private fun updateOpenApsData(nsDeviceStatus: NSDeviceStatus) {
        nsDeviceStatus.openaps?.suggested?.let {
            it.safeGetString("timestamp")?.let { timestamp ->
                val clock = dateUtil.fromISODateString(timestamp)
                // check if this is new data
                if (clock > processedDeviceStatusData.openAPSData.clockSuggested) {
                    try {
                        val suggested = readLoopResult(nsDeviceStatus, it, clock)
                        processedDeviceStatusData.openAPSData.suggested = suggested
                        val enactedRate = nsDeviceStatus.openaps?.enacted?.let { e -> runCatching { readLoopResult(nsDeviceStatus, e, clock).rate }.getOrNull() }
                        storeReceivedAutoIsf(suggested, clock, enactedRate)
                        mirrorAutomationStates(suggested)
                        // 2026-10-21, per explicit request: a client keeps the loop phone's step counts too, for the
                        // S5-S180 columns of the AutoISF table (the virtual phone already did)
                        if (config.AAPSCLIENT) nsDeviceStatus.device?.let { storeReceivedSteps(it, clock, suggested.reason.toString()) }
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.NSCLIENT, e.stackTraceToString())
                    }
                    processedDeviceStatusData.openAPSData.clockSuggested = clock
                    processedDeviceStatusData.getAPSResult()?.let { apsResult ->
                        // A result that does not say which algorithm made it cannot be stored: `toDb` throws,
                        // and in this scope that would end the app. It is still shown from the data above.
                        if (apsResult.algorithm == APSResult.Algorithm.UNKNOWN)
                            aapsLogger.debug(LTag.NSCLIENT, "APS result without algorithm from ${nsDeviceStatus.device}, not stored")
                        else
                            appScope.launch { persistenceLayer.insertOrUpdateApsResult(apsResult) }
                    }
                }
            }
        }
        nsDeviceStatus.openaps?.enacted?.let {
            it.safeGetString("timestamp")?.let { timestamp ->
                val clock = dateUtil.fromISODateString(timestamp)
                // check if this is new data
                if (clock > processedDeviceStatusData.openAPSData.clockEnacted) {
                    try {
                        processedDeviceStatusData.openAPSData.enacted = readLoopResult(nsDeviceStatus, it, clock)
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.NSCLIENT, e.stackTraceToString())
                    }
                    processedDeviceStatusData.openAPSData.clockEnacted = clock
                }
            }
        }
    }

    /**
     * A full phone on the virtual pump keeps the live phone's loop row and step counts.
     * It does not copy the live phone's pump state, and it ignores its own Nightscout echo.
     */
    private fun receiveLiveLoop(deviceStatus: NSDeviceStatus) {
        if (config.AAPSCLIENT) return
        if (activePlugin.activePump.selectedActivePump() !is VirtualPump) return
        val device = deviceStatus.device ?: return
        val own = "openaps://${config.deviceModelForUpload}"
        if (device != "Trio" && (!device.startsWith("openaps://") || device.equals(own, ignoreCase = true))) return
        val suggested = deviceStatus.openaps?.suggested ?: return
        val timestamp = suggested.safeGetString("timestamp") ?: return
        val clock = runCatching { dateUtil.fromISODateString(timestamp) }.getOrNull() ?: return
        val now = dateUtil.now()
        if (clock <= 0L || clock > now) return
        val rt = runCatching { readLoopResult(deviceStatus, suggested, clock) }.getOrElse { error ->
            aapsLogger.error(LTag.NSCLIENT, "Live loop result was not stored: ${error.message}")
            return
        }
        val enactedRate = deviceStatus.openaps?.enacted?.let { e -> runCatching { readLoopResult(deviceStatus, e, clock).rate }.getOrNull() }
        storeReceivedAutoIsf(rt, clock, enactedRate)
        if (stepsFromPrimarySite(preferences.get(BooleanKey.NsClientSecondaryEnabled)) &&
            preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual) &&
            now - clock <= LiveSteps.MAX_AGE_MS
        ) {
            storeReceivedSteps(device, clock, rt.reason.toString())
        }
    }

    /**
     * Step counts from the secondary Nightscout. Used only while that site is on, so a virtual
     * pump follows the live phone rather than this phone's own Nightscout.
     */
    /**
     * Returns one line saying what happened to each row, for the Nightscout log: why nothing was taken when this is
     * skipped, or how many rows came from this phone, were too old, had no loop result, had no step lines, or were kept.
     */
    fun takeLiveSteps(deviceStatuses: List<NSDeviceStatus>): String {
        if (stepsFromPrimarySite(preferences.get(BooleanKey.NsClientSecondaryEnabled))) return "skipped: secondary site is off"
        if (!preferences.get(BooleanKey.ApsAutoIsfUseLiveStepsOnVirtual)) return "skipped: step import is off"
        if (config.AAPSCLIENT) return "skipped: this is a client"
        if (activePlugin.activePump.selectedActivePump() !is VirtualPump) return "skipped: pump is not the Virtual pump"
        val now = dateUtil.now()
        val own = "openaps://${config.deviceModelForUpload}"
        var fromThisPhone = 0
        var notLoopPhone = 0
        var noLoopResult = 0
        var tooOld = 0
        var noStepLines = 0
        var kept = 0
        val otherPhones = mutableSetOf<String>()
        for (deviceStatus in deviceStatuses) {
            val device = deviceStatus.device
            if (device == null || !device.startsWith("openaps://")) {
                notLoopPhone++
                continue
            }
            if (device.equals(own, ignoreCase = true)) {
                fromThisPhone++
                continue
            }
            otherPhones += device
            val suggested = deviceStatus.openaps?.suggested
            val timestamp = suggested?.safeGetString("timestamp")
            val clock = timestamp?.let { runCatching { dateUtil.fromISODateString(it) }.getOrNull() }
            if (suggested == null || clock == null || clock <= 0L) {
                noLoopResult++
                continue
            }
            if (clock > now || now - clock > LiveSteps.MAX_AGE_MS) {
                tooOld++
                continue
            }
            val reason = runCatching { RT.deserialize(suggested.toString()).reason.toString() }.getOrNull()
            if (reason == null) {
                noLoopResult++
                continue
            }
            if (storeReceivedSteps(device, clock, reason)) kept++ else noStepLines++
        }
        return "own=$fromThisPhone, other phones=${otherPhones.ifEmpty { "none" }}, no loop result=$noLoopResult, " +
            "older than 20 min=$tooOld, no step lines=$noStepLines, kept=$kept, not a loop phone=$notLoopPhone"
    }

    /** True when the reason had the step lines and the sample was queued for storing. */
    private fun storeReceivedSteps(device: String, clock: Long, reason: String): Boolean {
        val buckets = LiveSteps.buckets(reason)
        if (!LiveSteps.hasDosingBuckets(buckets)) return false
        appScope.launch {
            val already = persistenceLayer.getStepsCountFromTimeToTime(clock, clock)
            if (already.any { it.timestamp == clock && it.device.equals(device, ignoreCase = true) }) return@launch
            persistenceLayer.insertOrUpdateStepsCounts(
                listOf(
                    SC(
                        timestamp = clock,
                        duration = 5 * 60_000L,
                        steps5min = buckets.getValue(5),
                        steps10min = buckets[10] ?: 0,
                        steps15min = buckets.getValue(15),
                        steps30min = buckets.getValue(30),
                        steps60min = buckets.getValue(60),
                        steps180min = buckets.getValue(180),
                        device = device
                    )
                )
            )
        }
        return true
    }

    /** A client, or a full phone on the virtual pump, keeps the live phone's loop row. */
    private fun keepsReceivedAutoIsf(): Boolean {
        if (config.AAPSCLIENT) return true
        if (!config.APS) return false
        return activePlugin.activePump.selectedActivePump() is VirtualPump
    }

    /**
     * A client keeps a read-only copy of the loop phone's automation states, from its newest loop result (2026-10-21, per
     * explicit request). The States dialog then shows the loop phone's real values; only the loop phone changes them.
     */
    private fun mirrorAutomationStates(rt: RT) {
        if (!config.AAPSCLIENT) return
        val values = rt.automationStatesValues ?: return
        val current = rt.automationStatesCurrent ?: return
        if (preferences.get(StringNonKey.AutomationStateValues) != values) preferences.put(StringNonKey.AutomationStateValues, values)
        if (preferences.get(StringNonKey.AutomationCurrentStates) != current) preferences.put(StringNonKey.AutomationCurrentStates, current)
    }

    private fun storeReceivedAutoIsf(rt: RT, clock: Long, enactedRate: Double?) {
        if (!keepsReceivedAutoIsf()) return
        val row = receivedAutoIsfRow(clock, rt, enactedRate) { profileUtil.convertToMgdl(it, profileUtil.units) } ?: return
        appScope.launch {
            val already = persistenceLayer.getAutoIsfValuesFromTimeToTime(clock, clock)
            if (already.any { it.timestamp == clock }) return@launch
            persistenceLayer.insertAutoIsfValue(row)
        }
    }

    private fun updateUploaderData(nsDeviceStatus: NSDeviceStatus) {
        val clock = nsDeviceStatus.createdAt?.let { dateUtil.fromISODateString(it) } ?: return
        val device = nsDeviceStatus.device ?: return
        val battery = nsDeviceStatus.uploaderBattery ?: nsDeviceStatus.uploader?.battery ?: return
        val isCharging = nsDeviceStatus.isCharging

        var uploader = processedDeviceStatusData.uploaderMap[device]
        // check if this is new data
        if (uploader == null || clock > uploader.clock) {
            if (uploader == null) uploader = ProcessedDeviceStatusData.Uploader()
            uploader.battery = battery
            uploader.clock = clock
            uploader.isCharging = isCharging
            processedDeviceStatusData.uploaderMap[device] = uploader
        }
    }
}

/** Known loop uploaders use different device-name formats. */
internal fun deviceStatusDisplayName(device: String?): String? = when {
    device == "Trio" -> device
    device?.startsWith("openaps://") == true -> device.removePrefix("openaps://")
    else -> null
}

/**
 * Flattens any markup a foreign uploader put in a device-status value.
 *
 * Kept identical to what the overview used to do before showing the text, so nothing that renders
 * correctly today starts showing raw tags.
 */
private fun Any?.toPlainText(): String =
    toString()
        .replace("<br>", "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
