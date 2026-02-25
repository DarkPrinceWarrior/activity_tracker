package com.example.activity_tracker.data.repository

import com.example.activity_tracker.data.local.entity.*
import kotlinx.coroutines.flow.Flow

interface SamplesRepository {
    suspend fun saveSensor(samples: List<SensorSampleEntity>)
    suspend fun saveBle(events: List<BleEventEntity>)
    suspend fun saveWear(events: List<WearEventEntity>)
    suspend fun saveHeartRate(samples: List<HeartRateEntity>)
    suspend fun saveBaro(samples: List<BaroEntity>)
    suspend fun saveMag(samples: List<MagEntity>)
    suspend fun saveBattery(events: List<BatteryEntity>)
    suspend fun saveDowntimeReason(events: List<DowntimeReasonEntity>)

    suspend fun enqueuePacket(item: PacketQueueEntity)
    fun observeQueue(status: String): Flow<List<PacketQueueEntity>>
    suspend fun updatePacketStatus(packetId: String, status: String, attempt: Int, error: String?)
    suspend fun getPendingPackets(): List<PacketQueueEntity>

    suspend fun getSensorRange(from: Long, to: Long): List<SensorSampleEntity>
    suspend fun getBleRange(from: Long, to: Long): List<BleEventEntity>
    suspend fun getWearRange(from: Long, to: Long): List<WearEventEntity>
    suspend fun getHeartRateRange(from: Long, to: Long): List<HeartRateEntity>
    suspend fun getBaroRange(from: Long, to: Long): List<BaroEntity>
    suspend fun getMagRange(from: Long, to: Long): List<MagEntity>
    suspend fun getBatteryRange(from: Long, to: Long): List<BatteryEntity>
    suspend fun getDowntimeReasonRange(from: Long, to: Long): List<DowntimeReasonEntity>
}
