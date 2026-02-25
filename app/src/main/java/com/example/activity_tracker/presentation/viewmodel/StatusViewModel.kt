package com.example.activity_tracker.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.activity_tracker.ActivityTrackerApp
import com.example.activity_tracker.network.UploadWorker
import com.example.activity_tracker.packet.PacketPipeline
import com.example.activity_tracker.service.CollectorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel для управления состоянием UI статуса
 */
class StatusViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ActivityTrackerApp).samplesRepository

    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: StateFlow<Boolean> = _isCollecting.asStateFlow()

    private val _shiftStartTs = MutableStateFlow<Long?>(null)

    val pendingPacketsCount: StateFlow<Int> = repository
        .observeQueue(PacketPipeline.STATUS_PENDING)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val uploadedPacketsCount: StateFlow<Int> = repository
        .observeQueue(PacketPipeline.STATUS_UPLOADED)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val errorPacketsCount: StateFlow<Int> = repository
        .observeQueue(PacketPipeline.STATUS_ERROR)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun startCollection() {
        viewModelScope.launch {
            _shiftStartTs.value = System.currentTimeMillis()
            CollectorService.start(getApplication())
            _isCollecting.value = true
        }
    }

    fun stopCollection() {
        viewModelScope.launch {
            val startTs = _shiftStartTs.value
            val endTs = System.currentTimeMillis()
            CollectorService.stop(getApplication())
            _isCollecting.value = false

            if (startTs != null) {
                val pipeline = PacketPipeline(getApplication(), repository)
                val result = pipeline.buildAndEnqueue(startTs, endTs)
                if (result != null) {
                    android.util.Log.d(
                        "StatusViewModel",
                        "Packet created: ${result.packetId}, size=${result.payloadSizeBytes}B"
                    )
                    UploadWorker.schedule(getApplication())
                }
            }
        }
    }
}
