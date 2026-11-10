package app.aaps.ui.compose.overview.graphs

/** Wide graph windows use a temporary clean view. The saved display settings stay unchanged. */
internal fun useCleanGraphForWindow(visibleMinutes: Double?, defaultMinutes: Double): Boolean =
    visibleMinutes != null && visibleMinutes.isFinite() &&
        visibleMinutes > maxOf(180.0, defaultMinutes) + 0.01

/**
 * Phones (2026-10-19, per explicit request): the clean graph only when the view is crowded. Portrait: more than 3 hours
 * shown (2026-11-09, per explicit request; was 6 hours), or more than 40 SMBs in view. Landscape has about twice the width: more than 12 hours, or more than 80 SMBs.
 * Split layout uses the portrait time limit because the graph only has one column.
 */
internal fun useCleanGraphForPhone(visibleMinutes: Double?, smbsInView: Int, landscape: Boolean, splitLayout: Boolean = false): Boolean {
    if (visibleMinutes == null || !visibleMinutes.isFinite()) return false
    val maxMinutes = if (landscape && !splitLayout) PHONE_LANDSCAPE_CLEAN_MINUTES else PHONE_PORTRAIT_CLEAN_MINUTES
    val maxSmbs = if (landscape) PHONE_LANDSCAPE_CLEAN_SMBS else PHONE_PORTRAIT_CLEAN_SMBS
    return visibleMinutes > maxMinutes + 0.01 || smbsInView > maxSmbs
}

internal const val PHONE_PORTRAIT_CLEAN_MINUTES = 3 * 60.0
internal const val PHONE_PORTRAIT_CLEAN_SMBS = 40
internal const val PHONE_LANDSCAPE_CLEAN_MINUTES = 12 * 60.0
internal const val PHONE_LANDSCAPE_CLEAN_SMBS = 80

/**
 * A phone shows the default 3 hours in about 360 dp, so about 2 dp for each minute. Below that the SMB numbers
 * and arrows run into each other.
 */
internal const val CLEAN_GRAPH_MIN_DP_PER_MINUTE = 2.0

/**
 * Desktop: a wide window has room for more hours, so the limit is the space for each minute, not 3 hours.
 * Split layout uses the portrait time limit regardless of window width.
 */
internal fun useCleanGraphForDesktopWindow(visibleMinutes: Double?, defaultMinutes: Double, widthDp: Double, splitLayout: Boolean = false): Boolean =
    if (splitLayout) useCleanGraphForWindow(visibleMinutes, PHONE_PORTRAIT_CLEAN_MINUTES)
    else visibleMinutes != null && useCleanGraphForWindow(visibleMinutes, defaultMinutes) &&
        widthDp / visibleMinutes < CLEAN_GRAPH_MIN_DP_PER_MINUTE
