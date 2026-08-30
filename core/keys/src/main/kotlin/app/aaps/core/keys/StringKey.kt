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
    // Added 2026-08-30 at explicit request: finer-grained tiers WITHIN the Standard/Low roles above, for
    // automations (e.g. MorningRoleSwapHigh/Normal) that want a specific percentage variant rather than
    // just "the" Standard or Low profile. Empty by default -- per explicit request, an unconfigured tier
    // silently falls back to its own base role's profile (see resolveTieredProfileName()) until you
    // actually re-pick a distinct profile for it (extended "Re-pick coded profiles" popup). No "Standard100"
    // entry: bare ApsAutoIsfStandardProfileName above already serves as that 100% tier.
    ApsAutoIsfStandard105ProfileName("autoisf_standard105_profile_name", ""),
    ApsAutoIsfStandard110ProfileName("autoisf_standard110_profile_name", ""),
    ApsAutoIsfLow70ProfileName("autoisf_low70_profile_name", ""),
    ApsAutoIsfLow80ProfileName("autoisf_low80_profile_name", ""),
    ApsAutoIsfLow90ProfileName("autoisf_low90_profile_name", ""),
    // Added 2026-08-27: Battery1pc/BatteryOver1pc (OpenAPSAutoISFPlugin.kt) previously hardcoded the
    // literal string "Current Profile50" for the low-battery safety-profile switch -- the one coded
    // profile role with no configurability or setup validation at all, unlike Standard/Low above.
    // Default preserves that literal so existing installs are unaffected.
    ApsAutoIsfSafetyProfileName("autoisf_safety_profile_name", "Current Profile50"),
    // Added 2026-08-23: same live-role indirection as the Standard/Low pair above, but for the six
    // steroid escalation tiers -- every switchProfileIfNeeded("Steroid Profile130")-style hardcoded
    // literal in the steroid escalation block (OpenAPSAutoISFPlugin.kt) now reads one of these instead.
    // Defaults originally matched the original hardcoded literals exactly, so nothing changed until
    // actively re-picked via showProfileNamesPopup().
    // "100" = steroids off/baseline, same role STEROID_TURN_OFF already targeted.
    ApsAutoIsfSteroid100ProfileName("autoisf_steroid_100_profile_name", "Current ProfileReal"),
    ApsAutoIsfSteroid110ProfileName("autoisf_steroid_110_profile_name", "Steroid Profile110"),
    ApsAutoIsfSteroid130ProfileName("autoisf_steroid_130_profile_name", "Steroid Profile130"),
    ApsAutoIsfSteroid150ProfileName("autoisf_steroid_150_profile_name", "Steroid Profile150"),
    // Renamed 2026-08-24 (default text only): these two used to be "Current Profile190Real" /
    // "Current ProfileReal250", the odd one out vs. the "Steroid ProfileNNN" pattern 110/130/150 already
    // used. ProfileSwitchDialog's steroidRoleKeyForProfileName() now requires the word "Steroid" (or a
    // "%") somewhere in the name before it will auto-detect a manual profile switch as a steroid-tier
    // pick -- these two didn't qualify, so a switch to either one was silently falling through to
    // Standard/Low instead. Changing the CODE DEFAULT alone does not rename anything on a device that
    // already has a value stored here, and does not rename the actual profile in the profile store --
    // both of those are real, separate, device-side steps still needed (see chat).
    ApsAutoIsfSteroid190ProfileName("autoisf_steroid_190_profile_name", "Steroid190"),
    ApsAutoIsfSteroid250ProfileName("autoisf_steroid_250_profile_name", "Steroid250"),
}