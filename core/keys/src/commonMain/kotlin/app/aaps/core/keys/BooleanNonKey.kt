package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class BooleanNonKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = true
) : BooleanNonPreferenceKey {

    GeneralSetupWizardProcessed("startupwizard_processed", false),
    /** This installation has successfully applied the built-in settings at least once. */
    BuiltInSettingsAppliedBefore("built_in_settings_applied_before", false, exportable = false),
    SetupWizardIUnderstand("I_understand", false),
    /** Profile store owner (2026-11-17): the one-time "Client" defaults of ProfileStoreOwner.applyClientDefaults ran on this client. */
    NsClientProfileClientDefaultsApplied("nsclient_profile_client_defaults_applied", false, exportable = false),
    /** Statistics, Sensor tab (2026-11-04, per explicit request): draw the sensor's raw signal (the default) or BG */
    StatsSensorRaw("stats_sensor_raw", true),
    /** Quick-launch bar with larger buttons (2026-11-26, per explicit request, List 2 "Quick-launch buttons"). This phone only. */
    QuickLaunchLargeButtons("quick_launch_large_buttons", false),
    /** Quick-launch bar turned vertical by a double-tap on its grip (2026-11-27, per explicit request). Portrait, this phone only. */
    QuickLaunchVertical("quick_launch_vertical", false),
    ObjectivesLoopUsed("ObjectivesLoopUsed", false),
    ObjectivesScaleUsed("ObjectivesScaleUsed", false),
    // Objectives screen, master on VirtualPump only: objectives no longer limit anything. Local only.
    ObjectivesVirtualPumpBypass("objectives_virtual_pump_bypass", false, exportable = false),
    ObjectivesPumpStatusIsAvailableInNS("ObjectivespumpStatusIsAvailableInNS", false),
    ObjectivesBgIsAvailableInNs("ObjectivesbgIsAvailableInNS", false),
    ObjectivesProfileSwitchUsed("ObjectivesProfileSwitchUsed", false),
    ObjectivesDisconnectUsed("ObjectivesDisconnectUsed", false),
    ObjectivesReconnectUsed("ObjectivesReconnectUsed", false),
    ObjectivesTempTargetUsed("ObjectivesTempTargetUsed", false),
    AutosensUsedOnMainPhone("used_autosens_on_main_phone", false),

    // Wizard toggle states (persisted across restarts, not user-facing preferences)
    WizardIncludeCob("wizard_include_cob", false),
    WizardIncludeTrend("wizard_include_trend_bg", false),
    // Bolus calculator ticks remembered from the last confirmed meal calculation (2026-11-05, per explicit request: the
    // simple screen uses the detailed screen's last settings). Auto fat/protein, Unreliable SMBs and Small meal are saved
    // to the Overview "Bolus calculator defaults" settings instead.
    WizardIncludeBg("wizard_include_bg", true),
    WizardIncludeTt("wizard_include_tt", true),
    WizardIncludeIob("wizard_include_iob", true),
    WizardSplitFpuBolus("wizard_split_fpu_bolus", true),
    WizardDelayFpuInsteadOfCancel("wizard_delay_fpu_instead_of_cancel", true),
    WizardGiveRestLater("wizard_give_rest_later", true),
    // 2026-11-14, per explicit request: the held part goes with the carb split (on) or the protein/fat doses (off)
    WizardGiveRestWithSplit("wizard_give_rest_with_split", true),

    // Set once the read-only client defaults (bolus calculator, NSClient BG) were added to the toolbar (2026-10-16)
    QuickLaunchReadOnlyDefaultsAdded("quick_launch_read_only_defaults_added", false),

    // Export destination settings (managed by ExportOptionsDialog, no preferences UI)
    ExportAllCloudEnabled("export_all_cloud_enabled", false),
    ExportLogEmailEnabled("export_log_email_enabled", true),
    ExportLogCloudEnabled("export_log_cloud_enabled", false),
    ExportSettingsLocalEnabled("export_settings_local_enabled", true),
    ExportSettingsCloudEnabled("export_settings_cloud_enabled", false),
    ExportCsvLocalEnabled("export_csv_local_enabled", true),
    ExportCsvCloudEnabled("export_csv_cloud_enabled", false),

    AllowHardwarePump("allow_hardware_pump", false),

    // Set (committed) right before a startup VACUUM and cleared after it finishes. If it is still
    // set on the next launch, the previous VACUUM died below the JVM (native abort / OOM) — used to
    // break the boot-crash loop. Transient device state, not a user setting → not exportable.
    VacuumInProgress("vacuum_in_progress", false, exportable = false),
    LowStorageNotified("low_storage_notified", false, exportable = false),
    LowStorageTargetedSmsSent("low_storage_targeted_sms_sent", false, exportable = false),

    // Set when a mild boost fires under 5.9 mmol. DetermineBasal forces the target offset to 0 while this is set.
    // Cleared when no temp target is active.
    ApsAutoIsfMildOffsetZeroActive("autoisf_mild_offset_zero_active", false, exportable = false),

    // True while Standard and Low point at tier C after a stuck-high rescue. Local only.
    ApsAutoIsfStuckHighTierCActive("autoisf_stuck_high_tier_c_active", false, exportable = false),

    // True when carbs or a UAM boost were seen during the current unexplained-high stretch. Local only.
    ApsAutoIsfUnexplainedHighMealSeen("autoisf_unexplained_high_meal_seen", false, exportable = false),

    // Profile-batch toggles. Off until something turns them on. Local only.
    ApsAutoIsfProfileBatchAutoEnabled("autoisf_profile_batch_auto_enabled", false, exportable = false),
    ApsAutoIsfProfileBatchRevertEnabled("autoisf_profile_batch_revert_enabled", false, exportable = false),
    ApsAutoIsfProfileBatchRevertCEnabled("autoisf_profile_batch_revert_c_enabled", false, exportable = false),

    ApsAutoIsfSensorAgeAutoOffLatched("autoisf_sensor_age_auto_off_latched", false, exportable = false),

    // Master switch for the old-sensor slope tiers. On until something turns it off. Local only.
    ApsAutoIsfOldSensorAdjEnabled("autoisf_old_sensor_adj_enabled", true, exportable = false),
    ApsAutoIsfOldSensorAdjActive("autoisf_old_sensor_adj_active", false, exportable = false),
    ApsAutoIsfOldPodInsReqBoostActive("autoisf_old_pod_insreq_boost_active", false, exportable = false),

    // Libre raw display smoothing. On until a virtual-pump toggle turns it off. Local only.
    ApsAutoIsfFslUseUkfSmoothing("autoisf_fsl_use_ukf_smoothing", true, exportable = false),

    // One-shot. The overview applies it on the next draw, then clears it.
    ApsAutoIsfCleanGraphRequested("autoisf_clean_graph_requested", false, exportable = false),

    // One-shot, like the one above: List 3 "Graph back to normal" (TT 5.246) raises it, the overview applies it and clears it.
    ApsAutoIsfGraphResetRequested("autoisf_graph_reset_requested", false, exportable = false),

    // Simple graphs (2026-11-21, per explicit request): the chip next to AIV switches the overview graphs between Simple and
    // Detailed. Display only, this device only; the saved graph heights are not changed.
    ApsAutoIsfSimpleGraphs("autoisf_simple_graphs", false, exportable = false),
    // 2026-11-27, per explicit request: the "U" graph mode, the main and IOB graphs drawn by upstream's own code (graphs/upstream).
    // Only while Simple graphs is off. This phone only.
    ApsAutoIsfUpstreamGraphs("autoisf_upstream_graphs", false, exportable = false),

    // The one card that says AAPS has its own BG alarms now, shown once after the update (`_docs/ALARMS.md`, step 2).
    BgAlarmsIntroShown("bg_alarms_intro_shown", false, exportable = false),

    // Client: the pairing secret has been replaced by the key exchange in the hello. Until then the client sends a hello
    // with a key on each connection, which is also how a phone paired from a QR code says hello at all. Belongs to the
    // pairing.
    NsClientControlSecretReplaced("nsclient_control_secret_replaced", false, exportable = false),

    /**
     * The user stopped AAPS on this phone (Maintenance → Stop AAPS), for example to hand the pump and
     * the sensor to another phone. While set, no pump command is sent, the loop does not run and the
     * sensor drivers are stopped. Kept across restarts until the user resumes. Device state, so never
     * exported: a stopped phone must not stop the phone that imports its settings.
     * 2026-11-25: added for the Dexcom ONE+ plugin (as in OpenApsAIMIios). Nothing in this tree sets it yet, so it stays false.
     */
    AapsStoppedByUser("aaps_stopped_by_user", false, exportable = false),
}
