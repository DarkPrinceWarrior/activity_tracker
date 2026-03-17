package com.example.activity_tracker.presentation.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.activity_tracker.presentation.viewmodel.StatusViewModel
import com.example.activity_tracker.util.QrCodeGenerator

/**
 * Экран регистрации часов через QR-код (Wear OS).
 *
 * Показывает QR-код с device_id и информацией об устройстве.
 * Оператор сканирует QR мобильным приложением,
 * после чего часы автоматически переходят к StatusScreen.
 */
@Composable
fun RegistrationScreen(
    viewModel: StatusViewModel,
) {
    val qrPayload by viewModel.qrPayload.collectAsState()
    val deviceId by viewModel.generatedDeviceId.collectAsState()
    val isPolling by viewModel.isPolling.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val isAuthLoading by viewModel.isAuthLoading.collectAsState()

    // Генерируем QR bitmap синхронно при изменении payload
    val qrBitmap: Bitmap? = remember(qrPayload) {
        if (qrPayload.isNotBlank()) {
            try {
                QrCodeGenerator.generate(qrPayload, size = 512)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp)
        ) {
            // Заголовок
            Text(
                text = "Сканируйте QR",
                style = MaterialTheme.typography.body1.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                ),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            // QR-код
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR для регистрации",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            } else {
                // Пока QR генерируется — простой текст вместо CircularProgressIndicator
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "...",
                        style = MaterialTheme.typography.title1,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Device ID
            if (deviceId.isNotBlank()) {
                Text(
                    text = deviceId,
                    style = MaterialTheme.typography.caption2.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    ),
                    color = Color(0xFF80CBC4),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))
            }

            // Статус поллинга
            val statusText = when {
                isAuthLoading -> "Получение токенов..."
                authError != null -> authError ?: ""
                isPolling -> "Ожидание сканирования..."
                else -> ""
            }
            val statusColor = when {
                isAuthLoading -> Color(0xFFFFD54F)
                authError != null -> Color(0xFFEF5350)
                else -> Color(0xFFB0BEC5)
            }

            if (statusText.isNotBlank()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp),
                    color = statusColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
