package com.miband.companion.presentation.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miband.companion.data.ble.ConnectionState
import com.miband.companion.data.ble.protocol.MiBand4Crypto
import com.miband.companion.data.ble.protocol.MiBandConstants
import com.miband.companion.data.ble.scanner.BleDeviceScanner
import com.miband.companion.data.ble.scanner.ScannedBleDevice
import com.miband.companion.data.ble.service.MiBandBleService
import com.miband.companion.data.projection.ScreenMirroringService
import com.miband.companion.data.repository.DevicePreferencesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

data class MainUiState(
    val macAddress: String = "",
    val authKeyHex: String = MiBandConstants.DEFAULT_AUTH_KEY_HEX,
    val isAuthKeyValid: Boolean = true,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val steps: Int = 0,
    val batteryLevel: Int = -1,
    val heartRate: Int = 0,
    val isScanning: Boolean = false,
    val scannedDevices: List<ScannedBleDevice> = emptyList(),
    val notificationsEnabled: Boolean = true,
    val hasNotificationPermission: Boolean = false,
    val isScreenMirroringActive: Boolean = false,
    val statusBannerMessage: String? = null,
    val bleLogs: List<String> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = DevicePreferencesRepository(application)
    private val bluetoothAdapter = (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner = BleDeviceScanner(bluetoothAdapter)

    private val _uiState = MutableStateFlow(
        MainUiState(
            macAddress = prefsRepo.getMacAddress(),
            authKeyHex = prefsRepo.getAuthKey(),
            isAuthKeyValid = validateAuthKey(prefsRepo.getAuthKey()),
            notificationsEnabled = prefsRepo.isNotificationsEnabled(),
            hasNotificationPermission = checkNotificationListenerPermission(application)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var serviceObserverJob: Job? = null

    init {
        scanner.isScanning.onEach { scanning ->
            _uiState.value = _uiState.value.copy(isScanning = scanning)
        }.launchIn(viewModelScope)

        scanner.discoveredDevices.onEach { devices ->
            _uiState.value = _uiState.value.copy(scannedDevices = devices)
        }.launchIn(viewModelScope)

        attachToBleServiceLoop()
    }

    private fun attachToBleServiceLoop() {
        viewModelScope.launch {
            while (true) {
                val manager = MiBandBleService.activeInstance?.bleManager
                if (manager != null && serviceObserverJob == null) {
                    serviceObserverJob = launch {
                        launch {
                            manager.connectionState.collect { state ->
                                _uiState.value = _uiState.value.copy(connectionState = state)
                            }
                        }
                        launch {
                            manager.realtimeSteps.collect { steps ->
                                _uiState.value = _uiState.value.copy(steps = steps)
                            }
                        }
                        launch {
                            manager.batteryLevel.collect { battery ->
                                _uiState.value = _uiState.value.copy(batteryLevel = battery)
                            }
                        }
                        launch {
                            manager.heartRate.collect { bpm ->
                                _uiState.value = _uiState.value.copy(heartRate = bpm)
                            }
                        }
                        launch {
                            manager.debugLogs.collect { logs ->
                                _uiState.value = _uiState.value.copy(bleLogs = logs)
                            }
                        }
                    }
                } else if (manager == null && serviceObserverJob != null) {
                    serviceObserverJob?.cancel()
                    serviceObserverJob = null
                }
                delay(500)
            }
        }
    }

    fun refreshSystemPermissionsState() {
        val context = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            hasNotificationPermission = checkNotificationListenerPermission(context)
        )
    }

    private fun checkNotificationListenerPermission(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledPackages.contains(context.packageName)
    }

    fun updateMacAddress(mac: String) {
        val formatted = mac.uppercase().trim()
        prefsRepo.saveMacAddress(formatted)
        _uiState.value = _uiState.value.copy(macAddress = formatted)
    }

    fun updateAuthKey(newKey: String) {
        val cleanKey = newKey.trim()
        val valid = validateAuthKey(cleanKey)
        if (valid) {
            prefsRepo.saveAuthKey(cleanKey)
        }
        _uiState.value = _uiState.value.copy(
            authKeyHex = cleanKey,
            isAuthKeyValid = valid
        )
    }

    private fun validateAuthKey(key: String): Boolean {
        return try {
            MiBand4Crypto.hexStringToByteArray(key)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun toggleScan(strictHuamiFilter: Boolean = false) {
        if (_uiState.value.isScanning) {
            scanner.stopScan()
        } else {
            scanner.startScan(strictHuamiFilter)
        }
    }

    fun selectScannedDevice(device: ScannedBleDevice) {
        scanner.stopScan()
        updateMacAddress(device.address)
        connectToBand()
    }

    fun connectToBand() {
        val state = _uiState.value
        if (state.macAddress.isBlank()) {
            showBanner("Укажите MAC-адрес браслета или выберите его в сканере.")
            return
        }
        if (!state.isAuthKeyValid) {
            showBanner("Некорректный Auth Key! Необходимо ровно 32 Hex-символа.")
            return
        }

        val context = getApplication<Application>()
        val intent = Intent(context, MiBandBleService::class.java).apply {
            action = MiBandBleService.ACTION_CONNECT
            putExtra(MiBandBleService.EXTRA_MAC_ADDRESS, state.macAddress)
            putExtra(MiBandBleService.EXTRA_AUTH_KEY, state.authKeyHex)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun disconnectFromBand() {
        val context = getApplication<Application>()
        val intent = Intent(context, MiBandBleService::class.java).apply {
            action = MiBandBleService.ACTION_DISCONNECT
        }
        context.startService(intent)
        stopScreenMirroring()
    }

    fun triggerFindBandVibration() {
        val context = getApplication<Application>()
        val intent = Intent(context, MiBandBleService::class.java).apply {
            action = MiBandBleService.ACTION_VIBRATE
        }
        context.startService(intent)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefsRepo.setNotificationsEnabled(enabled)
        _uiState.value = _uiState.value.copy(notificationsEnabled = enabled)
    }

    fun sendTestNotificationToBand() {
        viewModelScope.launch {
            val manager = MiBandBleService.activeInstance?.bleManager
            if (manager == null || manager.connectionState.value !is ConnectionState.Authenticated) {
                showBanner("Браслет должен быть подключен и авторизован!")
                return@launch
            }

            val testMessage = "Mi Band Companion\nСвязь установлена! Ключ принят."
            val textBytes = testMessage.toByteArray(StandardCharsets.UTF_8)
            val packet = ByteArray(2 + textBytes.size)
            packet[0] = 0x09
            packet[1] = 0x01
            System.arraycopy(textBytes, 0, packet, 2, textBytes.size)

            val ok = manager.sendRawData(
                serviceUuid = MiBandConstants.UUID_SERVICE_ALERT_NOTIFICATION,
                charUuid = MiBandConstants.UUID_CHAR_NEW_ALERT,
                data = packet,
                withResponse = false
            )
            showBanner(if (ok) "Тестовое уведомление отправлено на браслет" else "Сбой отправки пакета")
        }
    }

    fun startScreenMirroring(resultCode: Int, data: Intent) {
        val context = getApplication<Application>()
        val intent = Intent(context, ScreenMirroringService::class.java).apply {
            action = ScreenMirroringService.ACTION_START_PROJECTION
            putExtra(ScreenMirroringService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenMirroringService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _uiState.value = _uiState.value.copy(isScreenMirroringActive = true)
    }

    fun stopScreenMirroring() {
        val context = getApplication<Application>()
        val intent = Intent(context, ScreenMirroringService::class.java).apply {
            action = ScreenMirroringService.ACTION_STOP_PROJECTION
        }
        context.startService(intent)
        _uiState.value = _uiState.value.copy(isScreenMirroringActive = false)
    }

    private fun showBanner(message: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(statusBannerMessage = message)
            delay(3500)
            _uiState.value = _uiState.value.copy(statusBannerMessage = null)
        }
    }
}
