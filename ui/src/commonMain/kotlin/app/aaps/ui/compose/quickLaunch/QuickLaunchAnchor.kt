package app.aaps.ui.compose.quickLaunch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where the overview's temp target row (TT and TBR chips, level with the delta in the BG circle) sits on screen, so the
 * main screen can open the quick launch bar level with it (2026-11-28, per explicit request). [rowTopInRoot] is the row's
 * top in root coordinates, null until the row has been laid out.
 */
class QuickLaunchAnchor {

    var rowTopInRoot by mutableStateOf<Float?>(null)
}

/** Provided by the main screen around the overview; null elsewhere, where nothing reports a position. */
val LocalQuickLaunchAnchor = staticCompositionLocalOf<QuickLaunchAnchor?> { null }
