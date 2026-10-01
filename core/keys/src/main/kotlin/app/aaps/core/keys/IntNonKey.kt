package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class IntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntNonPreferenceKey {

    ObjectivesManualEnacts("ObjectivesmanualEnacts", 0),
    RangeToDisplay("rangetodisplay", 6),
    // WiFi-drop recovery (OpenAPSAutoISFPlugin.kt's checkAndAttemptWifiRecovery) -- how many
    // Shizuku-driven WiFi off/on attempts have fired for the current stale-BG episode. Resets to 0
    // once a fresh BG reading arrives; capped at 3 (stops attempting entirely past that).
    WifiRecoverAttemptCount("wifi_recover_attempt_count", 0, exportable = false)
}