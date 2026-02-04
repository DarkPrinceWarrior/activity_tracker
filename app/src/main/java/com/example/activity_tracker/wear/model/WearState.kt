package com.example.activity_tracker.wear.model

data class WearState(
    val timestamp: Long,
    val state: State
) {
    enum class State(val value: String) {
        ON_WRIST("on"),
        OFF_WRIST("off"),
        UNKNOWN("unknown")
    }
}
