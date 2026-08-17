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
    LastCloudLogExport("last_cloud_log_export", 0L),
    LastCloudLogSuccessNote("last_cloud_log_success_note", 0L, exportable = false),
    LastAutoIsfHistoryExport("last_autoisf_history_export", 0L),
    MirroredAutoIsfSettingsTimestamp("mirrored_autoisf_settings_timestamp", 0L, exportable = false),
    // Internal bookkeeping for PersistentRiseTT57Release (OpenAPSAutoISFPlugin.kt) -- when the
    // "delta >= +0.10mmol && no SMB this cycle" condition first becomes continuously true, not a
    // user-facing setting. 0L means "not currently tracking a persistent rise".
    ApsAutoIsfPersistentRiseStartedAt("autoisf_persistent_rise_started_at", 0L, exportable = false),
}

