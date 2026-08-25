package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey

enum class BooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val calculatedDefaultValue: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val exportable: Boolean = true
) : BooleanPreferenceKey {

    GeneralSimpleMode("simple_mode", true),
    GeneralSetupWizardProcessed("startupwizard_processed", false),
    OverviewKeepScreenOn("keep_screen_on", false, calculatedDefaultValue = true),
    OverviewShowTreatmentButton("show_treatment_button", false, defaultedBySM = true, hideParentScreenIfHidden = true),
    OverviewShowWizardButton("show_wizard_button", true, defaultedBySM = true),
    OverviewShowInsulinButton("show_insulin_button", true, defaultedBySM = true),
    OverviewShowCarbsButton("show_carbs_button", true, defaultedBySM = true),
    OverviewShowCgmButton("show_cgm_button", false, defaultedBySM = true, showInNsClientMode = false),
    OverviewShowCalibrationButton("show_calibration_button", false, defaultedBySM = true, showInNsClientMode = false),
    OverviewShortTabTitles("short_tabtitles", false, defaultedBySM = true),
    OverviewShowNotesInDialogs("show_notes_entry_dialogs", false, defaultedBySM = true),
    OverviewShowStatusLights("show_statuslights", true, defaultedBySM = true, hideParentScreenIfHidden = true),
    OverviewUseBolusAdvisor("use_bolus_advisor", true, defaultedBySM = true),
    OverviewUseBolusReminder("use_bolus_reminder", true, defaultedBySM = true),
    OverviewUseSuperBolus("key_usersuperbolus", false, defaultedBySM = true, hideParentScreenIfHidden = true),

    PumpBtWatchdog("bt_watchdog", false, showInNsClientMode = false, hideParentScreenIfHidden = true),

    AlertMissedBgReading("enable_missed_bg_readings", false),
    AlertPumpUnreachable("enable_pump_unreachable_alert", true),
    AlertCarbsRequired("enable_carbs_required_alert_local", true),
    AlertUrgentAsAndroidNotification("raise_urgent_alarms_as_android_notification", true),
    AlertIncreaseVolume("gradually_increase_notification_volume", true),

    BgSourceUploadToNs("dexcomg5_nsupload", true, defaultedBySM = true, hideParentScreenIfHidden = true),
    BgSourceCreateSensorChange("dexcom_lognssensorchange", true, defaultedBySM = true),

    ApsUseDynamicSensitivity("use_dynamic_sensitivity", false),
    ApsUseAutosens("openapsama_useautosens", true, defaultedBySM = true, negativeDependency = ApsUseDynamicSensitivity), // change from default false
    ApsUseSmb("use_smb", true, defaultedBySM = true), // change from default false
    ApsUseSmbWithHighTt("enableSMB_with_high_temptarget", false, defaultedBySM = true, dependency = ApsUseSmb),
    ApsUseSmbAlways("enableSMB_always", true, defaultedBySM = true, dependency = ApsUseSmb), // change from default false
    ApsUseSmbWithCob("enableSMB_with_COB", true, defaultedBySM = true, dependency = ApsUseSmb), // change from default false
    ApsUseSmbWithLowTt("enableSMB_with_temptarget", true, defaultedBySM = true, dependency = ApsUseSmb), // change from default false
    ApsUseSmbAfterCarbs("enableSMB_after_carbs", true, defaultedBySM = true, dependency = ApsUseSmb), // change from default false
    ApsUseUam("use_uam", true, defaultedBySM = true), // change from default false
    ApsSensitivityRaisesTarget("sensitivity_raises_target", true, defaultedBySM = true),
    ApsResistanceLowersTarget("resistance_lowers_target", true, defaultedBySM = true), // change from default false
    ApsAlwaysUseShortDeltas("always_use_shortavg", false, defaultedBySM = true, hideParentScreenIfHidden = true),
    ApsDynIsfAdjustSensitivity("dynisf_adjust_sensitivity", false, defaultedBySM = true, dependency = ApsUseDynamicSensitivity), // change from default false
    ApsAmaAutosensAdjustTargets("autosens_adjust_targets", true, defaultedBySM = true),
    ApsAutoIsfHighTtRaisesSens("high_temptarget_raises_sensitivity", false, defaultedBySM = true),
    ApsAutoIsfLowTtLowersSens("low_temptarget_lowers_sensitivity", false, defaultedBySM = true),
    ApsAutoIsfTddSensitivity("autoisf_tdd_sensitivity", true, defaultedBySM = true),
    ApsAutoIsfTddFactor("autoisf_tdd_factor", true, defaultedBySM = true),
    ApsActivityDetection("activity_detection", false, defaultedBySM = true),
    AutomationStatesEnabled("automation_states_enabled", true),
    ApsAutoIsfCustomAutomationsEnabled("autoisf_custom_automations_enabled", true),
    // Shows the two direct Kotlin MJ buttons on Overview. Independent of native Automation events.
    ApsAutoIsfMjKotlinButtonsEnabled("autoisf_mj_kotlin_buttons_enabled", true),
    // Shows the direct Kotlin Steroids-ON button on Overview. Independent of native Automation events.
    ApsAutoIsfSteroidKotlinButtonEnabled("autoisf_steroid_kotlin_button_enabled", true),
    ApsUseAutoIsfWeights("openapsama_enable_autoISF", false, defaultedBySM = true),
    ApsAutoIsfSmbOnEvenTarget("Enable alternative activation of SMB always", false, defaultedBySM = true),   // profile target
    ApsAutoIsfSplitBolusEnabled("split_bolus_enabled", false, defaultedBySM = true),
    // Extra optional carbs-graph overlay: a theoretical two-compartment (Dalla Man-style) Ra(t) carb
    // absorption model curve, peak fixed at 90min, hard cutoff at 360min (6h) -- purely a calculated
    // overlay, off by default, independent of the empirical Carbs Absorption line (this5MinAbsorption-
    // based). See carbModelK/carbModelCutoffMin in PrepareIobAutosensGraphDataWorker.kt (was briefly
    // 70min -- an old mismatch between this comment and the code -- reverted back to 90min).
    ApsAutoIsfShowCarbModelCurve("show_carb_model_curve", true, defaultedBySM = true),
    // 5th graph: a fixed clone of the main graph's content (BG line, predictions, bucketed/smoothed
    // trend, treatments/therapy events, activity, carb absorption + model curve, BG parabola, raw BG +
    // its UKF-smoothed trace) but WITHOUT basal -- not independently configurable per-series like the
    // secondary graphs 1-4, just one on/off switch. Off by default. Two ways to flip it, both writing
    // this same key so they stay in sync: a real Preferences checkbox (OverviewPlugin.kt) and the
    // Graph5ToggleTT remote TT-signal (OpenAPSAutoISFPlugin.kt, TT=5.142, same pattern as
    // ApsAutoIsfShowCarbModelCurve/Graph2ToggleTT above). See OverviewFragment.updateGraph().
    ApsAutoIsfShowGraph5("show_graph5", false, defaultedBySM = true),
    // When graph5 is on: false (default, preserves prior behaviour) = show every series it always has
    // (BGL lines + insulin activity + all 3 carb-related lines + basal). true = BGL-only, skipping
    // addActivity/addCarbModelCurve/addUamCarbImpact/addCombinedCarbs -- see the gating in
    // OverviewFragment's graph5-building block. Reachable from list2 (basal icon), reusing
    // GraphToggleEntry's second checkbox slot (normally calibration) for this instead.
    ApsAutoIsfGraph5BglOnly("graph5_bgl_only", false, defaultedBySM = true),
    // When enabled, bypasses the entire varOffset/targetBgOffset derivation (smb_delivery_ratio_max as a
    // base, carbsReqThreshold-encoded offset1/2/3 flags, hour-of-day and delta_accl adjustments) in favor
    // of a single fixed mmol value (DoubleKey.ApsAutoIsfSmbOffsetOverride) — see DetermineBasalAutoISF.kt.
    ApsAutoIsfSmbOffsetOverrideEnabled("autoisf_smb_offset_override_enabled", false, defaultedBySM = true),
    // Single master switch for BOTH BolusGiven (bg1/bg2/bg3) and BolusGivenMild — turning this off
    // disables both boost automations together, not just one.
    ApsAutoIsfBoostAutomationsEnabled("autoisf_boost_automations_enabled", true, defaultedBySM = true),
    // Master on/off for Tier 3 "UAM Boost" in DetermineBasalAutoISF.kt, ported from
    // Boost-in-AAPS_3.4's OapsProfileBoost.boostActive (there, a whole time-window +
    // sleep-in/step-detection subsystem; here, deliberately just a plain toggle -- the simpler
    // version explicitly chosen over porting that full subsystem). "Uam" prefix (not just
    // ApsAutoIsfBoostEnabled) to stay clearly distinct from ApsAutoIsfBoostAutomationsEnabled above,
    // which is a different, unrelated feature (BolusGiven/BolusGivenMild). Default false: opt-in,
    // Tier 3 UAM Boost was hardcoded off (`val boostActive = false`) before this preference existed.
    ApsAutoIsfUamBoostEnabled("autoisf_uam_boost_enabled", false, defaultedBySM = true),
    // Added 2026-08-23: routes the AutoISF plugin's own getGlucoseStatusData() -- the single choke
    // point behind glucoseStatusProvider.glucoseStatusData app-wide when this plugin is the active APS
    // -- through the literal "UKF1" comparison series (UnscentedKalmanFilterPlugin.smoothForDisplay()
    // over raw/noise readings, the same source UKFcheck found consistently fastest to react) instead of
    // whichever smoothing algorithm is actually live (LibreSpecial EMA by default). Default false:
    // opt-in, unproven for real dosing use. See OpenAPSAutoISFPlugin.kt's applyUkf1DosingOverride() doc
    // comment for exactly which GlucoseStatusAutoIsf fields this does/doesn't replace.
    ApsAutoIsfUseUkf1ForDosing("autoisf_use_ukf1_for_dosing", false, defaultedBySM = true),
    // Read-only diagnostics. When enabled, OpenAPSAutoISFPlugin writes a versioned, chunked replay
    // trace to the ordinary AAPS log after determine_basal() returns. It never changes an input or
    // result and is off by default because the trace is intentionally detailed.
    ApsAutoIsfReplayTraceEnabled("autoisf_replay_trace_enabled", false, defaultedBySM = true),
    FslApplySmoothing("fsl_apply_smoothing", true, defaultedBySM = true),
    // Mutually-exclusive UKF requests. UKF1 replaces LibreSpecial EMA with smoothRawRealtime() only
    // on a non-Client Virtual Pump; real-pump and Client ingestion remain on LibreSpecial EMA. UKF2
    // retains the original EMA and calculates smoothForDisplayNEW() over it for comparison history;
    // its returned value is currently not assigned to the live BG.
    FslUseUkfSmoothing("fsl_use_ukf_smoothing", false, defaultedBySM = true),
    FslUseUkfLibreSpecialSmoothing("fsl_use_ukf_libre_special_smoothing", false, defaultedBySM = true),
    // Per-line show/hide + calibration toggles for the three raw/noise-derived graph comparison lines
    // (UKF1 = rawBgSmoothedSeries, UKF2 = libreSpecialPreUkfSeries, UKF3 = libreSpecialFromUkf1Series
    // -- see PrepareBgDataWorker.kt). Deliberately separate from OverviewMenus.CharType.RAW_BG_SMOOTHED
    // (the main chart-selection-panel checkbox) and from FslUseUkfLibreSpecialSmoothing (the live FSL
    // pipeline mode) -- these are local display-only settings reachable via OverviewFragment's IOB
    // double-tap list 2 (showTtCodesListDialog()), not relayed via TT the way dosing settings are.
    // Defaults preserve each line's pre-existing behavior: UKF1 previously never calibrated, UKF3
    // previously always did; UKF2 has no calibration toggle (its value comes from a persisted history
    // of already-calibrated live-pipeline results, not a raw value recomputed at display time).
    ShowUkf1Graph("show_ukf1_graph", true, defaultedBySM = true),
    Ukf1ApplyLibreCalibration("ukf1_apply_libre_calibration", false, defaultedBySM = true),
    ShowUkf2Graph("show_ukf2_graph", true, defaultedBySM = true),
    ShowUkf3Graph("show_ukf3_graph", true, defaultedBySM = true),
    Ukf3ApplyLibreCalibration("ukf3_apply_libre_calibration", true, defaultedBySM = true),
    FslCalibrationTrigger("calibration_stops_SMB", false, defaultedBySM = true),
    FslCalibrationEnd("calibration_end", false, defaultedBySM = true),
    // Master on/off for OldSensorAdj (the 11-15 elapsed-day aging-sensor FslCalSlope/FslCalOffset override).
    // Off = stay at whatever's configured in Libre special settings as usual, no override ever applied.
    ApsAutoIsfOldSensorAdjEnabled("autoisf_old_sensor_adj_enabled", true, defaultedBySM = true),
    // Independent master gate for executing the complete SensorAge calibration routine. This is
    // intentionally separate from OldSensorAdjEnabled: switching execution off restores any active
    // slope/offset override and then skips sensor-age tracking/tier evaluation entirely.
    ApsAutoIsfSensorAgeCodeEnabled("autoisf_sensor_age_code_enabled", true, defaultedBySM = true),
    // Internal-only: tracks whether OldSensorAdj currently has FslCalSlope/FslCalOffset overridden, so
    // it knows whether to snapshot (first activation) or restore (once the 11-15 day window or MJ
    // condition ends, or the toggle above is turned off). Not shown in any preference screen.
    ApsAutoIsfOldSensorAdjActive("autoisf_old_sensor_adj_active", false, defaultedBySM = true, exportable = false),
    // Internal state for the temporary low-raw calibration override. Changed 2026-08-19: no longer has
    // a hard expiry -- previously capped at six hours even if the 24h-below-10 condition was still
    // true (that "expires regardless" behavior turned out not to be the intended design), now just
    // stays active/reasserted for as long as the rolling 24-hour condition keeps holding, and yields
    // immediately once it stops being true.
    ApsAutoIsfLowRaw24OverrideActive("autoisf_low_raw_24_override_active", false, defaultedBySM = true, exportable = false),

    // Internal-only: "OldPod" notify-once latch — true once the pod>60h + BGL>10.0mmol-for-2h+ notice
    // (CarePortal note + SMS) has fired for the CURRENT pod, so it isn't repeated every cycle. Resets to
    // false once cannula age drops back under 60h (i.e. a new pod was actually inserted), re-arming it
    // for the next old pod. Not shown in any preference screen.
    ApsAutoIsfOldPodNotified("autoisf_old_pod_notified", false, defaultedBySM = true, exportable = false),
    // Real-pump phone latch for its low-storage alert/SMS/NS Note. It prevents one set per APS cycle;
    // re-armed only after that phone's storage recovers above the hysteresis threshold.
    ApsAutoIsfLowStorageNotified("autoisf_low_storage_notified", false, defaultedBySM = true, exportable = false),
    // Internal-only, one-shot cross-module signal: set true by the CleanGraphTT trigger (see
    // OpenAPSAutoISFPlugin.kt, TT=5.42), consumed and cleared back to false by OverviewFragment's
    // updateGraph() the next time it runs — applies showSmbLabels=false + basalToggleIndex=2 (no SMB
    // labels, no BGL arrowheads, solid uniform-green graph line), the same combo as long-pressing IOB
    // then Basal. plugins:aps has no dependency on core:graph (where those companion fields live), so a
    // plain preference flag is the simplest way to signal across modules. Not shown in any screen.
    ApsAutoIsfCleanGraphRequested("autoisf_clean_graph_requested", false, defaultedBySM = true, exportable = false),

    // Internal, not user-facing. Set by OpenAPSAutoISFPlugin's BolusGivenMild block when it fires while
    // BG < 5.9mmol: forces DetermineBasalAutoISF's varOffset (the "no COB + BG under target+offset ->
    // zero SMB" gate) to 0 for that window, so the mild-boost's stronger delivery ratio isn't wasted
    // behind a gate that would otherwise still block SMB outright at that BG. Self-clears the same way
    // the delivery-ratio boost itself does — when the mild fire's own 2-min TT expires.
    ApsAutoIsfMildOffsetZeroActive("autoisf_mild_offset_zero_active", false, defaultedBySM = true, exportable = false),

    ActivityMonitorDetection("activity_detection", false, defaultedBySM=true),
    ActivityMonitorOvernight("ignore_inactivity_overnight", true, defaultedBySM=true, dependency = ActivityMonitorDetection),
    ActivityMonitorStepsActive("steps_activity_detected", false, defaultedBySM=true),
    ActivityMonitorStepsInactive("steps_inactivity_detected", false, defaultedBySM=true),
    ActivityMonitorShowStepsFromSmartphone("steps_graph_from_smartphone", true, defaultedBySM = true),

    MaintenanceEnableFabric("enable_fabric2", true, defaultedBySM = true, hideParentScreenIfHidden = true),

    MaintenanceEnableExportSettingsAutomation("enable_unattended_export", true, defaultedBySM = false),
    MaintenanceAutoExportLogsToCloud("maintenance_auto_export_logs_to_cloud", false, defaultedBySM = false),

    AutotuneAutoSwitchProfile("autotune_auto", false),
    AutotuneCategorizeUamAsBasal("categorize_uam_as_basal", false),
    AutotuneTuneInsulinCurve("autotune_tune_insulin_curve", false),
    AutotuneCircadianIcIsf("autotune_circadian_ic_isf", false),
    AutotuneAdditionalLog("autotune_additional_log", false),

    SmsAllowRemoteCommands("smscommunicator_remotecommandsallowed", false),
    SmsReportPumpUnreachable("smscommunicator_report_pump_unreachable", true),
    SmsReportMissedBgReadings("smscommunicator_report_missed_bg_readings", true),

    VirtualPumpStatusUpload("virtualpump_uploadstatus", false, showInNsClientMode = false),
    NsClientUploadData("ns_upload", true, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptCgmData("ns_receive_cgm", false, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptProfileStore("ns_receive_profile_store", true, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptTempTarget("ns_receive_temp_target", false, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptProfileSwitch("ns_receive_profile_switch", false, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptInsulin("ns_receive_insulin", false, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptInsulinExcludeSmb("ns_receive_insulin_exclude_smb", false, showInNsClientMode = false, dependency = NsClientAcceptInsulin),
    NsClientSecondaryEnabled("nsclient_secondary_enabled", false),
    NsClientSecondaryAcceptTherapyEvent("nsclient_secondary_receive_therapy_events", true, dependency = NsClientSecondaryEnabled),
    NsClientAcceptCarbs("ns_receive_carbs", false, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptTherapyEvent("ns_receive_therapy_events", false, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptRunningMode("ns_receive_running_mode", false, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptTbrEb("ns_receive_tbr_eb", false, showInNsClientMode = false, engineeringModeOnly = true),
    NsClientNotificationsFromAlarms("ns_alarms", false, calculatedDefaultValue = true),
    NsClientNotificationsFromAnnouncements("ns_announcements", false, calculatedDefaultValue = true),
    NsClientUseCellular("ns_cellular", true),
    NsClientUseRoaming("ns_allow_roaming", true, dependency = NsClientUseCellular),
    NsClientUseWifi("ns_wifi", true),
    NsClientUseOnBattery("ns_battery", true),
    NsClientUseOnCharging("ns_charging", true),
    NsClientLogAppStart("ns_log_app_started_event", false, calculatedDefaultValue = true),
    NsClientCreateAnnouncementsFromErrors("ns_create_announcements_from_errors", false, calculatedDefaultValue = true, showInNsClientMode = false),
    NsClientCreateAnnouncementsFromCarbsReq("ns_create_announcements_from_carbs_req", false, calculatedDefaultValue = true, showInNsClientMode = false),
    NsClientSlowSync("ns_sync_slow", false),
    NsClient3UseWs("ns_use_ws", true),
    OpenHumansWifiOnly("oh_wifi_only", true),
    OpenHumansChargingOnly("oh_charging_only", false),
    XdripSendStatus("xdrip_send_status", false),
    XdripSendDetailedIob("xdripstatus_detailediob", true, defaultedBySM = true, hideParentScreenIfHidden = true),
    XdripSendBgi("xdripstatus_showbgi", true, defaultedBySM = true, hideParentScreenIfHidden = true),
    WearControl(key = "wearcontrol", defaultValue = false),
    WearWizardBg(key = "wearwizard_bg", defaultValue = true, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardTt(key = "wearwizard_tt", defaultValue = false, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardTrend(key = "wearwizard_trend", defaultValue = false, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardCob(key = "wearwizard_cob", defaultValue = true, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardIob(key = "wearwizard_iob", defaultValue = true, dependency = WearControl, hideParentScreenIfHidden = true),
    WearCustomWatchfaceAuthorization(key = "wear_custom_watchface_autorization", defaultValue = false),
    WearNotifyOnSmb(key = "wear_notifySMB", defaultValue = true),
    WearBroadcastData(key = "wear_broadcast_data", defaultValue = false),
    AutomationFuzzyEquals("automation_fuzzy_equals", defaultValue = false),
    WizardCalculationVisible("wizard_calculation_visible", defaultValue = false),
    WizardCorrectionPercent("wizard_correction_percent", defaultValue = false),
    // Delayed bolus (50%-profile wizard mechanism). Constant renamed from WizardSplitBolusEnabled;
    // the stored key string is kept so existing users' setting survives the rename.
    WizardDelayedBolusEnabled("wizard_split_bolus_enabled", defaultValue = false),
    WizardIncludeCob("wizard_include_cob", defaultValue = false),
    WizardIncludeTrend("wizard_include_trend_bg", defaultValue = false),
    SiteRotationManagePump("site_rotation_manage_pump", defaultValue = false),
    SiteRotationManageCgm("site_rotation_manage_cgm", defaultValue = false),

    // Export destination settings
    ExportAllCloudEnabled("export_all_cloud_enabled", defaultValue = false),
    ExportLogEmailEnabled("export_log_email_enabled", defaultValue = true),
    ExportLogCloudEnabled("export_log_cloud_enabled", defaultValue = false),
    ExportSettingsLocalEnabled("export_settings_local_enabled", defaultValue = true),
    ExportSettingsCloudEnabled("export_settings_cloud_enabled", defaultValue = false),
    ExportCsvLocalEnabled("export_csv_local_enabled", defaultValue = true),
    ExportCsvCloudEnabled("export_csv_cloud_enabled", defaultValue = false),

}
