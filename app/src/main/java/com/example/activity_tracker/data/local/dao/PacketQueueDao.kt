package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.PacketQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PacketQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun enqueue(item: PacketQueueEntity)

    @Query("SELECT * FROM packet_queue WHERE status = :status ORDER BY created_ts_ms ASC")
    fun byStatus(status: String): List<PacketQueueEntity>

    @Query("SELECT * FROM packet_queue WHERE status = :status ORDER BY created_ts_ms ASC")
    fun byStatusFlow(status: String): Flow<List<PacketQueueEntity>>

    @Query(
        "SELECT * FROM packet_queue " +
            "WHERE status = :status AND created_ts_ms >= :shiftStartTs " +
            "ORDER BY created_ts_ms ASC"
    )
    fun byStatusFlowForShift(
        status: String,
        shiftStartTs: Long,
    ): Flow<List<PacketQueueEntity>>

    @Query("UPDATE packet_queue SET status = :status, attempt = :attempt, last_error = :err WHERE packet_id = :id")
    fun updateStatus(id: String, status: String, attempt: Int, err: String?)

    @Query("DELETE FROM packet_queue WHERE packet_id = :id")
    fun delete(id: String)
}
