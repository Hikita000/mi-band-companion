package com.miband.companion.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miband.companion.data.ble.ConnectionState
import com.miband.companion.data.ble.protocol.MiBandConstants
import com.miband.companion.data.ble.scanner.ScannedBleDevice
import com.miband.companion.presentation.theme.ErrorRed
import com.miband.companion.presentation.theme.SuccessGreen
import com.miband.companion.presentation.theme.TealAccent
import com.miband.companion.presentation.viewmodel.MainUiState

enum class CompanionTab(val title: String, val icon: ImageVector) {
    DASHBOARD("Браслет", Icons.Default.Watch),
    NOTIFICATIONS("Уведомления", Icons.Default.Notifications),
    MIRRORING("Экран", Icons.Default.Cast),
    SETTINGS("Ключ и BLE", Icons.Default.Settings)
}

@Composable
fun MainScreen(
    uiState: MainUiState,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onFindBandClick: () -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSendTestNotification: () -> Unit,
    onStartScreenMirroring: () -> Unit,
    onStopScreenMirroring: () -> Unit,
    onMacChanged: (String) -> Unit,
    onAuthKeyChanged: (String) -> Unit,
    onToggleScan: () -> Unit,
    onSelectDevice: (ScannedBleDevice) -> Unit,
    onRequestBatteryIgnore: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(CompanionTab.DASHBOARD) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Mi Smart Band 4",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Direct BLE & Auth Engine",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    ConnectionStatusChip(state = uiState.connectionState)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                CompanionTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.title) },
                        label = { Text(text = tab.title, maxLines = 1) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(visible = uiState.statusBannerMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = uiState.statusBannerMessage.orEmpty(),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            when (selectedTab) {
                CompanionTab.DASHBOARD -> DashboardTabContent(
                    uiState = uiState,
                    onConnectClick = onConnectClick,
                    onDisconnectClick = onDisconnectClick,
                    onFindBandClick = onFindBandClick
                )
                CompanionTab.NOTIFICATIONS -> NotificationsTabContent(
                    uiState = uiState,
                    onToggleNotifications = onToggleNotifications,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onSendTestNotification = onSendTestNotification
                )
                CompanionTab.MIRRORING -> MirroringTabContent(
                    uiState = uiState,
                    onStartScreenMirroring = onStartScreenMirroring,
                    onStopScreenMirroring = onStopScreenMirroring
                )
                CompanionTab.SETTINGS -> SettingsTabContent(
                    uiState = uiState,
                    onMacChanged = onMacChanged,
                    onAuthKeyChanged = onAuthKeyChanged,
                    onToggleScan = onToggleScan,
                    onSelectDevice = onSelectDevice,
                    onRequestBatteryIgnore = onRequestBatteryIgnore
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusChip(state: ConnectionState) {
    val (label, color) = when (state) {
        is ConnectionState.Authenticated -> "Авторизован" to SuccessGreen
        is ConnectionState.Authenticating -> "Auth Key..." to TealAccent
        is ConnectionState.ConnectedGatt -> "GATT..." to TealAccent
        is ConnectionState.Connecting -> "Поиск..." to MaterialTheme.colorScheme.primary
        is ConnectionState.Disconnected -> "Отключен" to Color.Gray
        is ConnectionState.Error -> "Ошибка" to ErrorRed
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DashboardTabContent(
    uiState: MainUiState,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onFindBandClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Карточка управления соединением
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Статус сокета GATT",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (val s = uiState.connectionState) {
                        is ConnectionState.Authenticated -> "Криптографическое рукопожатие успешно пройдено. Канал открыт."
                        is ConnectionState.Authenticating -> "Выполняется обмен AES-128 challenge с браслетом..."
                        is ConnectionState.ConnectedGatt -> "GATT сокет открыт. Обнаружение характеристик Huami..."
                        is ConnectionState.Connecting -> "Установка прямого BLE соединения..."
                        is ConnectionState.Disconnected -> "Готов к подключению. Выберите устройство или нажмите Подключить."
                        is ConnectionState.Error -> "Сбой: ${s.message}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "MAC: ${uiState.macAddress.ifBlank { "Не выбран" }}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val isConnected = uiState.connectionState !is ConnectionState.Disconnected &&
                            uiState.connectionState !is ConnectionState.Error

                    Button(
                        onClick = if (isConnected) onDisconnectClick else onConnectClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.LinkOff else Icons.Default.BluetoothConnected,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isConnected) "Отключить" else "Подключить")
                    }

                    OutlinedButton(
                        onClick = onFindBandClick,
                        enabled = uiState.connectionState is ConnectionState.Authenticated
                    ) {
                        Icon(imageVector = Icons.Default.Vibration, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Найти")
                    }
                }
            }
        }

        // Виджеты живой телеметрии
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryCard(
                title = "Шаги",
                value = uiState.steps.toString(),
                icon = Icons.Default.DirectionsWalk,
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            TelemetryCard(
                title = "Заряд",
                value = if (uiState.batteryLevel >= 0) "${uiState.batteryLevel}%" else "--",
                icon = Icons.Default.BatteryChargingFull,
                accentColor = SuccessGreen,
                modifier = Modifier.weight(1f)
            )
        }

        TelemetryCard(
            title = "Пульс (в реальном времени)",
            value = if (uiState.heartRate > 0) "${uiState.heartRate} уд/мин" else "Ожидание замера",
            icon = Icons.Default.Favorite,
            accentColor = ErrorRed,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TelemetryCard(
    title: String,
    value: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NotificationsTabContent(
    uiState: MainUiState,
    onToggleNotifications: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSendTestNotification: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Доступ к шторке уведомлений", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (uiState.hasNotificationPermission) {
                        "Системный перехватчик активен. Входящие сообщения автоматически упаковываются и отправляются на браслет."
                    } else {
                        "Необходимо выдать системное разрешение Notification Listener для перехвата сообщений из Android."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (!uiState.hasNotificationPermission) {
                    Button(onClick = onOpenNotificationSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть настройки доступа")
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Пересылать уведомления", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = uiState.notificationsEnabled,
                            onCheckedChange = onToggleNotifications
                        )
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Диагностика пакета 0x2A46", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Отправляет тестовый UTF-8 пакет напрямую в характеристику оповещений Mi Smart Band 4.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onSendTestNotification,
                    enabled = uiState.connectionState is ConnectionState.Authenticated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Отправить тест на экран браслета")
                }
            }
        }
    }
}

@Composable
private fun MirroringTabContent(
    uiState: MainUiState,
    onStartScreenMirroring: () -> Unit,
    onStopScreenMirroring: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ScreenshotMonitor,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Движок MediaProjection 120×240", style = MaterialTheme.typography.titleLarge)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Захват экрана смартфона с помощью VirtualDisplay, масштабирование до 120×240, дизеринг Флойда-Стейнберга (3600 байт/кадр) и троттлинг 3 FPS для передачи чанками по BLE.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (uiState.isScreenMirroringActive) {
                    Button(
                        onClick = onStopScreenMirroring,
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Остановить трансляцию")
                    }
                } else {
                    Button(
                        onClick = onStartScreenMirroring,
                        enabled = uiState.connectionState is ConnectionState.Authenticated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Запустить стриминг экрана")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTabContent(
    uiState: MainUiState,
    onMacChanged: (String) -> Unit,
    onAuthKeyChanged: (String) -> Unit,
    onToggleScan: () -> Unit,
    onSelectDevice: (ScannedBleDevice) -> Unit,
    onRequestBatteryIgnore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = uiState.authKeyHex,
            onValueChange = onAuthKeyChanged,
            label = { Text("Auth Key (16 байт / 32 Hex символа)") },
            isError = !uiState.isAuthKeyValid,
            supportingText = {
                Text(
                    if (uiState.isAuthKeyValid) "Ключ валиден (AES-128 готов)"
                    else "Требуется ровно 32 Hex символа без пробелов"
                )
            },
            trailingIcon = {
                IconButton(onClick = { onAuthKeyChanged(MiBandConstants.DEFAULT_AUTH_KEY_HEX) }) {
                    Icon(imageVector = Icons.Default.Restore, contentDescription = "Сбросить на извлеченный ключ")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = uiState.macAddress,
            onValueChange = onMacChanged,
            label = { Text("MAC-адрес браслета (XX:XX:XX:XX:XX:XX)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onToggleScan,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (uiState.isScanning) Icons.Default.Stop else Icons.Default.BluetoothSearching,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (uiState.isScanning) "Стоп поиск" else "Сканер BLE")
            }

            OutlinedButton(onClick = onRequestBatteryIgnore) {
                Icon(imageVector = Icons.Default.BatterySaver, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Без Doze")
            }
        }

        if (uiState.isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Text(
            text = "Найденные и сопряженные устройства:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.scannedDevices, key = { it.address }) { device ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectDevice(device) },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = device.address,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (device.isBonded) {
                                Text(
                                    text = "Сопряжено",
                                    color = SuccessGreen,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Text(
                                text = "${device.rssi} dBm",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
