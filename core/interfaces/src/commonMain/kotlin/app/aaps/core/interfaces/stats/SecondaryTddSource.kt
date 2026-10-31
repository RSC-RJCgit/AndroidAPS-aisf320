package app.aaps.core.interfaces.stats

import app.aaps.core.data.model.TDD

/**
 * The other phone's daily totals, worked out from its records on the second Nightscout (2026-11-01, per explicit
 * request). For the statistics screens only, to fill days this phone has no insulin records for - a Virtual master that
 * started recently, for one.
 *
 * Never stored. In particular nothing from here goes into the TDD cache: AutoISF reads that cache, and the other
 * phone's insulin must not change this one's dosing.
 */
interface SecondaryTddSource {

    /**
     * The totals of the days starting at the local midnights in [dayStarts], keyed by those midnights. A day is missing
     * when the second site is off, cannot be read, or holds too little for that day. Days not yet over are not given.
     */
    suspend fun dailyTdds(dayStarts: List<Long>): Map<Long, TDD>
}
