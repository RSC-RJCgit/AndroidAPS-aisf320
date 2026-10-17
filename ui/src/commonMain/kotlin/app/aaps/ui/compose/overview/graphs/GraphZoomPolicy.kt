package app.aaps.ui.compose.overview.graphs

/** Wide graph windows use a temporary clean view. The saved display settings stay unchanged. */
internal fun useCleanGraphForWindow(visibleMinutes: Double?, defaultMinutes: Double): Boolean =
    visibleMinutes != null && visibleMinutes.isFinite() &&
        visibleMinutes > maxOf(180.0, defaultMinutes) + 0.01

/**
 * A phone shows the default 3 hours in about 360 dp, so about 2 dp for each minute. Below that the SMB numbers
 * and arrows run into each other.
 */
internal const val CLEAN_GRAPH_MIN_DP_PER_MINUTE = 2.0

/**
 * Desktop: a wide window has room for more hours, so the limit is the space for each minute, not 3 hours.
 * Never clean at 3 hours or less, the same as on a phone.
 */
internal fun useCleanGraphForDesktopWindow(visibleMinutes: Double?, defaultMinutes: Double, widthDp: Double): Boolean =
    visibleMinutes != null && useCleanGraphForWindow(visibleMinutes, defaultMinutes) &&
        widthDp / visibleMinutes < CLEAN_GRAPH_MIN_DP_PER_MINUTE
