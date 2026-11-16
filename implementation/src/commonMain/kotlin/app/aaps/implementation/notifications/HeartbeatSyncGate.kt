package app.aaps.implementation.notifications

/** Shared by the primary and backup heartbeat; receiving a tick need not start another sync. */
internal class HeartbeatSyncGate {
    var lastRequestAt: Long? = null
        private set

    fun shouldRequestSync(now: Long): Boolean {
        val previous = lastRequestAt
        if (previous != null && now >= previous && now - previous < INTERVAL_MS) return false
        lastRequestAt = now
        return true
    }

    companion object {
        // Once per RileyLink tick, which comes about every minute (2026-11-16, per explicit request; was 5 minutes). The
        // master sends a reading every minute, and the Live Activity, watch and CarPlay showed each one up to 5 minutes late.
        // 50 s, not 60 s, so a tick that comes a few seconds early is not skipped; a second source in the same tick still is.
        const val INTERVAL_MS = 50_000L
    }
}
