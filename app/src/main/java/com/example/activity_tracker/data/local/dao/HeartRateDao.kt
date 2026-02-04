package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.HeartRateEntity

@Dao
interface HeartRateDao {
    @Insert
    fun insert(items: List<HeartRateEntity>)

    @Query("SELECT * FROM heart_rate WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<HeartRateEntity>

    @Query("DELETE FROM heart_rate WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}
