package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.PacketPartEntity

@Dao
interface PacketPartDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(parts: List<PacketPartEntity>)

    @Query("SELECT * FROM packet_parts WHERE packet_id = :id ORDER BY part_no ASC")
    fun parts(id: String): List<PacketPartEntity>

    @Query("DELETE FROM packet_parts WHERE packet_id = :id")
    fun deleteForPacket(id: String)
}
