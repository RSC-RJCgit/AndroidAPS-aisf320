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
 * they are back as soon as the finger is lifted. [GraphsSection] adds its graphs with [add]; the main screen draws the pill
 * of [current] with [GraphInspectPillOf]. Where no host is given (the History browser), the pill stays on the graph as before.
 */
@Stable
class TopBarPillHost {

    /**
     * The graphs on screen, newest last (2026-10-28). A list rather than one value: when the landscape toggle swaps the
     * wide and the split layout, the new graphs can be added before the old ones are removed. Each removes only its own
     * entry, so the order cannot leave the top bar with graphs that are gone.
     */
    private val pills = mutableStateListOf<Pill>()

    /**
     * The graphs that show a pill now, else the newest graphs. Data, not a stored composable (2026-10-29): the stored
     * composables of the old and the new graphs came from the same lambda, so when the toggle swapped them in one frame
     * Compose kept running the old one. The 29 Oct Windows log showed it: "drawn here=true" for every hold after the
     * toggle, and the top bar never measured a pill again until a rotation rebuilt the screen.
     */
    val current: Pill? get() = pills.lastOrNull { it.inspect.pill != null } ?: pills.lastOrNull()

    /** A summary opened by a chip stays above graph inspection until it is closed. */
    var summaryContent by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** How many graph sections are here, for the log */
    val size: Int get() = pills.size

    /** True when [pill] is the one that [current] gives, for the log */
    fun isDrawn(pill: Pill): Boolean = current === pill

    fun add(inspect: GraphInspectState, graphViewModel: GraphViewModel): Pill = Pill(inspect, graphViewModel).also { pills.add(it) }

    fun remove(pill: Pill) {
        pills.remove(pill)
    }

    /** One graph section. A plain class, so [remove] finds this entry and no other, and so the main screen can key on it. */
    class Pill(val inspect: GraphInspectState, val graphViewModel: GraphViewModel)
}

/** The host of the screen, or null where the pill stays on the graph. */
val LocalTopBarPillHost = staticCompositionLocalOf<TopBarPillHost?> { null }
