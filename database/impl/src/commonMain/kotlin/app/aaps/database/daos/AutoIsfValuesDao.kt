package app.aaps.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.aaps.database.entities.AutoIsfValues
import app.aaps.database.entities.TABLE_AUTO_ISF_VALUES

@Dao
internal interface AutoIsfValuesDao {

    @Insert
    suspend fun insert(entry: AutoIsfValues): Long

    @Query("SELECT * FROM $TABLE_AUTO_ISF_VALUES WHERE timestamp BETWEEN :startMillis AND :endMillis ORDER BY timestamp")
    suspend fun getFromTimeToTime(startMillis: Long, endMillis: Long): List<AutoIsfValues>
}
