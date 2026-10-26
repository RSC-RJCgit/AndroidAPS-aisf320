package app.aaps.implementation.notifications

internal enum class HeartbeatReception { WAITING, RECEIVING, STALE, DISCONNECTED }

/** A connection or an old test result is not evidence of a current heartbeat. */
internal fun heartbeatReception(lastValueAt: Long, now: Long, connected: Boolean): HeartbeatReception = when {
    lastValueAt <= 0L || now < lastValueAt -> HeartbeatReception.WAITING
    !connected -> HeartbeatReception.DISCONNECTED
    now - lastValueAt >= 180_000L -> HeartbeatReception.STALE
    else -> HeartbeatReception.RECEIVING
}
