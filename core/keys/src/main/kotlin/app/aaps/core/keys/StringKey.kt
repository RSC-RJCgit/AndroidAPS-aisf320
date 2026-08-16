package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

enum class StringKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {

    GeneralUnits("units", "mg/dl"),
    GeneralLanguage("language", "default", defaultedBySM = true),
    GeneralPatientName("patient_name", ""),
    GeneralSkin("skin", ""),
    GeneralDarkMode("use_dark_mode", "dark", defaultedBySM = true),

    AapsDirectoryUri("aaps_directory", "", exportable = false),

    ProtectionMasterPassword("master_password", "", isPassword = true),
    ProtectionSettingsPassword("settings_password", "", isPassword = true),
    ProtectionSettingsPin("settings_pin", "", isPin = true),
    ProtectionApplicationPassword("application_password", "", isPassword = true),
    ProtectionApplicationPin("application_pin", "", isPin = true),
    ProtectionBolusPassword("bolus_password", "", isPassword = true),
    ProtectionBolusPin("bolus_pin", "", isPin = true),

    OverviewCopySettingsFromNs(key = "statuslights_copy_ns", "", dependency = BooleanKey.OverviewShowStatusLights),

    SafetyAge("age", "adult"),
    MaintenanceEmail("maintenance_logs_email", "logs@aaps.app", defaultedBySM = true),
    MaintenanceIdentification("email_for_crash_report", ""),
    AutomationLocation("location", "PASSIVE", hideParentScreenIfHidden = true),

    SmsAllowedNumbers("smscommunicator_allowednumbers", ""),
    SmsBattAlertNumbers("smscommunicator_battalertnumbers", ""),
    SmsBroadcastExcludeNumbers("smscommunicator_broadcastexcludenumbers", ""),
    SmsGentleHypoAlertNumbers("smscommunicator_gentlehypoalertnumbers", ""),
    SmsAlarmHypo1Numbers("smscommunicator_alarmhypo1numbers", ""),
    SmsAlarmHypo2Numbers("smscommunicator_alarmhypo2numbers", ""),
    SmsTest3Numbers("smscommunicator_test3numbers", ""),
    SmsPod2Numbers("smscommunicator_pod2numbers", ""),
    SmsDeadPodNumbers("smscommunicator_deadpodnumbers", ""),
    SmsConnectPodNumbers("smscommunicator_connectpodnumbers", ""),
    SmsOtpPassword("smscommunicator_otp_password", "", dependency = BooleanKey.SmsAllowRemoteCommands, isPassword = true),

    VirtualPumpType("virtualpump_type", "Generic AAPS"),

    NsClientUrl("nsclientinternal_url", ""),
    NsClientApiSecret("nsclientinternal_api_secret", "", isPassword = true),
    NsClientWifiSsids("ns_wifi_ssids", "", dependency = BooleanKey.NsClientUseWifi),
    NsClientAccessToken("nsclient_token", "", isPassword = true),
    NsClientSecondaryUrl("nsclient_secondary_url", ""),
    NsClientSecondaryAccessToken("nsclient_secondary_token", "", isPassword = true),

    // Google Drive settings
    GoogleDriveStorageType("google_drive_storage_type", "local"),
    GoogleDriveFolderId("google_drive_folder_id", ""),
    GoogleDriveRefreshToken("google_drive_refresh_token", "", isPassword = true),

    PumpCommonBolusStorage("pump_sync_storage_bolus", ""),
    PumpCommonTbrStorage("pump_sync_storage_tbr", ""),

    // Coded-profile indirection: OpenAPSAutoISFPlugin.kt's ~36 switchProfileIfNeeded("Current Profile"/
    // "Current ProfileReal") call sites read these instead of the literal names, so any locally-named
    // profile can fill either role. Defaults match the original hardcoded literals, so nothing changes
    // until you actively repick via the on-update profile-selection popup (or here directly).
    ApsAutoIsfStandardProfileName("autoisf_standard_profile_name", "Current ProfileReal"),
    ApsAutoIsfLowProfileName("autoisf_low_profile_name", "Current Profile"),
}