package com.example.activity_tracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.activity_tracker.data.local.dao.*
import com.example.activity_tracker.data.local.entity.*

@Database(
    entities = [
        SensorSampleEntity::class,
        BleEventEntity::class,
        WearEventEntity::class,
        HeartRateEntity::class,
        BaroEntity::class,
        MagEntity::class,
        BatteryEntity::class,
        DowntimeReasonEntity::class,
        PacketQueueEntity::class,
        PacketPartEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sensorDao(): SensorDao
    abstract fun bleDao(): BleDao
    abstract fun wearDao(): WearDao
    abstract fun heartRateDao(): HeartRateDao
    abstract fun baroDao(): BaroDao
    abstract fun magDao(): MagDao
    abstract fun batteryDao(): BatteryDao
    abstract fun downtimeReasonDao(): DowntimeReasonDao
    abstract fun packetQueueDao(): PacketQueueDao
    abstract fun packetPartDao(): PacketPartDao
}
