package com.example.activity_tracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "battery")
data class BatteryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ts_ms: Long,
    val level: Float
)
