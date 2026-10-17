package app.aaps.plugins.configuration.setupwizard.elements

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.protection.PasswordCheck
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.preference.AdaptiveSwitchPreferenceItem
import app.aaps.core.ui.compose.preference.rememberPreferenceBooleanState
import dev.zacsweers.metro.Inject

/**
 * An on/off setting in the setup wizard (2026-10-16). A change refreshes the wizard at once, so items whose
 * visibility depends on this setting show or hide without leaving the step.
 */
@Inject
class SWSwitch(aapsLogger: AAPSLogger, rh: TextResolver, rxBus: RxBus, preferences: Preferences, passwordCheck: PasswordCheck) : SWItem(aapsLogger, rh, rxBus, preferences, passwordCheck) {

    fun preference(preference: BooleanPreferenceKey): SWSwitch {
        this.preference = preference
        return this
    }

    @Composable
    override fun Compose() {
        val key = preference as BooleanPreferenceKey
        AdaptiveSwitchPreferenceItem(booleanKey = key, title = label)
        val state = rememberPreferenceBooleanState(key)
        LaunchedEffect(state.value) { scheduleChange(0) }
    }
}
