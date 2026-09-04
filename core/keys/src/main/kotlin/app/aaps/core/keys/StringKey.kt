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
    // Format: label|@latitude,longitude OR postal address|radius metres|arrival note|exit note|cooldown minutes.
    // A dash disables an unused slot. Street-address slots are resolved by Android's geocoder and cached.
    AutomationLocationSmsNumbers("automation_location_sms_numbers", "+61411600285", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    // Build.MODEL of the phone allowed to send coded-location SMS / CarePortal notes / AnyDesk.
    // Blank = no pin (every phone with locations enabled may notify). List2 and Settings write this.
    AutomationLocationSmsDeviceModel("automation_location_sms_device_model", "", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAirport1("automation_airport_1", "Adelaide Airport|@-34.945000,138.530556|1500|AptAd||90", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAirport2("automation_airport_2", "Sydney Airport|@-33.946111,151.177222|1500|AptSy||90", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAirport3("automation_airport_3", "Dubai International Airport|@25.252778,55.364444|2000|AptDb||90", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAirport4("automation_airport_4", "Melbourne Airport|@-37.673333,144.843333|1500|AptMb||90", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAirport5("automation_airport_5", "Brisbane Airport|@-27.384167,153.117500|1500|AptBn||90", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAddress1("automation_address_1", "Home|5 Rockness Court, Woodforde SA 5072, Australia|150|HmEnt|HmLve|30", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAddress2("automation_address_2", "Netball|155 Railway Terrace, Keswick Terminal SA 5035, Australia|200|NblAr|NblLv|30", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAddress3("automation_address_3", "Cheer|5 McInnes Street, Ridleyton SA 5008, Australia|150|ChrAr|ChrLv|30", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAddress4("automation_address_4", "-", dependency = BooleanKey.AutomationCodedLocationsEnabled),
    AutomationAddress5("automation_address_5", "-", dependency = BooleanKey.AutomationCodedLocationsEnabled),

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

    // Coded-profile indirection: OpenAPSAutoISFPlugin.kt's ~36 switchProfileIfNeeded() call sites read
    // these instead of any literal profile name, so any locally-named profile can fill either role.
    // Defaults blanked 2026-08-30 at explicit request (removing the old "Current Profile"/"Current
    // ProfileReal" literal defaults, since real installs have long since actively re-picked real names
    // via the profile-selection popup and the literal default text was stale/misleading) -- an unpicked
    // install now shows the popup's normal "nothing selected yet" fallback rather than a name that may
    // not even exist as a real profile.
    ApsAutoIsfStandardProfileName("autoisf_standard_profile_name", ""),
    ApsAutoIsfLowProfileName("autoisf_low_profile_name", ""),
    // Added 2026-08-30 at explicit request: finer-grained tiers WITHIN the Standard/Low roles above, for
    // automations (e.g. MorningRoleSwapHigh/Normal) that want a specific percentage variant rather than
    // just "the" Standard or Low profile. Empty by default -- per explicit request, an unconfigured tier
    // silently falls back to its own base role's profile (see resolveTieredProfileName()) until you
    // actually re-pick a distinct profile for it (extended "Re-pick coded profiles" popup).
    //
    // ApsAutoIsfStandard100ProfileName (added same day, second pass): a STABLE anchor for the true 100%
    // baseline, deliberately SEPARATE from the mutable ApsAutoIsfStandardProfileName above.
    // ApsAutoIsfStandardProfileName is the live "currently active role" preference -- MorningRoleSwapHigh
    // overwrites it directly when it escalates to Standard110, which means it can no longer answer "what
    // was the real 100% profile" once that's happened. This anchor is written ONLY by
    // showProfileNamesPopup() (mirrored alongside the Standard row there), never by any automation, so it
    // always still holds the user's actual chosen 100% profile regardless of what the active role has
    // since been escalated to. Standard105/110's own fallback, and the Standard ladder's floor rung, both
    // read this instead of the mutable role.
    ApsAutoIsfStandard100ProfileName("autoisf_standard100_profile_name", ""),
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
    // "100" = steroids off/baseline, same role STEROID_TURN_OFF already targeted. Default blanked
    // 2026-08-30 alongside Standard/Low above (was "Current ProfileReal").
    ApsAutoIsfSteroid100ProfileName("autoisf_steroid_100_profile_name", ""),
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
