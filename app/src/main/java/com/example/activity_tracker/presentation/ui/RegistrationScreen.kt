package com.example.activity_tracker.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*

/**
 * Экран регистрации устройства на бэкенде.
 * Пользователь нажимает кнопку — устройство регистрируется по коду.
 *
 * Для разработки код вшит в UI. В проде — получать от оператора
 * через BLE или QR.
 */
@Composable
fun RegistrationScreen(
    registrationCode: String,
    isLoading: Boolean,
    errorMessage: String?,
    onRegisterClick: () -> Unit,
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
            // Заголовок
            Text(
                text = "Регистрация",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Показываем код (усеченный)
            Text(
                text = "Код: ${registrationCode.take(4)}...${registrationCode.takeLast(4)}",
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Ошибка
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.error
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Кнопка регистрации
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp
                )
            } else {
                Button(
                    onClick = onRegisterClick,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(
                        text = "Зарегистрировать",
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
