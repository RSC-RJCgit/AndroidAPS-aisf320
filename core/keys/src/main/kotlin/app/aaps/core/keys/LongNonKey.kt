package app.aaps.core.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class LongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    LastDelayedBolusDeliveredAt("last_delayed_bolus_delivered_at", 0L, exportable = false),
    NsClientSimulatorBgReplayPauseUntil("nsclient_simulator_bg_replay_pause_until", 0L, exportable = false),
    // When replay switched from 48 to 72 hours (0 = still 48). Stays on 72 until replay is turned off.
    NsClientSimulatorBgReplayLongDelaySince("nsclient_simulator_bg_replay_long_delay_since", 0L, exportable = false),
    // Current shift in ms once switched (0 = 72 hours); shrinks when a gap is skipped, plus a day when nothing is ahead.
    NsClientSimulatorBgReplayLongDelayMs("nsclient_simulator_bg_replay_long_delay_ms", 0L, exportable = false),
    LastLoRebAppliedAt("last_loreb_applied_at", 0L, exportable = false),
    LastHiBrkTwilightTtAt("last_hibrk_twilight_tt_at", 0L, exportable = false),

    LocalProfileLastChange("local_profile_last_change", 0L),
    BtWatchdogLastBark("bt_watchdog_last", 0L),
    ActivePumpChangeTimestamp("active_pump_change_timestamp", 0L),
    LastCleanupRun("last_cleanup_run", 0L),
    LastWeeklyApkCleanupDay("last_weekly_apk_cleanup_day", 0L, exportable = false),
    LastCloudLogExport("last_cloud_log_export", 0L),
    LastCloudLogSuccessNote("last_cloud_log_success_note", 0L, exportable = false),
    LastAutoIsfHistoryExport("last_autoisf_history_export", 0L),
    MirroredAutoIsfSettingsTimestamp("mirrored_autoisf_settings_timestamp", 0L, exportable = false),
    // Internal bookkeeping for PersistentRiseTT57Release (OpenAPSAutoISFPlugin.kt) -- when the
    // "delta >= +0.10mmol && no SMB this cycle" condition first becomes continuously true, not a
    // user-facing setting. 0L means "not currently tracking a persistent rise".
    ApsAutoIsfPersistentRiseStartedAt("autoisf_persistent_rise_started_at", 0L, exportable = false),
    // When the newest raw Libre spike peaked (2026-10-29). Opens the 6 h raw-spike window: see findRawSpike in OpenAPSAutoISFPlugin.
    ApsAutoIsfRawSpikeAt("autoisf_raw_spike_at", 0L, exportable = false),
    // When the newest raw Libre dip (outside 22:30-07:30) bottomed (2026-10-29). Only opens the sensor-age slope tier for 6 h.
    ApsAutoIsfRawDipAt("autoisf_raw_dip_at", 0L, exportable = false),
}

