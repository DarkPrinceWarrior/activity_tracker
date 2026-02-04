package com.example.activity_tracker.heartrate.model

data class HeartRateSample(
    val timestamp: Long,
    val bpm: Int,
    val confidence: Float = 1f
)
