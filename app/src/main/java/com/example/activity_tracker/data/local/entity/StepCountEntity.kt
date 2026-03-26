package com.example.activity_tracker.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "step_counts",
    indices = [Index("ts_ms")]
)
data class StepCountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ts_ms: Long,
    val total_steps: Long
)
