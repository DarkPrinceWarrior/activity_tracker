/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.example.activity_tracker.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.activity_tracker.presentation.theme.Activity_trackerTheme
import com.example.activity_tracker.presentation.ui.StatusScreen
import com.example.activity_tracker.presentation.viewmodel.StatusViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: StatusViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            Activity_trackerTheme {
                ActivityTrackerApp(viewModel)
            }
        }
    }
}

@Composable
fun ActivityTrackerApp(viewModel: StatusViewModel) {
    val isCollecting by viewModel.isCollecting.collectAsState()

    StatusScreen(
        isCollecting = isCollecting,
        onStartClick = { viewModel.startCollection() },
        onStopClick = { viewModel.stopCollection() }
    )
}