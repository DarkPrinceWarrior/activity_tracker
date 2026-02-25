package com.example.activity_tracker.packet

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.activity_tracker.data.repository.SamplesRepository
import com.example.activity_tracker.packet.model.AccelSample
import com.example.activity_tracker.packet.model.BatterySample
import com.example.activity_tracker.packet.model.BleSample
import com.example.activity_tracker.packet.model.BaroSample
import com.example.activity_tracker.packet.model.DeviceInfo
import com.example.activity_tracker.packet.model.DowntimeSample
import com.example.activity_tracker.packet.model.GyroSample
import com.example.activity_tracker.packet.model.HrSample
import com.example.activity_tracker.packet.model.MagSample
import com.example.activity_tracker.packet.model.PacketMeta
import com.example.activity_tracker.packet.model.ShiftPacket
import com.example.activity_tracker.packet.model.ShiftPeriod
import com.example.activity_tracker.packet.model.ShiftSamples
import com.example.activity_tracker.packet.model.TimeSync
import com.example.activity_tracker.packet.model.WearSample
import com.google.gson.Gson
import java.util.TimeZone
import java.util.UUID

/**
 * Формирует пакет смены из Room-данных за указанный период
 * Согласно секции 9.6 плана
 */
class PacketBuilder(
    private val context: Context,
    private val repository: SamplesRepository,
    private val gson: Gson = Gson()
) {

    /**
     * Собирает все данные за период [startTs, endTs] и формирует ShiftPacket
     * @param startTs начало смены (Unix ms)
     * @param endTs конец смены (Unix ms)
     * @param seq порядковый номер пакета
     * @return ShiftPacket готовый к сериализации и шифрованию
     */
    suspend fun build(
        startTs: Long,
        endTs: Long,
        seq: Int = 0
    ): ShiftPacket {
        Log.d(TAG, "Building packet for period [$startTs, $endTs]")

        val sensorSamples = repository.getSensorRange(startTs, endTs)
        val bleEvents = repository.getBleRange(startTs, endTs)
        val wearEvents = repository.getWearRange(startTs, endTs)
        val heartRates = repository.getHeartRateRange(startTs, endTs)
        val baroSamples = repository.getBaroRange(startTs, endTs)
        val magSamples = repository.getMagRange(startTs, endTs)
        val batteryEvents = repository.getBatteryRange(startTs, endTs)
        val downtimeReasons = repository.getDowntimeReasonRange(startTs, endTs)

        val accelList = sensorSamples
            .filter { it.type == "accel" }
            .map { AccelSample(it.ts_ms, it.x ?: 0f, it.y ?: 0f, it.z ?: 0f, it.quality ?: 1f) }

        val gyroList = sensorSamples
            .filter { it.type == "gyro" }
            .map { GyroSample(it.ts_ms, it.x ?: 0f, it.y ?: 0f, it.z ?: 0f, it.quality ?: 1f) }

        val baroList = baroSamples.map { BaroSample(it.ts_ms, it.hpa) }

        val magList = magSamples.map { MagSample(it.ts_ms, it.x, it.y, it.z) }

        val hrList = heartRates.map { HrSample(it.ts_ms, it.bpm, it.confidence ?: 1f) }

        val bleList = bleEvents.map { BleSample(it.ts_ms, it.beacon_id, it.rssi) }

        val wearList = wearEvents.map { WearSample(it.ts_ms, it.state) }

        val batteryList = batteryEvents.map { BatterySample(it.ts_ms, it.level) }

        val downtimeList = downtimeReasons.map {
            DowntimeSample(it.ts_ms, it.reason_id, it.zone_id)
        }

        val packet = ShiftPacket(
            packet_id = UUID.randomUUID().toString(),
            device = buildDeviceInfo(),
            shift = ShiftPeriod(startTs, endTs),
            time_sync = TimeSync(),
            samples = ShiftSamples(
                accel = accelList,
                gyro = gyroList,
                baro = baroList,
                mag = magList,
                hr = hrList,
                ble = bleList,
                wear = wearList,
                battery = batteryList,
                downtime_reason = downtimeList
            ),
            meta = PacketMeta(
                created_ts_ms = System.currentTimeMillis(),
                seq = seq
            )
        )

        Log.d(TAG, "Packet built: id=${packet.packet_id}, " +
            "accel=${accelList.size}, gyro=${gyroList.size}, " +
            "hr=${hrList.size}, ble=${bleList.size}, " +
            "wear=${wearList.size}, battery=${batteryList.size}")

        return packet
    }

    /**
     * Сериализует пакет в JSON-строку
     */
    fun toJson(packet: ShiftPacket): String = gson.toJson(packet)

    /**
     * Возвращает размер пакета в байтах (JSON)
     */
    fun sizeBytes(packet: ShiftPacket): Int = toJson(packet).toByteArray().size

    private fun buildDeviceInfo(): DeviceInfo = DeviceInfo(
        device_id = getDeviceId(),
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        fw = Build.VERSION.RELEASE,
        app_version = getAppVersion(),
        tz = TimeZone.getDefault().id
    )

    private fun getAppVersion(): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            Log.d(TAG, "Generated new device_id: $newId")
            newId
        }
    }

    companion object {
        private const val TAG = "PacketBuilder"
        private const val PREFS_NAME = "activity_tracker_prefs"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
