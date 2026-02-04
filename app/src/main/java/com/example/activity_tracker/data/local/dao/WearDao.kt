package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.WearEventEntity

@Dao
interface WearDao {
    @Insert
    fun insert(items: List<WearEventEntity>)

    @Query("SELECT * FROM wear_events WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<WearEventEntity>

    @Query("DELETE FROM wear_events WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}
