package com.example.activity_tracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downtime_reason")
data class DowntimeReasonEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ts_ms: Long,
    val reason_id: String,
    val zone_id: String? = null
)
