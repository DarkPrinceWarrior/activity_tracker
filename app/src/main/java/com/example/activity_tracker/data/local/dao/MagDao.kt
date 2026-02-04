package com.example.activity_tracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.activity_tracker.data.local.entity.MagEntity

@Dao
interface MagDao {
    @Insert
    fun insert(items: List<MagEntity>)

    @Query("SELECT * FROM mag WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<MagEntity>

    @Query("DELETE FROM mag WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}
