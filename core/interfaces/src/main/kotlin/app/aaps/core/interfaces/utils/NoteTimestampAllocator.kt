package app.aaps.core.interfaces.utils

/**
 * Hands out strictly increasing, unique millisecond timestamps for CarePortal notes
 * (`TE.Type.NOTE`), to work around `insertPumpTherapyEventIfNewByTimestamp`'s dedup check — which keys
 * purely on `(type, timestamp)`, not note text (see `InsertIfNewByTimestampTherapyEventTransaction`).
 * Two different notes created within the same processing cycle can land on the exact same millisecond
 * (many sequential, synchronous checks with no I/O between most of them), silently dropping whichever
 * one loses the race — found as "already exists," never inserted, no error logged. Routing every note
 * timestamp through [next] instead of a bare `dateUtil.now()` eliminates that collision entirely,
 * without changing the shared dedup transaction itself (which many unrelated callers rely on for its
 * current exact-timestamp semantics).
 */
object NoteTimestampAllocator {

    @Volatile private var lastAllocated = 0L

    @Synchronized
    fun next(now: Long): Long {
        val ts = if (now > lastAllocated) now else lastAllocated + 1
        lastAllocated = ts
        return ts
    }
}
