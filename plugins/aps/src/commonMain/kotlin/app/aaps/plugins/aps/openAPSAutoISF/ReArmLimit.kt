package app.aaps.plugins.aps.openAPSAutoISF

/**
 * Re-arm limit for the 5-minute TT nudges (2026-11-28, per explicit request, same as 3426): EarlyDawnSlowRise and
 * StuckRisingSlowly re-arm every ~6 minutes while their slow-rise test holds. Live 28 Oct-28 Nov: no run of 3 or fewer TTs
 * went to 4.5 mmol or below within 3 h; the runs of 4+ did (EDSR 26 Nov x9 and 28 Nov x4 both 4.0, StuckRisingSlowly 16 Nov
 * 3.7 and 11 Nov 4.4). So at most [max] TTs per block in any rolling [windowMs], plus an optional IOB limit (EDSR 0.9 U: the
 * first-fire 90th percentile was 0.83 U, 28 Nov's 4th re-arm 0.93 U). In memory only: a restart clears it.
 */
internal class ReArmLimit(private val max: Int = 3, private val windowMs: Long = 30 * 60_000L) {

    private val times = HashMap<String, ArrayDeque<Long>>()

    /** Why [key] must not set another TT now, or null when it may. */
    fun heldBack(key: String, now: Long, iob: Double? = null, maxIob: Double? = null): String? {
        val list = times.getOrPut(key) { ArrayDeque() }
        while (list.isNotEmpty() && now - list.first() >= windowMs) list.removeFirst()
        if (list.size >= max) return "${list.size} TTs in the last ${windowMs / 60_000L} min"
        if (maxIob != null && iob != null && iob >= maxIob) return "IOB $iob >= $maxIob U"
        return null
    }

    /** Records a TT that [key] set at [now]. */
    fun note(key: String, now: Long) {
        times.getOrPut(key) { ArrayDeque() }.addLast(now)
    }
}
