package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.SensorSampleEntity

@Dao
interface SensorDao {
    @Insert
    fun insertSensorSamples(items: List<SensorSampleEntity>)

    @Query("SELECT * FROM sensor_samples WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<SensorSampleEntity>

    @Query("DELETE FROM sensor_samples WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}
