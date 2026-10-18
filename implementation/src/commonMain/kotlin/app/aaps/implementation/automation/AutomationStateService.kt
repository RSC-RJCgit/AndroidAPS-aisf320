package app.aaps.implementation.automation

import app.aaps.core.interfaces.automation.AutomationStateInterface
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The app's automation states, stored in the preferences. A screen can follow changes by observing
 * `StringNonKey.AutomationCurrentStates`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AutomationStateService(
    preferences: Preferences,
) : AutomationStateInterface by AutomationStateStore(
    read = { key -> preferences.get(key) },
    save = { key, value -> preferences.put(key, value) },
)
