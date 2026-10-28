package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets the main screen draw the press-and-hold pill of the graphs over its top bar (2026-10-27, per explicit request).
 *
 * The top bar does not scroll, so the pill is always in view while a finger is down, also when the graphs are scrolled
 * up, and it no longer covers the top of the BG graph. Search and settings are not needed while a graph is held, and
 * they are back as soon as the finger is lifted. [GraphsSection] adds its pill with [add]; the main screen draws [content].
 * Where no host is given (the History browser), the pill stays on the graph as before.
 */
@Stable
class TopBarPillHost {

    /**
     * The pills of the graphs on screen, newest last (2026-10-28). A list rather than one value: when the landscape
     * toggle swaps the wide and the split layout, the new graphs can be added before the old ones are removed. Each
     * removes only its own entry, so the order cannot leave the top bar drawing the pill of graphs that are gone.
     */
    private val pills = mutableStateListOf<Pill>()

    /** The pill of the newest graphs. It draws nothing while no graph is held. */
    val content: (@Composable () -> Unit)? get() = pills.lastOrNull()?.draw

    /** A summary opened by a chip stays above graph inspection until it is closed. */
    var summaryContent by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** How many graph sections have a pill here, for the log */
    val size: Int get() = pills.size

    fun add(draw: @Composable () -> Unit): Pill = Pill(draw).also { pills.add(it) }

    fun remove(pill: Pill) {
        pills.remove(pill)
    }

    /** One graph section's pill. A plain class, so [remove] finds this entry and no other. */
    class Pill(val draw: @Composable () -> Unit)
}

/** The host of the screen, or null where the pill stays on the graph. */
val LocalTopBarPillHost = staticCompositionLocalOf<TopBarPillHost?> { null }
