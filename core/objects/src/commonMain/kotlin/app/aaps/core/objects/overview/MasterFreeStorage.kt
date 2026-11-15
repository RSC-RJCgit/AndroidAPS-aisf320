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

    /** Where the display's changes are logged (2026-11-15, per explicit request); set by the NS device status handler. */
    @Volatile var log: ((String) -> Unit)? = null
    @Volatile private var lastLogged: String? = null

    fun display(now: Long, isMaster: Boolean): Display {
        val value = mirrored
        // A phone clock a little ahead makes a fresh value's age negative; up to 5 min counts as 0 (2026-11-15)
        val age = value?.let { (now - it.timestamp).let { a -> if (a in -CLOCK_TOLERANCE_MS until 0L) 0L else a } }
        val known = value?.takeIf { age != null && age in 0L..MAX_AGE_MS }
        val fresh = known != null && age!! <= FRESH_MS
        val local = if (!fresh && isMaster) localFreeStorageGb() else null
        val shown = when {
            fresh         -> Display(known!!.gb, stale = false)
            local != null -> Display(local, stale = false)
            else          -> Display(known?.gb, stale = known != null)
        }
        val state = when {
            shown.gb == null -> "missing (mirrored ${if (value == null) "none" else "${value.gb} GB, age ${age?.div(1000)} s"})"
            shown.stale      -> "greyed, ${shown.gb} GB, age ${age?.div(1000)} s"
            fresh            -> "shown, ${shown.gb} GB from the master"
            else             -> "shown, ${shown.gb} GB of this phone"
        }
        // Only the change from shown / greyed / missing (and the GB) is logged, not the age, so the log gets one line per change
        val key = state.substringBefore(" (").substringBefore(", age")
        if (key != lastLogged) {
            lastLogged = key
            log?.invoke("Master free storage display: $state")
        }
        return shown
    }

    private const val CLOCK_TOLERANCE_MS = 5 * 60_000L

    /** The clock text with the free storage after it, e.g. "14:32  48 GB"; just the time when none is known. */
    fun withFreeStorage(time: String, now: Long, isMaster: Boolean): String {
        val gb = display(now, isMaster).gb
        return if (gb == null) time else "$time  $gb GB"
    }
}

/** This device's free storage in whole GB, or null where it cannot be read. */
expect fun localFreeStorageGb(): Int?
