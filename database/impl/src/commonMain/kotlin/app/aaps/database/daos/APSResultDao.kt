package app.aaps.database.daos

import androidx.room.Dao
import androidx.room.Query
import app.aaps.database.entities.APSResult
import app.aaps.database.entities.TABLE_APS_RESULTS

@Dao
internal interface APSResultDao : TraceableDao<APSResult> {

    @Query("SELECT * FROM $TABLE_APS_RESULTS WHERE id = :id")
    override suspend fun findById(id: Long): APSResult?

    @Query("DELETE FROM $TABLE_APS_RESULTS")
    override suspend fun deleteAllEntries()

    @Query("DELETE FROM $TABLE_APS_RESULTS WHERE timestamp < :than")
    override suspend fun deleteOlderThan(than: Long): Int

    @Query("DELETE FROM $TABLE_APS_RESULTS WHERE referenceId IS NOT NULL")
    override suspend fun deleteTrackedChanges(): Int

    @Query("SELECT * FROM $TABLE_APS_RESULTS WHERE dateCreated > :since AND dateCreated <= :until LIMIT :limit OFFSET :offset")
    suspend fun getNewEntriesSince(since: Long, until: Long, limit: Int, offset: Int): List<APSResult>

    @Query("SELECT * FROM $TABLE_APS_RESULTS WHERE timestamp > :since AND timestamp <= :until ORDER BY timestamp DESC LIMIT 1")
    suspend fun getApsResult(since: Long, until: Long): APSResult?

    @Query("SELECT * FROM $TABLE_APS_RESULTS WHERE timestamp >= :start AND timestamp <= :end ORDER BY timestamp ASC")
    suspend fun getApsResults(start: Long, end: Long): List<APSResult>

    /**
     * Only the time and the variable sensitivity of each result, for the graph.
     *
     * The value is a field of `resultJson`, and SQLite reads it there. Loading whole rows and decoding
     * them in Kotlin decodes all seven JSON columns of about 288 rows on every calculation. In a desktop
     * benchmark that was about 29 times slower (#5248). `json_extract` gives NULL when the field is
     * missing and when it is `NaN`.
     */
    @Query("SELECT timestamp, json_extract(resultJson, '\$.variable_sens') AS variableSens FROM $TABLE_APS_RESULTS WHERE timestamp >= :start AND timestamp <= :end ORDER BY timestamp ASC")
    suspend fun getVariableSens(start: Long, end: Long): List<ApsResultVariableSens>

    /**
     * Only the time and the reason text of each result, for the graph's StepCut arrows and steps row (2026-11-18).
     *
     * Like [getVariableSens]. Decoding the whole rows of 24 hours (about 1,350 rows of about 6.8 kB each on a client)
     * about once a minute held 0.4 to 1 GB and about 10 % CPU on the iOS AAPSClient2 (jetsam reports 11-17 Nov 2026).
     */
    @Query("SELECT timestamp, json_extract(resultJson, '\$.reason') AS reason FROM $TABLE_APS_RESULTS WHERE timestamp >= :start AND timestamp <= :end ORDER BY timestamp ASC")
    suspend fun getReasons(start: Long, end: Long): List<ApsResultReason>
}

/** One row of [APSResultDao.getReasons]. [reason] is null when the result has none. */
data class ApsResultReason(
    val timestamp: Long,
    val reason: String?
)

/** One row of [APSResultDao.getVariableSens]. [variableSens] is in mg/dL, null when the result has no value. */
data class ApsResultVariableSens(
    val timestamp: Long,
    val variableSens: Double?
)
