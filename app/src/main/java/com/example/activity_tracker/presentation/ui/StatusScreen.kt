package com.example.activity_tracker.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import androidx.wear.compose.material.dialog.Dialog
import androidx.wear.compose.material.dialog.Alert

/**
 * UI статуса сбора данных и очереди пакетов.
 * Показывает device_id, статус сбора, очередь пакетов.
 * Внизу — кнопка «Сбросить» для капитального сброса (переезд площадки).
 */
@Composable
fun StatusScreen(
    deviceId: String,
    isCollecting: Boolean,
    pendingPackets: Int,
    uploadedPackets: Int,
    errorPackets: Int = 0,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Диалог подтверждения сброса
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        ResetConfirmationDialog(
            onConfirm = {
                showResetDialog = false
                onResetClick()
            },
            onDismiss = { showResetDialog = false }
        )
    }

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

            Spacer(modifier = Modifier.height(8.dp))

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

            if (errorPackets > 0) {
                Text(
                    text = "Ошибка: $errorPackets пак.",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.error,
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

            // Кнопка сброса (только когда сбор НЕ активен)
            if (!isCollecting) {
                Spacer(modifier = Modifier.height(12.dp))
                CompactButton(
                    onClick = { showResetDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF424242)
                    )
                ) {
                    Text(
                        text = "⚙ Сбросить",
                        style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp),
                        color = Color(0xFFB0BEC5)
                    )
                }
            }
        }
    }
}

/**
 * Диалог подтверждения сброса устройства.
 * Предупреждает что часы будут отвязаны от площадки.
 */
@Composable
private fun ResetConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        showDialog = true,
        onDismissRequest = onDismiss
    ) {
        Alert(
            title = {
                Text(
                    text = "Сбросить устройство?",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground
                )
            },
            message = {
                Text(
                    text = "Все данные регистрации будут удалены. Потребуется повторное сканирование QR-кода.",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
                )
            },
            negativeButton = {
                CompactButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text("Отмена", style = MaterialTheme.typography.caption2)
                }
            },
            positiveButton = {
                CompactButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFFEF5350)
                    )
                ) {
                    Text("Сброс", style = MaterialTheme.typography.caption2)
                }
            }
        )
    }
}
