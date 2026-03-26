package com.example.activity_tracker.packet.model

/**
 * Модели JSON-пакета смены согласно секции 9.6 плана
 * Это plaintext-структура внутри payload_enc после расшифровки
 */

data class ShiftPacket(
    val schema_version: Int = 1,
    val packet_id: String,
    val device: DeviceInfo,
    val shift: ShiftPeriod,
    val time_sync: TimeSync,
    val samples: ShiftSamples,
    val meta: PacketMeta
)

data class DeviceInfo(
    val device_id: String,
    val model: String,
    val firmware: String,
    val app_version: String,
    val timezone: String
)

data class ShiftPeriod(
    val start_ts_ms: Long,
    val end_ts_ms: Long
)

data class TimeSync(
    val server_time_offset_ms: Long = 0L,
    val server_time_ms: Long = 0L
)

data class ShiftSamples(
    val accel: List<AccelSample>,
    val gyro: List<GyroSample>,
    val baro: List<BaroSample>,
    val mag: List<MagSample>,
    val steps: List<StepSample>,
    val heart_rate: List<HrSample>,
    val ble_events: List<BleSample>,
    val wear_events: List<WearSample>,
    val battery_events: List<BatterySample>,
    val downtime_reasons: List<DowntimeSample>
)

data class AccelSample(
    val ts_ms: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val quality: Float
)

data class GyroSample(
    val ts_ms: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val quality: Float
)

data class BaroSample(
    val ts_ms: Long,
    val hpa: Float
)

data class MagSample(
    val ts_ms: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

data class StepSample(
    val ts_ms: Long,
    val total_steps: Long
)

data class HrSample(
    val ts_ms: Long,
    val bpm: Int,
    val confidence: Float
)

data class BleSample(
    val ts_ms: Long,
    val beacon_id: String,
    val rssi: Int?
)

data class WearSample(
    val ts_ms: Long,
    val state: String
)

data class BatterySample(
    val ts_ms: Long,
    val level: Float
)

data class DowntimeSample(
    val ts_ms: Long,
    val reason_id: String,
    val zone_id: String?
)

data class PacketMeta(
    val created_ts_ms: Long,
    val seq: Int,
    val upload_attempt: Int = 0
)
