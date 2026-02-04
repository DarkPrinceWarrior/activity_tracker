package com.example.activity_tracker.data.repository

import com.example.activity_tracker.data.local.AppDatabase
import com.example.activity_tracker.data.local.entity.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SamplesRepositoryImpl(
    private val db: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : SamplesRepository {

    override suspend fun saveSensor(samples: List<SensorSampleEntity>) = withContext(io) {
        db.sensorDao().insertSensorSamples(samples)
    }

    override suspend fun saveBle(events: List<BleEventEntity>) = withContext(io) {
        db.bleDao().insert(events)
    }

    override suspend fun saveWear(events: List<WearEventEntity>) = withContext(io) {
        db.wearDao().insert(events)
    }

    override suspend fun saveHeartRate(samples: List<HeartRateEntity>) = withContext(io) {
        db.heartRateDao().insert(samples)
    }

    override suspend fun saveBaro(samples: List<BaroEntity>) = withContext(io) {
        db.baroDao().insert(samples)
    }

    override suspend fun saveMag(samples: List<MagEntity>) = withContext(io) {
        db.magDao().insert(samples)
    }

    override suspend fun saveBattery(events: List<BatteryEntity>) = withContext(io) {
        db.batteryDao().insert(events)
    }

    override suspend fun saveDowntimeReason(events: List<DowntimeReasonEntity>) = withContext(io) {
        db.downtimeReasonDao().insert(events)
    }

    override suspend fun enqueuePacket(item: PacketQueueEntity) = withContext(io) {
        db.packetQueueDao().enqueue(item)
    }

    override fun observeQueue(status: String): Flow<List<PacketQueueEntity>> =
        db.packetQueueDao().byStatusFlow(status)

    override suspend fun getSensorRange(from: Long, to: Long): List<SensorSampleEntity> = withContext(io) {
        db.sensorDao().range(from, to)
    }

    override suspend fun getBleRange(from: Long, to: Long): List<BleEventEntity> = withContext(io) {
        db.bleDao().range(from, to)
    }

    override suspend fun getWearRange(from: Long, to: Long): List<WearEventEntity> = withContext(io) {
        db.wearDao().range(from, to)
    }

    override suspend fun getHeartRateRange(from: Long, to: Long): List<HeartRateEntity> = withContext(io) {
        db.heartRateDao().range(from, to)
    }

    override suspend fun getBaroRange(from: Long, to: Long): List<BaroEntity> = withContext(io) {
        db.baroDao().range(from, to)
    }

    override suspend fun getMagRange(from: Long, to: Long): List<MagEntity> = withContext(io) {
        db.magDao().range(from, to)
    }

    override suspend fun getBatteryRange(from: Long, to: Long): List<BatteryEntity> = withContext(io) {
        db.batteryDao().range(from, to)
    }

    override suspend fun getDowntimeReasonRange(from: Long, to: Long): List<DowntimeReasonEntity> = withContext(io) {
        db.downtimeReasonDao().range(from, to)
    }
}
