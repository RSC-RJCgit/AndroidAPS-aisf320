package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets the main screen draw the press-and-hold pill of the graphs over its top bar (2026-10-27, per explicit request).
 *
 * The top bar does not scroll, so the pill is always in view while a finger is down, also when the graphs are scrolled
 * up, and it no longer covers the top of the BG graph. Search and settings are not needed while a graph is held, and
 * they are back as soon as the finger is lifted. [GraphsSection] puts the pill into [content]; the main screen draws it.
 * Where no host is given (the History browser), the pill stays on the graph as before.
 */
@Stable
class TopBarPillHost {

    /** The pill, set by the graphs while they are on screen. It draws nothing while no graph is held. */
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** A summary opened by a chip stays above graph inspection until it is closed. */
    var summaryContent by mutableStateOf<(@Composable () -> Unit)?>(null)
}

/** The host of the screen, or null where the pill stays on the graph. */
val LocalTopBarPillHost = staticCompositionLocalOf<TopBarPillHost?> { null }
