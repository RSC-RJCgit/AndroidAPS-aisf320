package app.aaps.desktop.shell.di

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.workflow.CalculationSignalsEmitter
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.workflow.CalculationSignalsImpl
import app.aaps.implementation.overview.OverviewDataImpl
import app.aaps.plugins.main.iob.iobCobCalculator.IobCobCalculatorPlugin
import app.aaps.ui.compose.overview.OverviewDataCacheFactory
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Marks objects belonging to one history browsing window rather than to the running app.
 *
 * Its own scope, and that is the entire point - see [DesktopHistoryWindowGraph].
 */
abstract class DesktopHistoryWindowScope private constructor()

/**
 * One history browsing window's calculation objects, the desktop counterpart of `HistoryWindowGraph`.
 *
 * A copy of `IosHistoryWindowGraph` (2026-10-27, per explicit request): the two shells share no module that could hold
 * one copy without a new module dependency. A change to one should be made in the other too.
 *
 * The History Browser recalculates over a **different time range** than the running loop. Every
 * object below is therefore the window's own: its own `OverviewData`, its own signals, its own cache
 * and its own calculator. Handing back the app-scoped ones would satisfy every type and silently
 * make history browsing rewrite the state the loop is calculating on - which is the bug this whole
 * arrangement exists to prevent, and the reason the desktop placeholder that came before this refused to
 * answer at all rather than share them.
 *
 * A `@GraphExtension` rather than a root graph of its own: it inherits the app-wide objects - the
 * database, preferences, the logger - while everything marked `@SingleIn(DesktopHistoryWindowScope)` is
 * fresh per window. A root graph would share nothing, so every leaf would have to be threaded in by
 * hand.
 *
 * The cache and the calculator need each other; a function type defers the lookup so the graph
 * accepts what a direct reference would reject.
 */
@GraphExtension(DesktopHistoryWindowScope::class)
interface DesktopHistoryWindowGraph {

    val overviewData: OverviewData
    val signals: CalculationSignalsEmitter
    val cache: OverviewDataCache
    val iobCobCalculator: IobCobCalculator

    /** Held by the app graph, so a window can be opened whenever the user picks a day. */
    @GraphExtension.Factory
    fun interface Factory {

        fun create(): DesktopHistoryWindowGraph
    }

    @Provides
    @SingleIn(DesktopHistoryWindowScope::class)
    fun provideOverviewData(): OverviewData = OverviewDataImpl()

    @Provides
    @SingleIn(DesktopHistoryWindowScope::class)
    fun provideSignals(): CalculationSignalsEmitter = CalculationSignalsImpl()

    /**
     * `observeDatabase = false`: a history window reads a fixed range. Following live database
     * changes is the running loop's job, and a window that did so would recalculate under the user
     * while they were reading it.
     */
    @Provides
    @SingleIn(DesktopHistoryWindowScope::class)
    fun provideCache(
        factory: OverviewDataCacheFactory,
        signals: CalculationSignalsEmitter,
        iobCobCalculator: () -> IobCobCalculator
    ): OverviewDataCache = factory.create(
        iobCobCalculatorProvider = { iobCobCalculator() },
        signals = signals,
        observeDatabase = false
    )

    @Suppress("LongParameterList")
    @Provides
    @SingleIn(DesktopHistoryWindowScope::class)
    fun provideIobCobCalculator(
        aapsLogger: AAPSLogger,
        rxBus: RxBus,
        preferences: Preferences,
        rh: TextResolver,
        profileFunction: ProfileFunction,
        activePlugin: ActivePlugin,
        dateUtil: DateUtil,
        persistenceLayer: PersistenceLayer,
        overviewData: OverviewData,
        calculationWorkflow: CalculationWorkflow,
        decimalFormatter: DecimalFormatter,
        processedTbrEbData: ProcessedTbrEbData,
        signals: CalculationSignalsEmitter,
        notificationManager: NotificationManager,
        cache: () -> OverviewDataCache
    ): IobCobCalculator = IobCobCalculatorPlugin(
        aapsLogger, rxBus, preferences, rh, profileFunction, activePlugin, dateUtil, persistenceLayer,
        overviewData, calculationWorkflow, decimalFormatter, processedTbrEbData, signals, notificationManager
    ) { cache() }
}
