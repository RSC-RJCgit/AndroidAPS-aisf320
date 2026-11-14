package app.aaps.core.objects.overview

import kotlin.concurrent.Volatile

/**
 * The master's free storage after the clock (2026-11-14, per explicit request; as v3 shows it since 2026-11-11). A v3
 * master sends its free storage in whole GB (1 GB = 1,000,000,000 bytes, as Android Settings shows it) as
 * [FIELD] beside masterTimeZone. A client or virtual master shows the mirrored value while it is under 30 min old;
 * a master shows its own.
 */
object MasterFreeStorage {

    const val FIELD = "masterFreeGB"
    private const val MAX_AGE_MS = 30 * 60_000L

    private class Mirrored(val timestamp: Long, val gb: Int)
    @Volatile private var mirrored: Mirrored? = null

    fun remember(gb: Int, timestamp: Long, now: Long) {
        if (gb < 0 || timestamp <= 0L || timestamp > now + 5 * 60_000L) return
        val previous = mirrored
        if (previous == null || timestamp > previous.timestamp) mirrored = Mirrored(timestamp, gb)
    }

    /** The clock text with the free storage after it, e.g. "14:32  48 GB"; just the time when none is known. */
    fun withFreeStorage(time: String, now: Long, isMaster: Boolean): String {
        val fresh = mirrored?.takeIf { now - it.timestamp in 0L..MAX_AGE_MS }?.gb
        val gb = fresh ?: if (isMaster) localFreeStorageGb() else null
        return if (gb == null) time else "$time  $gb GB"
    }
}

/** This device's free storage in whole GB, or null where it cannot be read. */
expect fun localFreeStorageGb(): Int?
