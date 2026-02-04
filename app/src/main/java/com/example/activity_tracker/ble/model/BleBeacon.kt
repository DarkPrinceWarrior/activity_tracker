package com.example.activity_tracker.ble.model

data class BleBeacon(
    val timestamp: Long,
    val beaconId: String,
    val rssi: Int
)
