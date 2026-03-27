package com.example.activity_tracker.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.activity_tracker.presentation.theme.Activity_trackerTheme
import com.example.activity_tracker.presentation.ui.RegistrationScreen
import com.example.activity_tracker.presentation.ui.StatusScreen
import com.example.activity_tracker.presentation.viewmodel.StatusViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: StatusViewModel by viewModels()

    private val requiredPermissions = buildList {
        add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions
            .filterValues { granted -> !granted }
            .keys
            .forEach { deniedPermission ->
                Log.w(TAG, "Permission denied: $deniedPermission")
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        // Удерживаем splash пока ViewModel не завершит инициализацию.
        // Пользователь видит системный splash вместо белого/чёрного фриза.
        splashScreen.setKeepOnScreenCondition {
            !viewModel.isReady.value
        }

        requestPermissionsIfNeeded()

        setContent {
            Activity_trackerTheme {
                ActivityTrackerApp(viewModel)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun ActivityTrackerApp(viewModel: StatusViewModel) {
    val isRegistered by viewModel.isRegistered.collectAsState()

    if (!isRegistered) {
        // Экран QR-регистрации: показывает QR с device_id, поллит бэкенд
        RegistrationScreen(viewModel = viewModel)
    } else {
        // Основной экран статуса (пассивный — без кнопок start/stop)
        val deviceId by viewModel.deviceId.collectAsState()
        val isCollecting by viewModel.isCollecting.collectAsState()
        val pollerState by viewModel.pollerState.collectAsState()
        val isCharging by viewModel.isChargingState.collectAsState()
        val pendingPackets by viewModel.pendingPacketsCount.collectAsState()
        val uploadedPackets by viewModel.uploadedPacketsCount.collectAsState()
        val errorPackets by viewModel.errorPacketsCount.collectAsState()

        StatusScreen(
            deviceId = deviceId,
            isCollecting = isCollecting,
            pollerState = pollerState,
            isCharging = isCharging,
            pendingPackets = pendingPackets,
            uploadedPackets = uploadedPackets,
            errorPackets = errorPackets,
            onResetClick = { viewModel.resetDevice() }
        )
    }
}
