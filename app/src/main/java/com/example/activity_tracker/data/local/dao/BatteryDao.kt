package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.BatteryEntity

@Dao
interface BatteryDao {
    @Insert
    fun insert(items: List<BatteryEntity>)

    @Query("SELECT * FROM battery WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<BatteryEntity>

    @Query("DELETE FROM battery WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}
