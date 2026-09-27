package app.aaps.core.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

enum class DoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
) : DoubleNonPreferenceKey {

    // Saved Libre bases. Sensor-age code copies these onto the live slope and offset.
    ApsAutoIsfLibreSlopeOrig("autoisf_libre_slope_orig", 0.72),
    ApsAutoIsfLibreOffsetOrig("autoisf_libre_offset_orig", 1.4),
    FslLastSmooth("fsl_last_smooth", -1.0),
    ApsAutoIsfFslCalSlopeNormal("autoisf_fslcal_slope_normal", 1.0),
    ApsAutoIsfFslCalOffsetNormal("autoisf_fslcal_offset_normal", 0.0),
}