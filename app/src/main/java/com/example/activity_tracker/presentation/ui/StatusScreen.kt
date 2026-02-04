package com.example.activity_tracker.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*

/**
 * Минимальный UI статуса сбора данных
 * Согласно секции 21 и Итерации 1 из секции 23.3 плана
 */
@Composable
fun StatusScreen(
    isCollecting: Boolean,
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Статус сбора
            Text(
                text = if (isCollecting) "Сбор активен" else "Сбор остановлен",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                color = if (isCollecting)
                    MaterialTheme.colors.primary
                else
                    MaterialTheme.colors.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Индикатор активности
            if (isCollecting) {
                Text(
                    text = "●",
                    style = MaterialTheme.typography.display1,
                    color = MaterialTheme.colors.primary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

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

            if (isCollecting) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "8 потоков сбора",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
