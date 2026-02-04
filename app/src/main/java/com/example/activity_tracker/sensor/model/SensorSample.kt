package com.example.activity_tracker.sensor.model

data class SensorSample(
    val type: SensorType,
    val timestamp: Long,
    val x: Float? = null,
    val y: Float? = null,
    val z: Float? = null,
    val quality: Float = 1f
)

enum class SensorType(val typeName: String) {
    ACCELEROMETER("accel"),
    GYROSCOPE("gyro"),
    BAROMETER("baro"),
    MAGNETOMETER("mag")
}
