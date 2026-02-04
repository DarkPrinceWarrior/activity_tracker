package com.example.activity_tracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "packet_queue")
data class PacketQueueEntity(
    @PrimaryKey
    val packet_id: String,
    val created_ts_ms: Long,
    val shift_start_ts_ms: Long,
    val shift_end_ts_ms: Long,
    val status: String,
    val attempt: Int = 0,
    val last_error: String? = null,
    val payload_path: String
)
