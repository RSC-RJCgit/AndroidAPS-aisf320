package app.aaps.core.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class LongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    LocalProfileLastChange("local_profile_last_change", 0L),

    BtWatchdogLastBark("bt_watchdog_last", 0L),
    ActivePumpChangeTimestamp("active_pump_change_timestamp", 0L),
    LastCleanupRun("last_cleanup_run", 0L),

    // When the six-hour AutoISF history files were last written. Local only.
    LastAutoIsfHistoryExport("last_autoisf_history_export", 0L, exportable = false),

    // When the six-hour cloud log upload last started. Local only.
    LastCloudLogExport("last_cloud_log_export", 0L, exportable = false),

    // NSCv3 client-control pairing (excluded from export — replay protection regresses if restored)
    NsClientControlCounterSent("nsclient_control_counter_sent", 0L, exportable = false),

    // When this client paired with master. Used by OrphanDetector to suppress false-positive
    // orphan signals on settings/aaps docs whose srvModified predates the pairing (master
    // hasn't republished the roster yet). Not exported — re-pair regenerates this.
    NsClientControlPairedAt("nsclient_control_paired_at", 0L, exportable = false),
    LastVacuumRun("last_vacuum_run", 0L),

    // Epoch millis of the last AlarmHypo1 or AlarmHypo2. 0 means none yet.
    ApsAutoIsfLastAlarmHypoAt("autoisf_last_alarm_hypo_at", 0L),

    // Epoch millis when the current rapid-SMB stack started. 0 means no stack. Local only.
    ApsAutoIsfSmbStackStart("autoisf_smb_stack_start", 0L, exportable = false),

    // Epoch millis until an overnight duration rescue must not start another one. Local only.
    ApsAutoIsfOvernightRescueUntil("autoisf_overnight_rescue_until", 0L, exportable = false),

    // Epoch millis when a mild rise with no SMB started. 0 means it is not holding. Local only.
    ApsAutoIsfPersistentRiseStartedAt("autoisf_persistent_rise_started_at", 0L, exportable = false),

    // Epoch millis of the temp target HiBrkTwilight wrote. 0 means none. Local only.
    ApsAutoIsfHiBrkTwilightTtAt("autoisf_hibrk_twilight_tt_at", 0L, exportable = false),

    // Epoch millis when glucose first stayed over 8.0 mmol with no meal yet. 0 means not holding. Local only.
    ApsAutoIsfUnexplainedHighSince("autoisf_unexplained_high_since", 0L, exportable = false),

    // Epoch millis when glucose first stayed over 12.0 mmol. 0 means not holding. Local only.
    ApsAutoIsfBatchBgl12Since("autoisf_batch_bgl12_since_ts", 0L, exportable = false),

    // Epoch millis when UKF raw first stayed over 14.0 mmol. 0 means not holding. Local only.
    ApsAutoIsfBatchUkf14Since("autoisf_batch_ukf14_since_ts", 0L, exportable = false),

    // Epoch millis of the 5.0 mmol hold the mild boost just started. 0 means none. Local only.
    ApsAutoIsfLastBmildTtAt("autoisf_last_bmild_tt_at", 0L, exportable = false),

    // Newest UKF raw over 12.0 mmol. 0 means none seen. Local only.
    ApsAutoIsfLibreOver12Ts("autoisf_libre_over_12_ts", 0L, exportable = false),
    // When an old pod's high glucose started. 0 means not high. Local only.
    ApsAutoIsfOldPodHighSinceTs("autoisf_old_pod_high_since_ts", 0L, exportable = false),

    // Server-modified time of the last secondary Nightscout page. 0 means the first download
    // still has to walk back 16 days. Local only: restoring it would skip treatments.
    NsClientSecondaryLastModified("nsclient_secondary_last_modified", 0L, exportable = false),

    // Server time of the profile store last taken from the secondary Nightscout.
    NsClientSecondaryProfileModified("nsclient_secondary_profile_modified", 0L, exportable = false),

    // Profile-switch time already used as a Set-role duration. Local only.
    ApsAutoIsfSetRoleDurationHandledAt("autoisf_set_role_duration_handled_at", 0L, exportable = false),

    // Time of the last LibreSpecial step. -1 means none yet.
    FslSmoothLastTimeRaw("fsl_last_time_raw", -1L),
}

