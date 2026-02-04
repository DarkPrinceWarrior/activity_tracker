package com.example.activity_tracker.battery.model

data class BatterySample(
    val timestamp: Long,
    val level: Float, // 0.0 - 1.0
    val isCharging: Boolean = false
)
