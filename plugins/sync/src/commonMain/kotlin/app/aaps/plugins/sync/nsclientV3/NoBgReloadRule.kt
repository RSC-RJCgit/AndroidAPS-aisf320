package app.aaps.plugins.sync.nsclientV3

import kotlin.math.min

// Used by IosNoBgReload (iOS, 2026-10-18).

/** No new BG for this long starts the first reload from Nightscout. */
internal const val NO_BG_RELOAD_AFTER_MS = 2 * 60_000L

/** The wait between reloads doubles after each reload that brings no new BG, up to this. */
internal const val NO_BG_RELOAD_MAX_WAIT_MS = 15 * 60_000L

/**
 * Decides when to reload from Nightscout because no new BG has come (2026-10-19, battery).
 *
 * The time counts from when the newest BG *arrived* on this phone, not from the time stamped on it. A client can get
 * its readings some minutes late (for example through the secondary Nightscout); measured from their own time, the
 * old rule found "no BG for 2 minutes" almost every check and reloaded every minute all night.
 *
 * One reload after [NO_BG_RELOAD_AFTER_MS]. If that brings no new BG, the next waits twice as long (2, 4, 8, 15, 15 ...
 * minutes). A new BG starts again from 2 minutes.
 */
internal class NoBgReloadRule {

    private var newestBgAt: Long? = null
    private var arrivedAt = 0L
    private var lastReloadAt = 0L
    private var waitMs = NO_BG_RELOAD_AFTER_MS

    /** Called on every check with the time of the newest stored BG. True when a reload should start now. */
    fun reloadDue(newestBg: Long?, now: Long): Boolean {
        if (newestBg == null) return false
        if (newestBg != newestBgAt) {
            newestBgAt = newestBg
            arrivedAt = now
            lastReloadAt = 0L
            waitMs = NO_BG_RELOAD_AFTER_MS
            return false
        }
        if (now - maxOf(arrivedAt, lastReloadAt) < waitMs) return false
        lastReloadAt = now
        waitMs = min(waitMs * 2, NO_BG_RELOAD_MAX_WAIT_MS)
        return true
    }
}
