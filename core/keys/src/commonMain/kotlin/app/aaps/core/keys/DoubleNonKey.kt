package app.aaps.core.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

enum class DoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
) : DoubleNonPreferenceKey {

    /** AutoISF 3.2.1 activity monitor: the sensitivity ratio of the last loop. */
    ActivityMonitorRatio("activity_ratio", 1.0),

    /** AutoISF 3.2.1 Libre smoothing: the last calibrated and the last smoothed value, mg/dL. -1 means none yet. */
    FslLastRaw("fsl_last_raw", -1.0),
    FslLastSmooth("fsl_last_smooth", -1.0),

}