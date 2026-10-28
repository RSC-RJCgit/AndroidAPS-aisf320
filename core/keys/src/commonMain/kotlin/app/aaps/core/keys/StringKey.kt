package app.aaps.core.keys

import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.PreferenceEnabledCondition
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.StringValidator
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec
import app.aaps.core.keys.interfaces.TextRef

/**
 * The entry in [StringKey.IosHeartbeatCheckDevice] that forgets every device that failed the check, so they are listed
 * again (2026-10-24, per explicit request). Not a Bluetooth identifier.
 */
const val IOS_HEARTBEAT_FORGET_CHECKED = "forget"

enum class StringKey(
    override val key: String,
    override val defaultValue: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    private val entriesRefs: Map<String, TextRef> = emptyMap(),
    /**
     * Entry labels that are not a resource in this module. Only the units preference needs this:
     * "mg/dL" and "mmol/L" read the same in every language, and the `units_*` strings themselves
     * now live in `:core:ui`, which this module cannot depend on.
     */
    private val entriesLiterals: Map<String, String> = emptyMap(),
    override val defaultedBySM: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val isHashed: Boolean = false,
    override val validator: StringValidator = StringValidator.NONE,
    override val visibility: ElementVisibility = ElementVisibility.ALWAYS,
    override val enabledCondition: PreferenceEnabledCondition = PreferenceEnabledCondition.ALWAYS,
    override val sync: SyncSpec? = null,
    override val platforms: Set<AppPlatform> = AppPlatform.ALL
) : StringPreferenceKey {

    /**
     * iOS only (2026-10-24, per explicit request): what the RileyLink heartbeat is doing, written by `IosRileyLinkHeartbeat`
     * and shown read-only under its switch as Bluetooth heartbeat status, including test progress,
     * verified backups and the actual device and time of the last received heartbeat.
     */
    IosRileyLinkStatus(
        key = "ios_rileylink_status",
        defaultValue = "",
        title = KeysStrings.pref_title_ios_rileylink_status,
        enabledCondition = PreferenceEnabledCondition { false },
        platforms = setOf(AppPlatform.Ios)
    ),

    /**
     * iOS only (2026-10-24, per explicit request): the device picked to be checked as a backup heartbeat (its Bluetooth
     * identifier). The list is filled at run time from [StringNonKey.IosHeartbeatSeen]. `IosRileyLinkHeartbeat` empties it again
     * when the check is done.
     */
    IosHeartbeatCheckDevice(
        key = "ios_heartbeat_check_device",
        defaultValue = "",
        title = KeysStrings.pref_title_ios_heartbeat_check_device,
        preferenceType = PreferenceType.LIST,
        dependency = BooleanKey.IosHeartbeatCheckOthers,
        platforms = setOf(AppPlatform.Ios)
    ),

    GeneralUnits(
        key = "units",
        defaultValue = "mg/dl",
        title = KeysStrings.pref_title_units,
        preferenceType = PreferenceType.LIST,
        entriesLiterals = mapOf(
            "mg/dl" to "mg/dL",
            "mmol" to "mmol/L"
        ),
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    GeneralLanguage(
        key = "language",
        defaultValue = "default",
        title = KeysStrings.pref_title_language,
        preferenceType = PreferenceType.LIST,
        entriesRefs = mapOf(
            "default" to KeysStrings.lang_default,
            "en" to KeysStrings.lang_en,
            "af" to KeysStrings.lang_af,
            "bg" to KeysStrings.lang_bg,
            "cs" to KeysStrings.lang_cs,
            "de" to KeysStrings.lang_de,
            "dk" to KeysStrings.lang_dk,
            "fr" to KeysStrings.lang_fr,
            "nl" to KeysStrings.lang_nl,
            "es" to KeysStrings.lang_es,
            "el" to KeysStrings.lang_el,
            "ga" to KeysStrings.lang_ga,
            "it" to KeysStrings.lang_it,
            "ko" to KeysStrings.lang_ko,
            "lt" to KeysStrings.lang_lt,
            "nb" to KeysStrings.lang_nb,
            "pl" to KeysStrings.lang_pl,
            "pt" to KeysStrings.lang_pt,
            "pt_BR" to KeysStrings.lang_pt_br,
            "ro" to KeysStrings.lang_ro,
            "ru" to KeysStrings.lang_ru,
            "sk" to KeysStrings.lang_sk,
            "sv" to KeysStrings.lang_sv,
            "tr" to KeysStrings.lang_tr,
            "zh_TW" to KeysStrings.lang_zh_tw,
            "zh_CN" to KeysStrings.lang_zh_cn
        ),
        defaultedBySM = true
    ),
    // Not the patient: the person using THIS phone. It names this AAPS instance (export file names,
    // the OTP label, the watch), so it stays device-local and carries no SyncSpec - a client must not
    // take the master's name. The stored key keeps its old "patient_name" spelling on purpose, so no
    // installation loses its value.
    GeneralInstanceName(
        key = "patient_name",
        defaultValue = "",
        title = KeysStrings.pref_title_patient_name,
        summary = KeysStrings.pref_summary_patient_name,
        validator = StringValidator.personName()
    ),
    /**
     * The main screen in landscape (2026-10-24, per explicit request): "auto" keeps the old choice (a phone wide, a tablet
     * split), "wide" and "split" force one. See `overviewLayout` in :ui.
     */
    OverviewLandscapeLayout(
        key = "overview_landscape_layout",
        defaultValue = "auto",
        title = KeysStrings.pref_title_overview_landscape_layout,
        summary = KeysStrings.pref_summary_overview_landscape_layout,
        preferenceType = PreferenceType.LIST,
        entriesRefs = mapOf(
            "auto" to KeysStrings.overview_landscape_layout_auto,
            "wide" to KeysStrings.overview_landscape_layout_wide,
            "split" to KeysStrings.overview_landscape_layout_split
        )
    ),

    GeneralDarkMode(
        key = "use_dark_mode",
        defaultValue = "dark",
        title = KeysStrings.pref_title_app_color_scheme,
        summary = KeysStrings.pref_summary_theme_switcher,
        preferenceType = PreferenceType.LIST,
        entriesRefs = mapOf(
            "dark" to KeysStrings.pref_dark_theme,
            "light" to KeysStrings.pref_light_theme,
            "system" to KeysStrings.pref_follow_system_theme
        ),
        defaultedBySM = true
    ),

    AapsDirectoryUri(key = "aaps_directory", defaultValue = "", title = KeysStrings.pref_title_aaps_directory),

    ProtectionMasterPassword(key = "master_password", defaultValue = "", title = KeysStrings.pref_title_master_password, isPassword = true, isHashed = true),
    ProtectionSettingsPassword(
        key = "settings_password", defaultValue = "", title = KeysStrings.pref_title_settings_password, isPassword = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeSettings }, ProtectionType.CUSTOM_PASSWORD.ordinal)
    ),
    ProtectionSettingsPin(
        key = "settings_pin", defaultValue = "", title = KeysStrings.pref_title_settings_pin, isPin = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeSettings }, ProtectionType.CUSTOM_PIN.ordinal)
    ),
    ProtectionApplicationPassword(
        key = "application_password", defaultValue = "", title = KeysStrings.pref_title_application_password, isPassword = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeApplication }, ProtectionType.CUSTOM_PASSWORD.ordinal)
    ),
    ProtectionApplicationPin(
        key = "application_pin", defaultValue = "", title = KeysStrings.pref_title_application_pin, isPin = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeApplication }, ProtectionType.CUSTOM_PIN.ordinal)
    ),
    ProtectionBolusPassword(
        key = "bolus_password", defaultValue = "", title = KeysStrings.pref_title_bolus_password, isPassword = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeBolus }, ProtectionType.CUSTOM_PASSWORD.ordinal)
    ),
    ProtectionBolusPin(
        key = "bolus_pin", defaultValue = "", title = KeysStrings.pref_title_bolus_pin, isPin = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeBolus }, ProtectionType.CUSTOM_PIN.ordinal)
    ),

    SafetyAge(key = "age", defaultValue = "adult", title = KeysStrings.pref_title_patient_age, preferenceType = PreferenceType.LIST, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    MaintenanceEmail(
        key = "maintenance_logs_email",
        defaultValue = "logs@aaps.app",
        title = KeysStrings.maintenance_email,
        defaultedBySM = true,
        validator = StringValidator.email()
    ),
    MaintenanceIdentification(key = "email_for_crash_report", defaultValue = "", title = KeysStrings.pref_title_identification),
    AutomationLocation(
        key = "location",
        defaultValue = "PASSIVE",
        title = KeysStrings.pref_title_automation_location,
        preferenceType = PreferenceType.LIST,
        entriesRefs = mapOf(
            "PASSIVE" to KeysStrings.automation_location_passive,
            "NETWORK" to KeysStrings.automation_location_network,
            "GPS" to KeysStrings.automation_location_gps
        ),
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AutomationLocationSmsDeviceModel(
        key = "automation_location_sms_device_model",
        defaultValue = "",
        title = KeysStrings.pref_title_location_sms_model,
        summary = KeysStrings.pref_summary_location_sms_model,
    ),
    AutomationLocationSmsNumbers(
        key = "automation_location_sms_numbers",
        defaultValue = "",
        title = KeysStrings.pref_title_location_sms_numbers,
        summary = KeysStrings.pref_summary_location_sms_numbers,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AutomationAirport1("automation_airport_1", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    AutomationAirport2("automation_airport_2", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    AutomationAirport3("automation_airport_3", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    AutomationAirport4("automation_airport_4", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    AutomationAirport5("automation_airport_5", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    AutomationAddress1("automation_address_1", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    AutomationAddress2("automation_address_2", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    AutomationAddress3("automation_address_3", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    AutomationAddress4("automation_address_4", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    AutomationAddress5("automation_address_5", "-", KeysStrings.pref_title_location_slot, KeysStrings.pref_summary_location_slot, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),

    SmsAllowedNumbers(
        key = "smscommunicator_allowednumbers",
        defaultValue = "",
        title = KeysStrings.smscommunicator_allowednumbers,
        summary = KeysStrings.smscommunicator_allowednumbers_summary,
        validator = StringValidator.multiPhone()
    ),
    SmsBroadcastExcludeNumbers(
        key = "smscommunicator_broadcastexcludenumbers",
        defaultValue = "",
        title = KeysStrings.smscommunicator_broadcastexcludenumbers,
        summary = KeysStrings.smscommunicator_broadcastexcludenumbers_summary,
        validator = StringValidator.multiPhone()
    ),
    SmsBattAlertNumbers(
        key = "smscommunicator_battalertnumbers",
        defaultValue = "",
        title = KeysStrings.smscommunicator_battalertnumbers,
        summary = KeysStrings.smscommunicator_battalertnumbers_summary,
        validator = StringValidator.multiPhone()
    ),
    SmsAlarmHypo1Numbers(
        key = "smscommunicator_alarmhypo1numbers",
        defaultValue = "",
        title = KeysStrings.smscommunicator_alarmhypo1numbers,
        summary = KeysStrings.smscommunicator_alarmhypo1numbers_summary,
        validator = StringValidator.multiPhone()
    ),
    SmsAlarmHypo2Numbers(
        key = "smscommunicator_alarmhypo2numbers",
        defaultValue = "",
        title = KeysStrings.smscommunicator_alarmhypo2numbers,
        summary = KeysStrings.smscommunicator_alarmhypo2numbers_summary,
        validator = StringValidator.multiPhone()
    ),
    SmsPod2Numbers(
        key = "smscommunicator_pod2numbers",
        defaultValue = "",
        title = KeysStrings.smscommunicator_pod2numbers,
        summary = KeysStrings.smscommunicator_pod2numbers_summary,
        validator = StringValidator.multiPhone()
    ),
    SmsConnectPodNumbers(
        key = "smscommunicator_connectpodnumbers",
        defaultValue = "",
        title = KeysStrings.smscommunicator_connectpodnumbers,
        summary = KeysStrings.smscommunicator_connectpodnumbers_summary,
        validator = StringValidator.multiPhone()
    ),
    SmsOtpPassword(
        key = "smscommunicator_otp_password",
        defaultValue = "",
        title = KeysStrings.smscommunicator_otp_pin,
        summary = KeysStrings.smscommunicator_otp_pin_summary,
        dependency = BooleanKey.SmsAllowRemoteCommands,
        isPassword = true,
        validator = StringValidator.pinStrength()
    ),

    VirtualPumpType(key = "virtualpump_type", defaultValue = "Generic AAPS", title = KeysStrings.pref_title_virtual_pump_type, preferenceType = PreferenceType.LIST),

    /**
     * Which embedded Watch Face Format face the wear app installs through Watch Face Push on a
     * Wear OS 6+ watch. Watch Face Push gives an app one slot, so this is a choice, not a set.
     * Values in [PushedWatchfaceId]. Older watches receive and keep the value but cannot act on it.
     *
     * Not in any preference screen: the Wear plugin's main page shows it as a card, and only once
     * the watch has reported that it has Watch Face Push. No summary for that reason.
     */
    WearPushedWatchface(
        key = "wear_pushed_watchface",
        defaultValue = PushedWatchfaceId.CWF,
        title = KeysStrings.pref_title_wear_pushed_watchface,
        preferenceType = PreferenceType.LIST,
        entriesRefs = mapOf(
            PushedWatchfaceId.WFS to KeysStrings.wear_pushed_watchface_wfs,
            PushedWatchfaceId.CWF to KeysStrings.wear_pushed_watchface_cwf
        )
    ),

    NsClientUrl(
        key = "nsclientinternal_url",
        defaultValue = "",
        title = KeysStrings.ns_client_url_title,
        summary = KeysStrings.ns_client_url_summary,
        validator = StringValidator.httpsUrl()
    ),
    NsClientApiSecret(
        key = "nsclientinternal_api_secret",
        defaultValue = "",
        title = KeysStrings.ns_client_secret_title,
        summary = KeysStrings.ns_client_secret_summary,
        isPassword = true,
        validator = StringValidator.minLength(12)
    ),
    NsClientWifiSsids(
        key = "ns_wifi_ssids",
        defaultValue = "",
        title = KeysStrings.ns_wifi_ssids,
        summary = KeysStrings.ns_wifi_ssids_summary,
        dependency = BooleanKey.NsClientUseWifi
    ),
    NsClientAccessToken(
        key = "nsclient_token",
        defaultValue = "",
        title = KeysStrings.nsclient_token_title,
        summary = KeysStrings.nsclient_token_summary,
        isPassword = true,
        validator = StringValidator.minLength(17)
    ),
    NsClientSecondaryUrl(
        key = "nsclient_secondary_url",
        defaultValue = "",
        title = KeysStrings.ns_secondary_url,
        summary = KeysStrings.ns_secondary_url_summary,
        dependency = BooleanKey.NsClientSecondaryEnabled,
        validator = StringValidator.httpsUrl()
    ),
    // Trio remote control (2026-10-29, per explicit request): the same four values LoopFollow asks for. The APNs key is the
    // .p8 file of the Apple developer account that built Trio. Not synced: they belong to this phone's setup.
    TrioRemoteUser(
        key = "trio_remote_user",
        defaultValue = "",
        title = KeysStrings.trio_remote_user,
        summary = KeysStrings.trio_remote_user_summary,
        dependency = BooleanKey.TrioRemoteEnabled
    ),
    TrioRemoteSharedSecret(
        key = "trio_remote_shared_secret",
        defaultValue = "",
        title = KeysStrings.trio_remote_shared_secret,
        summary = KeysStrings.trio_remote_shared_secret_summary,
        isPassword = true,
        dependency = BooleanKey.TrioRemoteEnabled
    ),
    TrioRemoteApnsKeyId(
        key = "trio_remote_apns_key_id",
        defaultValue = "",
        title = KeysStrings.trio_remote_apns_key_id,
        summary = KeysStrings.trio_remote_apns_key_id_summary,
        dependency = BooleanKey.TrioRemoteEnabled
    ),
    TrioRemoteApnsKey(
        key = "trio_remote_apns_key",
        defaultValue = "",
        title = KeysStrings.trio_remote_apns_key,
        summary = KeysStrings.trio_remote_apns_key_summary,
        isPassword = true,
        dependency = BooleanKey.TrioRemoteEnabled
    ),
    NsClientSecondaryAccessToken(
        key = "nsclient_secondary_token",
        defaultValue = "",
        title = KeysStrings.ns_secondary_token,
        summary = KeysStrings.ns_secondary_token_summary,
        isPassword = true,
        dependency = BooleanKey.NsClientSecondaryEnabled
    ),
    ApsAutoIsfLowProfileName(
        key = "autoisf_low_profile_name",
        defaultValue = "",
        title = KeysStrings.pref_title_aps_low_profile_name,
        summary = KeysStrings.pref_summary_aps_low_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfStandardProfileName(
        key = "autoisf_standard_profile_name",
        defaultValue = "",
        title = KeysStrings.pref_title_aps_standard_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfStandard100ProfileName(
        key = "autoisf_standard100_profile_name",
        defaultValue = "",
        title = KeysStrings.pref_title_aps_standard100_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfStandard105ProfileName(
        key = "autoisf_standard105_profile_name",
        defaultValue = "",
        title = KeysStrings.pref_title_aps_standard105_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfStandard110ProfileName(
        key = "autoisf_standard110_profile_name",
        defaultValue = "",
        title = KeysStrings.pref_title_aps_standard110_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfLow70ProfileName(
        key = "autoisf_low70_profile_name",
        defaultValue = "",
        title = KeysStrings.pref_title_aps_low70_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfLow80ProfileName(
        key = "autoisf_low80_profile_name",
        defaultValue = "",
        title = KeysStrings.pref_title_aps_low80_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfLow90ProfileName(
        key = "autoisf_low90_profile_name",
        defaultValue = "",
        title = KeysStrings.pref_title_aps_low90_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSteroid100ProfileName(
        key = "autoisf_steroid_100_profile_name",
        defaultValue = "",
        title = KeysStrings.pref_title_aps_steroid_100_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSteroid110ProfileName(
        key = "autoisf_steroid_110_profile_name",
        defaultValue = "Steroid Profile110",
        title = KeysStrings.pref_title_aps_steroid_110_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSteroid130ProfileName(
        key = "autoisf_steroid_130_profile_name",
        defaultValue = "Steroid Profile130",
        title = KeysStrings.pref_title_aps_steroid_130_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSteroid150ProfileName(
        key = "autoisf_steroid_150_profile_name",
        defaultValue = "Steroid Profile150",
        title = KeysStrings.pref_title_aps_steroid_150_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSteroid190ProfileName(
        key = "autoisf_steroid_190_profile_name",
        defaultValue = "Steroid190",
        title = KeysStrings.pref_title_aps_steroid_190_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSteroid250ProfileName(
        key = "autoisf_steroid_250_profile_name",
        defaultValue = "Steroid250",
        title = KeysStrings.pref_title_aps_steroid_250_profile_name,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    ;

    override val entries: Map<String, TextRef> =
        entriesRefs + entriesLiterals.mapValues { TextRef.Literal(it.value) }
}
