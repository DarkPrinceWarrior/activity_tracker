package com.example.activity_tracker.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.activity_tracker.service.CollectorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel для управления состоянием UI статуса
 */
class StatusViewModel(application: Application) : AndroidViewModel(application) {

    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: StateFlow<Boolean> = _isCollecting.asStateFlow()

    fun startCollection() {
        viewModelScope.launch {
            CollectorService.start(getApplication())
            _isCollecting.value = true
        }
    }

    fun stopCollection() {
        viewModelScope.launch {
            CollectorService.stop(getApplication())
            _isCollecting.value = false
        }
    }
}
