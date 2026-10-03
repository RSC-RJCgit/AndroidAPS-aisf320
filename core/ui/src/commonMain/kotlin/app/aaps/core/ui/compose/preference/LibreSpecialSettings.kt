package app.aaps.core.ui.compose.preference

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.KeysStrings

/** Slope, offset, and smooth factor. xDrip and NSClient each show this screen. */
fun libreSpecialSettings(key: String): PreferenceSubScreenDef = PreferenceSubScreenDef(
    key = key,
    title = KeysStrings.libre_special_settings,
    summary = KeysStrings.libre_special_settings_summary,
    items = listOf(
        BooleanKey.FslApplySmoothing,
        DoubleKey.FslCalOffset,
        DoubleKey.FslCalSlope,
        DoubleKey.FslSmoothAlpha,
    )
)
