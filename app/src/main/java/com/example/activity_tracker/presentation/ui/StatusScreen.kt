package com.example.activity_tracker.presentation.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import androidx.compose.foundation.background

/**
 * StatusScreen — строгий нативный Wear OS дизайн.
 * Следует принципам Material 3 для часов:
 * глансируемость, вертикальный скролл, нативные компоненты.
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

    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        timeText = {
            TimeText(
                timeTextStyle = TimeTextDefaults.timeTextStyle(
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
                )
            )
        },
        modifier = modifier
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                top = 32.dp,
                bottom = 24.dp,
                start = 8.dp,
                end = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            // ─── 1. Device ID (строка-метка) ─────────────────────────────
            if (deviceId.isNotBlank()) {
                item {
                    Text(
                        text = deviceId,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.primaryVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }
            }

            // ─── 2. Статус (dominant text — главный акцент экрана) ────────
            item {
                StatusLabel(isCollecting = isCollecting)
            }

            // ─── 3. Основная кнопка действия ─────────────────────────────
            item {
                Button(
                    onClick = if (isCollecting) onStopClick else onStartClick,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (isCollecting)
                            MaterialTheme.colors.error
                        else
                            MaterialTheme.colors.primary
                    )
                ) {
                    Text(
                        text = if (isCollecting) "Стоп" else "Пуск",
                        style = MaterialTheme.typography.button,
                        color = MaterialTheme.colors.onPrimary
                    )
                }
            }

            // ─── 4. Статистика пакетов (только если есть данные) ─────────
            if (uploadedPackets > 0 || pendingPackets > 0 || errorPackets > 0) {
                item {
                    PacketStats(
                        uploaded = uploadedPackets,
                        pending = pendingPackets,
                        errors = errorPackets
                    )
                }
            }

            // ─── 5. Кнопка сброса (только в режиме ожидания) ─────────────
            if (!isCollecting) {
                item {
                    CompactChip(
                        onClick = { showResetDialog = true },
                        label = {
                            Text(
                                text = "Сбросить",
                                style = MaterialTheme.typography.caption2
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Статус-лейбл с пульсирующим индикатором
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StatusLabel(isCollecting: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isCollecting) 1.6f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val dotColor by animateColorAsState(
        targetValue = if (isCollecting)
            Color(0xFF4CAF50)
        else
            MaterialTheme.colors.onSurface.copy(alpha = 0.38f),
        animationSpec = tween(400),
        label = "dotColor"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isCollecting)
            MaterialTheme.colors.onBackground
        else
            MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        animationSpec = tween(400),
        label = "labelColor"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Пульсирующая точка
        Box(contentAlignment = Alignment.Center) {
            if (isCollecting) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50).copy(alpha = 0.25f))
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }

        // Текст статуса
        Text(
            text = if (isCollecting) "Сбор активен" else "Сбор остановлен",
            style = MaterialTheme.typography.title3.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = labelColor,
            textAlign = TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Статистика пакетов — строгие нативные Chip-метки
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PacketStats(
    uploaded: Int,
    pending: Int,
    errors: Int
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        if (uploaded > 0) {
            StatChip(
                label = "Отправлено",
                value = "$uploaded пак.",
                valueColor = MaterialTheme.colors.primary
            )
        }
        if (pending > 0) {
            StatChip(
                label = "В очереди",
                value = "$pending пак.",
                valueColor = MaterialTheme.colors.secondary
            )
        }
        if (errors > 0) {
            StatChip(
                label = "Ошибки",
                value = "$errors пак.",
                valueColor = MaterialTheme.colors.error
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, valueColor: Color) {
    Chip(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        enabled = false,
        colors = ChipDefaults.chipColors(
            disabledBackgroundColor = MaterialTheme.colors.surface,
            disabledContentColor = MaterialTheme.colors.onSurface
        ),
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.caption1.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = valueColor
                )
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Диалог подтверждения сброса
// ─────────────────────────────────────────────────────────────────────────────
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
                    text = "Сбросить?",
                    style = MaterialTheme.typography.title3,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground
                )
            },
            negativeButton = {
                CompactButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text("✕", style = MaterialTheme.typography.caption2)
                }
            },
            positiveButton = {
                CompactButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.error
                    )
                ) {
                    Text("✓", style = MaterialTheme.typography.caption2)
                }
            }
        ) {
            Text(
                text = "Данные регистрации будут удалены. Потребуется QR-сканирование.",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}
