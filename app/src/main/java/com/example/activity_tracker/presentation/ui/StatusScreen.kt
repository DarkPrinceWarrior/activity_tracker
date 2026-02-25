package com.example.activity_tracker.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*

/**
 * UI статуса сбора данных и очереди пакетов
 * Согласно секции 21 и Итерации 2 из секции 23.3 плана
 */
@Composable
fun StatusScreen(
    isCollecting: Boolean,
    pendingPackets: Int,
    uploadedPackets: Int,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        timeText = { TimeText() },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Статус сбора
            Text(
                text = if (isCollecting) "● Сбор активен" else "Сбор остановлен",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                color = if (isCollecting)
                    MaterialTheme.colors.primary
                else
                    MaterialTheme.colors.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Кнопка управления
            Button(
                onClick = if (isCollecting) onStopClick else onStartClick,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text(
                    text = if (isCollecting) "Остановить" else "Запустить",
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Статус очереди пакетов
            if (pendingPackets > 0) {
                Text(
                    text = "В очереди: $pendingPackets пак.",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.secondary,
                    textAlign = TextAlign.Center
                )
            }

            if (uploadedPackets > 0) {
                Text(
                    text = "Отправлено: $uploadedPackets пак.",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }

            if (isCollecting) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "8 потоков сбора",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
