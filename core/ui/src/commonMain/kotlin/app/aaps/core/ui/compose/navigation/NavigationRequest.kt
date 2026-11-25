package app.aaps.core.ui.compose.navigation

import androidx.compose.runtime.compositionLocalOf
import app.aaps.core.interfaces.navigation.ElementType

sealed class NavigationRequest {
    data class Element(val type: ElementType) : NavigationRequest()
    data class QuickWizard(val guid: String) : NavigationRequest()
    data class Plugin(val className: String) : NavigationRequest()
    /** [composeScreenKey] opens that sub-screen of the plugin's settings at once (2026-11-25, as in OpenApsAIMIios). */
    data class PluginPreferences(val pluginKey: String, val composeScreenKey: String? = null) : NavigationRequest()

    /** Statistics opened on its Glucose tab for [days] (7 or 30), from the overview stats summary (2026-10-29) */
    data class StatsGlucose(val days: Int) : NavigationRequest()
}

/**
 * CompositionLocal for dispatching a [NavigationRequest] from within a plugin's compose content
 * (or any nested screen) up to whichever host owns the NavController. Provided by
 * `PluginContentRoute` in the app module — defaults to a no-op so screens used outside that
 * route (previews, tests) don't crash.
 */
val LocalPluginNavigationRequest = compositionLocalOf<(NavigationRequest) -> Unit> { { } }
