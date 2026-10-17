package app.aaps.plugins.sync.nsclientV3

// Used by IosNoBgReload (iOS, 2026-10-18).

/** No new BG for this long starts a reload from Nightscout. */
internal const val NO_BG_RELOAD_AFTER_MS = 2 * 60_000L

/** At most one reload in this time. */
internal const val NO_BG_RELOAD_MIN_GAP_MS = 60_000L

/** True when no BG has come for [NO_BG_RELOAD_AFTER_MS] and the last reload was at least [NO_BG_RELOAD_MIN_GAP_MS] ago. */
internal fun noBgReloadDue(lastBgAt: Long?, lastReloadAt: Long, now: Long): Boolean =
    lastBgAt != null && now - lastBgAt >= NO_BG_RELOAD_AFTER_MS && now - lastReloadAt >= NO_BG_RELOAD_MIN_GAP_MS
