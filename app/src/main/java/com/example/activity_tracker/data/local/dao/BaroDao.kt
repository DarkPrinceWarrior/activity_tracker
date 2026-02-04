package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.BaroEntity

@Dao
interface BaroDao {
    @Insert
    fun insert(items: List<BaroEntity>)

    @Query("SELECT * FROM baro WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<BaroEntity>

    @Query("DELETE FROM baro WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}
