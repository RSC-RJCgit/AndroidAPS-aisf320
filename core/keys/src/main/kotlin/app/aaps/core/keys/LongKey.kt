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
    SplitBolusBlockSmbUntil("split_bolus_block_smb_until", 0, defaultedBySM = true),
    DelayedBolusBlockSmbUntil("delayed_bolus_block_smb_until", 0, defaultedBySM = true),
    // Internal-only: timestamp (ms) when the current sustained-high-BG episode (>10.0mmol) started, for
    // the "OldPod" notify-once check — 0 means no episode currently in progress. Reset to 0 the instant
    // BG drops back to <=10.0mmol, so this only ever measures an UNBROKEN stretch above the threshold.
    ApsAutoIsfOldPodHighSinceTs("autoisf_old_pod_high_since_ts", 0, defaultedBySM = true),

}