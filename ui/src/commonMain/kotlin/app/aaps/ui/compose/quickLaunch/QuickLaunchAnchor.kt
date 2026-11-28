package app.aaps.ui.compose.quickLaunch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where the top bar's search bar sits on screen, so the main screen can open the quick launch bar over its bottom half,
 * leaving the top half visible (2026-11-28, per explicit request; first level with the TT row). [searchBarMidInRoot] is the
 * search bar's vertical middle in root coordinates, null until it has been laid out.
 */
class QuickLaunchAnchor {

    var searchBarMidInRoot by mutableStateOf<Float?>(null)
}

/** Provided by the main screen around the top bar; null elsewhere, where nothing reports a position. */
val LocalQuickLaunchAnchor = staticCompositionLocalOf<QuickLaunchAnchor?> { null }
