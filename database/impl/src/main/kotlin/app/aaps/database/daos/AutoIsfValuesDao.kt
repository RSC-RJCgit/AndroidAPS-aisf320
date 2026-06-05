package app.aaps.database.daos

import androidx.room.Dao
import androidx.room.Query
import app.aaps.database.entities.AutoIsfValues
import app.aaps.database.entities.TABLE_AUTOISF_VALUES
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single

@Dao
internal interface AutoIsfValuesDao : TraceableDao<AutoIsfValues> {

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE id = :id")
    override fun findById(id: Long): AutoIsfValues?

    @Query("DELETE FROM $TABLE_AUTOISF_VALUES")
    override fun deleteAllEntries()

    @Query("DELETE FROM $TABLE_AUTOISF_VALUES WHERE timestamp < :than")
    override fun deleteOlderThan(than: Long): Int

    @Query("DELETE FROM $TABLE_AUTOISF_VALUES WHERE referenceId IS NOT NULL")
    override fun deleteTrackedChanges(): Int

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE timestamp >= :timestamp ORDER BY timestamp")
    fun getFromTime(timestamp: Long): Single<List<AutoIsfValues>>

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE timestamp BETWEEN :startMillis AND :endMillis ORDER BY timestamp")
    fun getFromTimeToTime(startMillis: Long, endMillis: Long): Single<List<AutoIsfValues>>

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE timestamp > :since AND timestamp <= :until LIMIT :limit OFFSET :offset")
    fun getNewEntriesSince(since: Long, until: Long, limit: Int, offset: Int): List<AutoIsfValues>

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE timestamp >= :timestamp ORDER BY timestamp DESC LIMIT 1")
    fun getLastAutoIsfValuesFromTime(timestamp: Long): Maybe<AutoIsfValues>

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE timestamp BETWEEN :startMillis AND :endMillis ORDER BY timestamp DESC LIMIT 1")
    fun getLastAutoIsfValuesFromTimeToTime(startMillis: Long, endMillis: Long): Maybe<AutoIsfValues>

}
