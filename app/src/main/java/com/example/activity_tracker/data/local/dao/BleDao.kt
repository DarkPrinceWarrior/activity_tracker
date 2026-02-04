package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.BleEventEntity

@Dao
interface BleDao {
    @Insert
    fun insert(items: List<BleEventEntity>)

    @Query("SELECT * FROM ble_events WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<BleEventEntity>

    @Query("DELETE FROM ble_events WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}
