package com.example.activity_tracker.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "packet_parts",
    primaryKeys = ["packet_id", "part_no"]
)
data class PacketPartEntity(
    val packet_id: String,
    val part_no: Int,
    val path: String
)
