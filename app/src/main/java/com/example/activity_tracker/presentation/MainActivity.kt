/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.example.activity_tracker.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    ) { /* permissions granted or denied - app continues anyway */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

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
}

@Composable
fun ActivityTrackerApp(viewModel: StatusViewModel) {
    val isRegistered by viewModel.isRegistered.collectAsState()

    if (!isRegistered) {
        // Экран QR-регистрации: показывает QR с device_id, поллит бэкенд
        RegistrationScreen(viewModel = viewModel)
    } else {
        // Основной экран статуса
        val deviceId by viewModel.deviceId.collectAsState()
        val isCollecting by viewModel.isCollecting.collectAsState()
        val pendingPackets by viewModel.pendingPacketsCount.collectAsState()
        val uploadedPackets by viewModel.uploadedPacketsCount.collectAsState()
        val errorPackets by viewModel.errorPacketsCount.collectAsState()

        StatusScreen(
            deviceId = deviceId,
            isCollecting = isCollecting,
            pendingPackets = pendingPackets,
            uploadedPackets = uploadedPackets,
            errorPackets = errorPackets,
            onStartClick = { viewModel.startCollection() },
            onStopClick = { viewModel.stopCollection() },
            onResetClick = { viewModel.resetDevice() }
        )
    }
}