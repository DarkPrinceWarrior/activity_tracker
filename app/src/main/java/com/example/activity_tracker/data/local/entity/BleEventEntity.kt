package com.example.activity_tracker.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ble_events",
    indices = [Index("ts_ms")]
)
data class BleEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ts_ms: Long,
    val beacon_id: String,
    val rssi: Int? = null
)
