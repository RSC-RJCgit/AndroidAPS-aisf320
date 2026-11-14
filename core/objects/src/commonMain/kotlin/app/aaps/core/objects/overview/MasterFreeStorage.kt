package app.aaps.core.objects.overview

import kotlin.concurrent.Volatile

/**
 * The master's free storage after the clock (2026-11-14, per explicit request; as v3 shows it since 2026-11-11). A v3
 * master sends its free storage in whole GB (1 GB = 1,000,000,000 bytes, as Android Settings shows it) as
 * [FIELD] beside masterTimeZone. A master shows its own.
 *
 * A client or virtual master shows the mirrored value (2026-11-15, per explicit request: it came and went too often).
 * - It is saved as text ([encoded], kept in StringNonKey.ClientMasterFreeStorage), so a restart does not lose it.
 * - It is shown for up to 24 hours, and after 30 minutes it is marked stale (the top bar greys it out).
 */
object MasterFreeStorage {

    const val FIELD = "masterFreeGB"
    private const val FRESH_MS = 30 * 60_000L
    private const val MAX_AGE_MS = 24 * 60 * 60_000L

    private class Mirrored(val timestamp: Long, val gb: Int)
    @Volatile private var mirrored: Mirrored? = null

    /** What the top bar shows: the GB, or null when none is known; [stale] when the mirrored value is over 30 min old. */
    data class Display(val gb: Int?, val stale: Boolean)

    /** Takes a newer mirrored value. True when it was newer, so the caller saves [encoded]. */
    fun remember(gb: Int, timestamp: Long, now: Long): Boolean {
        if (gb < 0 || timestamp <= 0L || timestamp > now + 5 * 60_000L) return false
        val previous = mirrored
        if (previous != null && timestamp <= previous.timestamp) return false
        mirrored = Mirrored(timestamp, gb)
        return true
    }

    /** "timestamp;GB" of the mirrored value, or "" when there is none. */
    fun encoded(): String = mirrored?.let { "${it.timestamp};${it.gb}" } ?: ""

    /** Restores a saved [encoded] value after a restart; a newer value already taken is kept. */
    fun restore(saved: String) {
        val parts = saved.split(';')
        if (parts.size != 2) return
        val timestamp = parts[0].toLongOrNull() ?: return
        val gb = parts[1].toIntOrNull() ?: return
        val previous = mirrored
        if (gb >= 0 && timestamp > 0L && (previous == null || timestamp > previous.timestamp)) mirrored = Mirrored(timestamp, gb)
    }

    fun display(now: Long, isMaster: Boolean): Display {
        val known = mirrored?.takeIf { now - it.timestamp in 0L..MAX_AGE_MS }
        if (known != null && now - known.timestamp <= FRESH_MS) return Display(known.gb, stale = false)
        if (isMaster) localFreeStorageGb()?.let { return Display(it, stale = false) }
        return Display(known?.gb, stale = known != null)
    }

    /** The clock text with the free storage after it, e.g. "14:32  48 GB"; just the time when none is known. */
    fun withFreeStorage(time: String, now: Long, isMaster: Boolean): String {
        val gb = display(now, isMaster).gb
        return if (gb == null) time else "$time  $gb GB"
    }
}

/** This device's free storage in whole GB, or null where it cannot be read. */
expect fun localFreeStorageGb(): Int?
