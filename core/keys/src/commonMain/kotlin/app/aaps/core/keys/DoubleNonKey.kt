package app.aaps.core.keys

import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey

enum class DoubleNonKey(
    override val key: String,
    override val defaultValue: Double,
) : DoubleNonPreferenceKey {

    // Libre slope and offset. Nothing in this app reads them yet. Local copies of the 3.2.1 keys.
    ApsAutoIsfLibreSlopeOrig("autoisf_libre_slope_orig", 0.72),
    ApsAutoIsfLibreOffsetOrig("autoisf_libre_offset_orig", 1.4),
    FslCalSlope("fslCal_Slope", 1.0),
    FslCalOffset("fslCal_Offset", 0.0),
    ApsAutoIsfFslCalSlopeNormal("autoisf_fslcal_slope_normal", 1.0),
    ApsAutoIsfFslCalOffsetNormal("autoisf_fslcal_offset_normal", 0.0),
}