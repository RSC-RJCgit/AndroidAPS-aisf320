package app.aaps.plugins.sync.nsclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSAlarm
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventSWSyncStatus
import app.aaps.core.interfaces.rx.events.EventXdripRawBgReceived
import app.aaps.core.interfaces.sync.DataSyncSelector
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.sync.Sync
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.toJson
import app.aaps.core.validators.DefaultEditTextValidator
import app.aaps.core.validators.EditTextValidator
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.nsShared.NSClientFragment
import app.aaps.plugins.sync.nsShared.events.EventNSClientStatus
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiData
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiStatus
import app.aaps.plugins.sync.nsclient.data.AlarmAck
import app.aaps.plugins.sync.nsclient.extensions.toJson
import app.aaps.plugins.sync.nsclient.services.NSClientService
import app.aaps.plugins.sync.nsclientV3.keys.NsclientBooleanKey
import com.google.common.hash.Hashing
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NSClientPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    rh: ResourceHelper,
    private val context: Context,
    private val fabricPrivacy: FabricPrivacy,
    private val preferences: Preferences,
    private val receiverDelegate: ReceiverDelegate,
    private val dataSyncSelectorV1: DataSyncSelectorV1,
    private val dateUtil: DateUtil,
    private val profileUtil: ProfileUtil,
    private val nsSettingsStatus: NSSettingsStatus,
    private val decimalFormatter: DecimalFormatter,
    private val config: Config
) : NsClient, Sync, PluginBase(
    PluginDescription()
        .mainType(PluginType.SYNC)
        .fragmentClass(NSClientFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_nightscout_syncs)
        .pluginName(R.string.ns_client_title)
        .shortName(R.string.ns_client_short_name)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .description(R.string.description_ns_client),
    aapsLogger, rh
) {

    private val disposable = CompositeDisposable()
    override val listLog: MutableList<EventNSClientNewLog> = ArrayList()
    override val dataSyncSelector: DataSyncSelector get() = dataSyncSelectorV1
    override var status = ""
    var nsClientService: NSClientService? = null
    val isAllowed: Boolean
        get() = receiverDelegate.allowed
    val blockingReason: String
        get() = receiverDelegate.blockingReason

    override fun onStart() {
        context.bindService(Intent(context, NSClientService::class.java), mConnection, Context.BIND_AUTO_CREATE)
        super.onStart()
        receiverDelegate.grabReceiversState()
        disposable += rxBus
            .toObservable(EventNSClientStatus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           status = event.getStatus(context)
                           rxBus.send(EventNSClientUpdateGuiStatus())
                           // Pass to setup wizard
                           rxBus.send(EventSWSyncStatus(event.getStatus(context)))
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ if (nsClientService != null) context.unbindService(mConnection) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNSClientNewLog::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event: EventNSClientNewLog ->
                           addToLog(event)
                           aapsLogger.debug(LTag.NSCLIENT, event.action + " " + event.logText)
                       }, fabricPrivacy::logException)
        safe("tertiaryEnsureSocketConnected") { tertiaryEnsureSocketConnected() }
        disposable += rxBus
            .toObservable(EventXdripRawBgReceived::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ ev ->
                           safe("onEventXdripRawBgReceived") { uploadRawToTertiarySite(ev) }
                       }, fabricPrivacy::logException)
    }

    override fun onStop() {
        nsClientService?.destroy()
        if (nsClientService != null) context.unbindService(mConnection)
        disposable.clear()
        tertiaryDisconnectSocket()
        super.onStop()
    }

    private fun safe(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            aapsLogger.error(LTag.NSCLIENT, "$label failed; subscription stays alive for the next event", e)
            fabricPrivacy.logException(e)
        }
    }

    // Tertiary ("3y") raw-BG upload site (2026-09-30, per explicit request; moved here from
    // NSClientV3Plugin 2026-10-01, per explicit request -- this must live in the V1 plugin specifically
    // (not just speak the V1 socket.io protocol from inside the V3 class), since iAPS's own NS
    // integration needs V1, and this class -- not NSClientV3Plugin -- is the one that's actually active
    // on the phone that needs it. Confirmed via live logcat: NSClientV3Plugin's onStart() never ran
    // there at all, so a subscription hosted only in that class never registered, even though the
    // underlying EventXdripRawBgReceived fired every minute -- right protocol, wrong host class. A
    // genuinely separate third connection, used ONLY to carry raw BG (from an xDrip/GDH broadcast) out
    // as its own SGV entry -- unrelated to this plugin's own primary NS sync.
    //
    // Kept as ONE persistent connection, not opened per event: the broadcast this rides on fires
    // roughly once a minute, so reconnecting per event would mean constant connect/auth churn instead
    // of a normal long-lived socket. tertiaryEnsureSocketConnected() is idempotent -- a no-op once
    // already connected under the same URL+secret -- and is called both eagerly from onStart() and
    // defensively from every upload attempt (in case the connection dropped). Auth follows the exact
    // handshake NSClientService.kt uses for the primary V1 connection: an "authorize" emit carrying a
    // SHA1 hash of the secret (NsClientTertiaryAccessToken doubles as the API secret here, not a
    // bearer token), then dbAdd once the ack confirms write permission.
    private var tertiarySocket: Socket? = null
    private var tertiarySocketKey: String? = null
    private var tertiaryHasWriteAuth = false

    private fun tertiaryDisconnectSocket() {
        tertiarySocket?.off(Socket.EVENT_CONNECT)
        tertiarySocket?.off(Socket.EVENT_DISCONNECT)
        tertiarySocket?.disconnect()
        tertiarySocket = null
        tertiarySocketKey = null
        tertiaryHasWriteAuth = false
    }

    private fun tertiaryEnsureSocketConnected() {
        val url = preferences.get(StringKey.NsClientTertiaryUrl).trim()
        val secret = preferences.get(StringKey.NsClientTertiaryAccessToken).trim()
        if (url.isEmpty() || secret.isEmpty()) {
            if (tertiarySocket != null) tertiaryDisconnectSocket()
            return
        }
        val key = "$url|$secret"
        if (tertiarySocketKey == key && tertiarySocket?.connected() == true) return
        if (tertiarySocket != null) tertiaryDisconnectSocket()
        try {
            val secretHash = Hashing.sha1().hashString(secret, Charsets.UTF_8).toString()
            val opt = IO.Options().also { it.forceNew = true }
            tertiarySocket = IO.socket(url, opt).also { socket ->
                socket.on(Socket.EVENT_CONNECT) {
                    val authMessage = JSONObject().apply {
                        put("client", "Android_tertiary_raw")
                        put("history", 0)
                        put("status", false)
                        put("secret", secretHash)
                    }
                    socket.emit("authorize", authMessage, object : Ack {
                        override fun call(vararg args: Any) {
                            val response = args.getOrNull(0) as? JSONObject
                            tertiaryHasWriteAuth = response?.optBoolean("write") == true
                            rxBus.send(EventNSClientNewLog("● TERTIARY-RAW", if (tertiaryHasWriteAuth) "authorized" else "write permission not granted"))
                        }
                    })
                }
                socket.on(Socket.EVENT_DISCONNECT) { tertiaryHasWriteAuth = false }
                socket.connect()
            }
            tertiarySocketKey = key
        } catch (e: Exception) {
            aapsLogger.error(LTag.NSCLIENT, "Tertiary raw upload: connect failed: ${e.message}")
            tertiarySocket = null
            tertiarySocketKey = null
        }
    }

    // "raw only" (per explicit request): sgv/unfiltered both carry the raw value itself, never AAPS's own
    // smoothed BG. NS's own "noise" field (an integer 1-4 noise LEVEL in its schema) is left out rather
    // than set to anything derived from our raw value, to avoid conflating this fork's GV.noise
    // raw-piggyback convention with NS's own unrelated meaning for that field.
    private fun uploadRawToTertiarySite(event: EventXdripRawBgReceived) {
        tertiaryEnsureSocketConnected()
        val socket = tertiarySocket
        if (socket == null || !socket.connected() || !tertiaryHasWriteAuth) {
            rxBus.send(EventNSClientNewLog("● TERTIARY-RAW", "skipped raw=${event.raw}: socket not ready"))
            return
        }
        val data = JSONObject().apply {
            put("type", "sgv")
            put("sgv", event.raw)
            put("unfiltered", event.raw)
            put("device", event.device)
            put("date", event.timestamp)
            put("dateString", dateUtil.toISOString(event.timestamp))
        }
        val message = JSONObject().apply {
            put("collection", "entries")
            put("data", data)
        }
        socket.emit("dbAdd", message)
        rxBus.send(EventNSClientNewLog("● TERTIARY-RAW", "raw=${event.raw} device=${event.device}"))
    }

    override val hasWritePermission: Boolean get() = nsClientService?.hasWriteAuth == true
    override val connected: Boolean get() = nsClientService?.isConnected == true

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.NSCLIENT, "Service is disconnected")
            nsClientService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.NSCLIENT, "Service is connected")
            val mLocalBinder = service as NSClientService.LocalBinder?
            nsClientService = mLocalBinder?.serviceInstance // is null when running in roboelectric
        }
    }

    override fun detectedNsVersion(): String = nsSettingsStatus.getVersion()

    private fun addToLog(ev: EventNSClientNewLog) {
        synchronized(listLog) {
            listLog.add(0, ev)
            // remove the first line if log is too large
            if (listLog.size >= Constants.MAX_LOG_LINES) {
                listLog.removeAt(listLog.size - 1)
            }
            rxBus.send(EventNSClientUpdateGuiData())
        }
    }

    override fun resend(reason: String) {
        nsClientService?.resend(reason)
    }

    override fun pause(newState: Boolean) {
        preferences.put(NsclientBooleanKey.NsPaused, newState)
        rxBus.send(EventPreferenceChange(NsclientBooleanKey.NsPaused.key))
    }

    override val address: String get() = nsClientService?.nsURL ?: ""

    override fun handleClearAlarm(originalAlarm: NSAlarm, silenceTimeInMilliseconds: Long) {
        if (!isEnabled()) return
        if (!preferences.get(BooleanKey.NsClientUploadData)) {
            aapsLogger.debug(LTag.NSCLIENT, "Upload disabled. Message dropped")
            return
        }
        nsClientService?.sendAlarmAck(
            AlarmAck().also { ack ->
                ack.level = originalAlarm.level
                ack.group = originalAlarm.group
                ack.silenceTime = silenceTimeInMilliseconds
            })
    }

    override fun updateLatestBgReceivedIfNewer(latestReceived: Long) {
        nsClientService?.let { if (latestReceived > it.latestDateInReceivedData) it.latestDateInReceivedData = latestReceived }
    }

    override fun updateLatestTreatmentReceivedIfNewer(latestReceived: Long) {
        nsClientService?.let { if (latestReceived > it.latestDateInReceivedData) it.latestDateInReceivedData = latestReceived }
    }

    override fun resetToFullSync() {
        dataSyncSelector.resetToNextFullSync()
    }

    override suspend fun nsAdd(collection: String, dataPair: DataSyncSelector.DataPair, progress: String, profile: Profile?): Boolean {
        when (dataPair) {
            is DataSyncSelector.PairBolus                  -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairCarbs                  -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairBolusCalculatorResult  -> dataPair.value.toJson(true, dateUtil, profileUtil)
            is DataSyncSelector.PairTemporaryTarget        -> dataPair.value.toJson(true, dateUtil, profileUtil)
            is DataSyncSelector.PairFood                   -> dataPair.value.toJson(true)
            is DataSyncSelector.PairGlucoseValue           -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairTherapyEvent           -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairDeviceStatus           -> dataPair.value.toJson(dateUtil)
            is DataSyncSelector.PairTemporaryBasal         -> dataPair.value.toJson(true, profile, dateUtil)
            is DataSyncSelector.PairExtendedBolus          -> dataPair.value.toJson(true, profile, dateUtil)
            is DataSyncSelector.PairProfileSwitch          -> dataPair.value.toJson(true, dateUtil, decimalFormatter)
            is DataSyncSelector.PairEffectiveProfileSwitch -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairRunningMode -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairProfileStore           -> dataPair.value
            else                                           -> null
        }?.let { data ->
            nsClientService?.dbAdd(collection, data, dataPair, progress)
        }
        return true
    }

    override suspend fun nsUpdate(collection: String, dataPair: DataSyncSelector.DataPair, progress: String, profile: Profile?): Boolean {
        val id = when (dataPair) {
            is DataSyncSelector.PairBolus                  -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairCarbs                  -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairBolusCalculatorResult  -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairTemporaryTarget        -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairFood                   -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairGlucoseValue           -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairTherapyEvent           -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairTemporaryBasal         -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairExtendedBolus          -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairProfileSwitch          -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairEffectiveProfileSwitch -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairRunningMode            -> dataPair.value.ids.nightscoutId
            else                                           -> error("Unsupported data type")
        }
        when (dataPair) {
            is DataSyncSelector.PairBolus                  -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairCarbs                  -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairBolusCalculatorResult  -> dataPair.value.toJson(false, dateUtil, profileUtil)
            is DataSyncSelector.PairTemporaryTarget        -> dataPair.value.toJson(false, dateUtil, profileUtil)
            is DataSyncSelector.PairFood                   -> dataPair.value.toJson(false)
            is DataSyncSelector.PairGlucoseValue           -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairTherapyEvent           -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairTemporaryBasal         -> dataPair.value.toJson(false, profile, dateUtil)
            is DataSyncSelector.PairExtendedBolus          -> dataPair.value.toJson(false, profile, dateUtil)
            is DataSyncSelector.PairProfileSwitch          -> dataPair.value.toJson(false, dateUtil, decimalFormatter)
            is DataSyncSelector.PairEffectiveProfileSwitch -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairRunningMode            -> dataPair.value.toJson(false, dateUtil)
            else                                           -> null
        }?.let { data ->
            nsClientService?.dbUpdate(collection, id, data, dataPair, progress)
        }
        return true
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey != "ns_client_synchronization" && requiredKey != "ns_client_alarm_options" && requiredKey != "ns_client_connection_options" && requiredKey != "ns_client_advanced" && requiredKey != "ns_secondary_settings" && requiredKey != "ns_tertiary_settings") return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "ns_client_settings"
            title = rh.gs(R.string.ns_client_title)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveStringPreference(
                    ctx = context, stringKey = StringKey.NsClientUrl, dialogMessage = R.string.ns_client_url_dialog_message, title = R.string.ns_client_url_title,
                    validatorParams = DefaultEditTextValidator.Parameters(testType = EditTextValidator.TEST_HTTPS_URL)
                )
            )
            addPreference(
                AdaptiveStringPreference(
                    ctx = context, stringKey = StringKey.NsClientApiSecret, dialogMessage = R.string.ns_client_secret_dialog_message, title = R.string.ns_client_secret_title,
                    validatorParams = DefaultEditTextValidator.Parameters(testType = EditTextValidator.TEST_MIN_LENGTH, minLength = 12)
                )
            )
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "ns_client_synchronization"
                title = rh.gs(R.string.ns_sync_options)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientUploadData, summary = R.string.ns_upload_summary, title = R.string.ns_upload).also {
                    if (dataSyncSelectorV1.fullAapsOnVirtualPump) it.setSummary(R.string.ns_upload_virtual_warning_summary)
                })
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.BgSourceUploadToNs, title = app.aaps.core.ui.R.string.do_ns_upload_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptCgmData, summary = R.string.ns_receive_cgm_summary, title = R.string.ns_receive_cgm))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptProfileStore, summary = R.string.ns_receive_profile_store_summary, title = R.string.ns_receive_profile_store))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptTempTarget, summary = R.string.ns_receive_temp_target_summary, title = R.string.ns_receive_temp_target))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptProfileSwitch, summary = R.string.ns_receive_profile_switch_summary, title = R.string.ns_receive_profile_switch))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptInsulin, summary = R.string.ns_receive_insulin_summary, title = R.string.ns_receive_insulin))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptInsulinExcludeSmb, summary = R.string.ns_receive_insulin_exclude_smb_summary, title = R.string.ns_receive_insulin_exclude_smb))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptCarbs, summary = R.string.ns_receive_carbs_summary, title = R.string.ns_receive_carbs))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptTherapyEvent, summary = R.string.ns_receive_therapy_events_summary, title = R.string.ns_receive_therapy_events))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptRunningMode, summary = R.string.ns_receive_running_mode_summary, title = R.string.ns_receive_running_mode))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientAcceptTbrEb, summary = R.string.ns_receive_tbr_eb_summary, title = R.string.ns_receive_tbr_eb).also {
                    if (dataSyncSelectorV1.fullAapsOnVirtualPump) {
                        it.disableKeepingParent()
                        it.setSummary(R.string.ns_receive_tbr_eb_blocked_virtual_summary)
                    }
                })
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "ns_secondary_settings"
                title = rh.gs(R.string.ns_secondary_settings)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientSecondaryEnabled, summary = R.string.ns_secondary_enabled_summary, title = R.string.ns_secondary_enabled))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientSecondaryAcceptTherapyEvent, summary = R.string.ns_secondary_receive_therapy_events_summary, title = R.string.ns_secondary_receive_therapy_events))
                addPreference(AdaptiveStringPreference(ctx = context, stringKey = StringKey.NsClientSecondaryUrl, summary = R.string.ns_secondary_url_summary, title = R.string.ns_secondary_url))
                addPreference(AdaptiveStringPreference(ctx = context, stringKey = StringKey.NsClientSecondaryAccessToken, summary = R.string.ns_secondary_token_summary, title = R.string.ns_secondary_token))
            })
            // Tertiary ("3y") raw-BG upload site settings (2026-10-01): the upload logic itself
            // (tertiaryEnsureSocketConnected()/uploadRawToTertiarySite() above) now lives entirely in
            // this class, not NSClientV3Plugin -- see that pair's own doc comment for why.
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "ns_tertiary_settings"
                title = rh.gs(R.string.ns_tertiary_settings)
                addPreference(AdaptiveStringPreference(ctx = context, stringKey = StringKey.NsClientTertiaryUrl, summary = R.string.ns_tertiary_url_summary, title = R.string.ns_tertiary_url))
                addPreference(AdaptiveStringPreference(ctx = context, stringKey = StringKey.NsClientTertiaryAccessToken, summary = R.string.ns_tertiary_token_summary, title = R.string.ns_tertiary_token))
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "ns_client_alarm_options"
                title = rh.gs(R.string.ns_alarm_options)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientNotificationsFromAlarms, title = R.string.ns_alarms))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientNotificationsFromAnnouncements, title = R.string.ns_announcements))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.NsClientAlarmStaleData, title = R.string.ns_alarm_stale_data_value_label))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.NsClientUrgentAlarmStaleData, title = R.string.ns_alarm_urgent_stale_data_value_label))
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "ns_client_connection_options"
                title = rh.gs(R.string.connection_settings_title)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientUseCellular, title = R.string.ns_cellular))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientUseRoaming, title = R.string.ns_allow_roaming))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientUseWifi, title = R.string.ns_wifi))
                addPreference(
                    AdaptiveStringPreference(
                        ctx = context, stringKey = StringKey.NsClientWifiSsids, dialogMessage = R.string.ns_wifi_allowed_ssids, title = R.string.ns_wifi_ssids,
                        validatorParams = DefaultEditTextValidator.Parameters(emptyAllowed = true)
                    )
                )
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientUseOnBattery, title = R.string.ns_battery))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientUseOnCharging, title = R.string.ns_charging))
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "ns_client_advanced"
                title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientLogAppStart, title = R.string.ns_log_app_started_event))
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = BooleanKey.NsClientCreateAnnouncementsFromErrors,
                        summary = R.string.ns_create_announcements_from_errors_summary,
                        title = R.string.ns_create_announcements_from_errors_title
                    )
                )
                addPreference(
                    AdaptiveSwitchPreference(
                        ctx = context,
                        booleanKey = BooleanKey.NsClientCreateAnnouncementsFromCarbsReq,
                        summary = R.string.ns_create_announcements_from_carbs_req_summary,
                        title = R.string.ns_create_announcements_from_carbs_req_title
                    )
                )
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.NsClientSlowSync, title = R.string.ns_sync_slow))
            })
        }
    }
}