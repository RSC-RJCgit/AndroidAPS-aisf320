package app.aaps.core.interfaces.configuration

import kotlinx.coroutines.flow.StateFlow

/**
 * True while heavy screen work may be skipped (2026-10-18). Set only by iOS: while the app runs in the background on the
 * silent-audio keep-alive, the per-reading calculation and the graph rebuilds are skipped, and done once when the app
 * comes back. Nightscout sync and the alarms keep running. Always false on Android and desktop.
 */
interface BackgroundWorkPause {

    val paused: StateFlow<Boolean>

    fun setPaused(paused: Boolean)
}
