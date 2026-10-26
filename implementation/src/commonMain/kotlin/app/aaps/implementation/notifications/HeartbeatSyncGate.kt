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
        const val INTERVAL_MS = 5 * 60_000L
    }
}
