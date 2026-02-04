package com.example.activity_tracker.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sensor_samples",
    indices = [Index("ts_ms")]
)
data class SensorSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,
    val ts_ms: Long,
    val x: Float? = null,
    val y: Float? = null,
    val z: Float? = null,
    val quality: Float? = null
)
