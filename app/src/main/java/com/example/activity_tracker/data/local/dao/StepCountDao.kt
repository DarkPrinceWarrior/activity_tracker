package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.StepCountEntity

@Dao
interface StepCountDao {
    @Insert
    fun insert(items: List<StepCountEntity>)

    @Query("SELECT * FROM step_counts WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<StepCountEntity>

    @Query("DELETE FROM step_counts WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}
