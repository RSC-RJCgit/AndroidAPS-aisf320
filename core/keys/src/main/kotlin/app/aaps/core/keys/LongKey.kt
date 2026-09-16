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
    // Last time we raised NSCLIENT_TOKEN_FAILED (24h gate). 0 = never, or cleared after a good auth.
    NsClientTokenFailNotifiedAt("nsclient_token_fail_notified_at", 0, defaultedBySM = true),
    SplitBolusBlockSmbUntil("split_bolus_block_smb_until", 0, defaultedBySM = true),
    DelayedBolusBlockSmbUntil("delayed_bolus_block_smb_until", 0, defaultedBySM = true),
    // Live RT.insulinReq (milliunits, x1000, since LongKey has no Double variant), refreshed every
    // cycle by OpenAPSAutoISFPlugin right alongside its own private lastCycleInsulinReq (see that
    // field's doc comment). DelayedBolusWorker lives in a lower module that can't see the plugin's
    // private field directly, so this preference is how it reads the loop's CURRENT insulin
    // requirement to cap the delayed dose -- added 2026-09-15 alongside removing the old iobDelta
    // term from DelayedBolusWorker's own calc (see that class's doc comment for the Db30 case that
    // motivated both changes).
    ApsAutoIsfLastCycleInsulinReqMilliU("autoisf_last_cycle_insulin_req_milliu", 0, defaultedBySM = true),
    // Live "how much is still pending" from BolusWizard's own carb-split and Warsaw-FPU protein/fat
    // series (milliunits, x1000), so DelayedBolusWorker -- in a different Gradle module, with no
    // visibility into BolusWizard's own in-process Handler-scheduled doses -- can show them in its own
    // check/cancel notes. Written/cleared by BolusWizard itself: split is the true live remainder
    // (scheduleReducedPartsSplitBolus's own decrementing remainingResidual, written on every entry,
    // cleared to 0 on every terminal branch); Warsaw is the combined protein+fat total, decremented by
    // each fpu-N sub-dose's own fixed amount as scheduleSingleDelayedDose resolves it (cancelled or
    // delivered) -- see both functions' own doc comments. Added 2026-09-16.
    ApsAutoIsfPendingSplitRemainingMilliU("autoisf_pending_split_remaining_milliu", 0, defaultedBySM = true),
    ApsAutoIsfPendingWarsawRemainingMilliU("autoisf_pending_warsaw_remaining_milliu", 0, defaultedBySM = true),
    // Internal-only: timestamp (ms) when the current sustained-high-BG episode (>10.0mmol) started, for
    // the "OldPod" notify-once check — 0 means no episode currently in progress. Reset to 0 the instant
    // BG drops back to <=10.0mmol, so this only ever measures an UNBROKEN stretch above the threshold.
    ApsAutoIsfOldPodHighSinceTs("autoisf_old_pod_high_since_ts", 0, defaultedBySM = true),
    // Internal-only, added 2026-09-13 for UnexplainedHighTierC (OpenAPSAutoISFPlugin.kt): timestamp (ms)
    // when the current sustained-high-BG episode (>8.0mmol) started -- separate from OldPod's own
    // ApsAutoIsfOldPodHighSinceTs above since the threshold differs (8.0mmol here vs 10.0mmol there) and
    // this one is pod-age-independent. 0 means no episode currently in progress. Reset to 0 the instant
    // BG drops back to <=8.0mmol, so this only ever measures an UNBROKEN stretch above the threshold. See
    // ApsAutoIsfUnexplainedHighMealSeen (BooleanKey.kt) for the paired "was there a meal/UAM during this
    // same episode" latch.
    ApsAutoIsfUnexplainedHighSinceTs("autoisf_unexplained_high_since_ts", 0, defaultedBySM = true),
    // ProfileBatchAuto UP: unbroken BGL > 12.0 mmol stretch start. 0 = not in a stretch.
    ApsAutoIsfBatchBgl12SinceTs("autoisf_batch_bgl12_since_ts", 0, defaultedBySM = true),
    // ProfileBatchAuto UP: unbroken ukfRaw BGL > 14.0 mmol stretch start. 0 = not in a stretch.
    ApsAutoIsfBatchUkf14SinceTs("autoisf_batch_ukf14_since_ts", 0, defaultedBySM = true),
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
    // Last Shizuku APK install start (auto or List2). Survives process death so a bounce
    // cannot auto-install again for 45 min. 0 = never.
    ApsAutoIsfApkAutoLastAt("autoisf_apk_auto_last_at", 0, defaultedBySM = true),
    // Newest pump-APK feature NNN seen on this loop phone (AAPS3 / AAPS333 / ApkDownload).
    // Mirrored to Client in the AutoISF settings snapshot so List2 can show it. 0 = unknown.
    ApsAutoIsfApkNewestNnn("autoisf_apk_newest_nnn", 0, defaultedBySM = true),

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

    // Added 2026-09-12: the exact TT.timestamp (creation time, ms) of the 5.0mmol TT BMild/
    // BMildFailsafe last successfully created, stashed by applyBMildOutcomeFactors right at creation.
    // Lets bmildOwnFiveTtActive() in OpenAPSAutoISFPlugin.kt prove the CURRENTLY active TT is literally
    // that same DB row (exact timestamp match) rather than inferring ownership from value+recency
    // coincidence -- a coincidence-based check would also exempt an unrelated 5.0mmol TT (e.g. a
    // manually-set one) that happened to appear in the same few minutes. 0 = none yet.
    ApsAutoIsfLastBmildTtCreatedAt("autoisf_last_bmild_tt_created_at", 0, defaultedBySM = true, exportable = false),
    // Timestamp (ms) of the most recent AlarmHypo1/AlarmHypo2 firing -- persisted (unlike the in-memory
    // lastRunTimestamps map) so a rolling "was there a genuine hypo alarm in the last 60 min" check
    // survives an app restart. Used by bmildBasicCriteriaMet() to raise mealLeftoverRise's BG floor
    // after a real alarm-tier hypo, not just any dip. 0 = never. Added 2026-09-16, per explicit request.
    ApsAutoIsfLastAlarmHypoAt("autoisf_last_alarm_hypo_at", 0, defaultedBySM = true),

}