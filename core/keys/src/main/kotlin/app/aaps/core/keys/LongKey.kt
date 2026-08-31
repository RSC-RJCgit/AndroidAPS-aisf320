package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.LongPreferenceKey

enum class LongKey(
    override val key: String,
    override val defaultValue: Long,
    override val min: Long = Long.MIN_VALUE,
    override val max: Long = Long.MAX_VALUE,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : LongPreferenceKey {

    FslSmoothLastTimeRaw("fsl_last_time_raw", -1, -1, defaultedBySM = true),
    FslCalibrationStart("fsl_cal_start_time", -1, -1, defaultedBySM = true),
    AppStart("app_start_time", 0, defaultedBySM = true),
    NsClientSecondaryLastLoaded("nsclient_secondary_last_loaded", 0, defaultedBySM = true),
    // Server-modified cursor for the secondary-NS treatment downloader. Separate from the legacy
    // event-time cursor above so the first upgraded run performs a recovery scan for backdated entries.
    NsClientSecondaryLastModified("nsclient_secondary_last_modified", 0, defaultedBySM = true),
    SplitBolusBlockSmbUntil("split_bolus_block_smb_until", 0, defaultedBySM = true),
    DelayedBolusBlockSmbUntil("delayed_bolus_block_smb_until", 0, defaultedBySM = true),
    // Internal-only: timestamp (ms) when the current sustained-high-BG episode (>10.0mmol) started, for
    // the "OldPod" notify-once check — 0 means no episode currently in progress. Reset to 0 the instant
    // BG drops back to <=10.0mmol, so this only ever measures an UNBROKEN stretch above the threshold.
    ApsAutoIsfOldPodHighSinceTs("autoisf_old_pod_high_since_ts", 0, defaultedBySM = true),
    // Virtual-Pump phone cursor for the newest StLow/legacy StorageLow Note already alerted. A later NS Note has a
    // new timestamp and is handled once, including across app restarts.
    ApsAutoIsfLowStorageNsNoteHandledAt("autoisf_low_storage_ns_note_handled_at", 0, defaultedBySM = true),
    // Secondary-NS server revision of the newest ADesk command, and the newest revision already sent
    // to Tasker on the real-pump phone. Separate values make the hand-off persistent across restarts.
    ApsAutoIsfAnyDeskSecondaryCommandAt("autoisf_anydesk_secondary_command_at", 0, defaultedBySM = true),
    ApsAutoIsfAnyDeskTaskerHandledAt("autoisf_anydesk_tasker_handled_at", 0, defaultedBySM = true),
    // Equal to SecondaryCommandAt only while an explicit on-device List2 local test is pending.
    // This lets the common receiving handler distinguish that test from a real secondary-NS command
    // after a restart, and therefore write an unambiguous route-specific receipt Note.
    ApsAutoIsfAnyDeskLocalCommandAt("autoisf_anydesk_local_command_at", 0, defaultedBySM = true),

    // Internal-only: timestamp (ms) when the current SMB anti-stacking window started (see the
    // smbInt5Sec <=70s trim in DetermineBasalAutoISF.kt) — 0 means no active stack. Reset to 0 the
    // instant stacking stops (avg gap rises above 70s), so a later rapid-fire sequence always starts a
    // genuinely fresh window rather than inheriting a stale timer from an already-ended stack.
    ApsAutoIsfSmbStackStart("autoisf_smb_stack_start_ts", 0, defaultedBySM = true),
    // Internal-only: timestamp (ms) raw Libre BGL was last observed over 12.0mmol -- 0 means never (or
    // not since this was added). Used to gate OldSensorAdj (the sensor-age slope/offset compensation):
    // if BGL hasn't exceeded 12.0 in the last 24h, that adjustment is blocked and Libre slope/offset
    // revert to baseline, on the reasoning that the compensation is calibrated against genuine high-BGL
    // Libre-vs-reference divergence, which this checks is actually still occurring recently.
    ApsAutoIsfLibreOver12Ts("autoisf_libre_over_12_ts", 0, defaultedBySM = true),
    // Internal-only: timestamp (ms) until which OvernightDuraRescue's temporary Standard-profile switch
    // should be respected -- OffHighProf/MJrecentCurrProfAcce/NightAcce's switch-to-Low actions check this
    // and yield while it's in the future. 0/past means no rescue active; no explicit clear needed, it just
    // stops mattering once passed.
    ApsAutoIsfOvernightRescueUntil("autoisf_overnight_rescue_until", 0, defaultedBySM = true),

    // Cursors for the two channels that carry a coded-profile-ROLE assignment made in the
    // ProfileSwitchDialog on another device (notably the AAPSCLIENT follower, whose local
    // preferences.put there is inert since it never runs the loop) to the loop phone -- see the two
    // receiver blocks in OpenAPSAutoISFPlugin.invoke():
    //  - ...NoteHandledAt: timestamp of the newest "SetRole <prefKey>=<profile>" careportal Note
    //    already applied. Arrives via the secondary-NS allowlist (LoadSecondaryBolusCarbsWorker) --
    //    slow (~40-70 min) but an independent channel.
    //  - ...DurationHandledAt: timestamp of the newest ProfileSwitch whose coded duration (51-57 min
    //    at 100%) already applied. Rides the normal profile-switch sync -- fast.
    // A later record has a newer timestamp and is applied once, across restarts.
    ApsAutoIsfSetRoleNoteHandledAt("autoisf_set_role_note_handled_at", 0, defaultedBySM = true),
    ApsAutoIsfSetRoleDurationHandledAt("autoisf_set_role_duration_handled_at", 0, defaultedBySM = true),

}