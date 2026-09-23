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

    // Epoch millis of the last AlarmHypo. Nothing writes this until that alarm is ported.
    ApsAutoIsfLastAlarmHypoAt("autoisf_last_alarm_hypo_at", 0L),

    // Epoch millis when the current rapid-SMB stack started. 0 means no stack. Local only.
    ApsAutoIsfSmbStackStart("autoisf_smb_stack_start", 0L, exportable = false),
}

