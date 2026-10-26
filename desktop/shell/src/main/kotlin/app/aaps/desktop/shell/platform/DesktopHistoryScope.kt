package app.aaps.desktop.shell.platform

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.desktop.shell.di.DesktopHistoryWindowGraph
import app.aaps.ui.compose.history.HistoryScope

/**
 * The calculation objects behind the History Browser on desktop, the counterpart of `HistoryBrowserData` and
 * `IosHistoryScope`.
 *
 * These are deliberately **not** the app's singletons. The History Browser recalculates over a different time range, and
 * sharing the running loop's objects would mean browsing history rewrites the state the loop is calculating on.
 *
 * This replaced a placeholder that threw on every property (2026-10-27, per explicit request), so the history screen
 * crashed the desktop app. [DesktopHistoryWindowGraph] is a graph extension with its own scope, so the window owns its own
 * calculator, cache, signals and overview data while still sharing the database and preferences with the app.
 */
class DesktopHistoryScope(window: DesktopHistoryWindowGraph) : HistoryScope {

    override val overviewData: OverviewData = window.overviewData
    override val signals: CalculationSignalsEmitter = window.signals
    override val cache: OverviewDataCache = window.cache
    override val iobCobCalculator: IobCobCalculator = window.iobCobCalculator

    /** Nothing to release. The window's objects are held only by this instance, so they go when it does. */
    override fun onDestroy() {
    }
}
