package app.aaps.ui.compose.overview.graphs

/** Wide graph windows use a temporary clean view. The saved display settings stay unchanged. */
internal fun useCleanGraphForWindow(visibleMinutes: Double?, defaultMinutes: Double): Boolean =
    visibleMinutes != null && visibleMinutes.isFinite() &&
        visibleMinutes > maxOf(180.0, defaultMinutes) + 0.01
