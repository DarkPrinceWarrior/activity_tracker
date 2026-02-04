package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.DowntimeReasonEntity

@Dao
interface DowntimeReasonDao {
    @Insert
    fun insert(items: List<DowntimeReasonEntity>)

    @Query("SELECT * FROM downtime_reason WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<DowntimeReasonEntity>

    @Query("DELETE FROM downtime_reason WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}
