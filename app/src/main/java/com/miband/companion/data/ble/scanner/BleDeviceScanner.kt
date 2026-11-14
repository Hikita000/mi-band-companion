package com.miband.companion.data.ble.scanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import com.miband.companion.data.ble.protocol.MiBandConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Модель обнаруженного BLE-устройства для отображения в UI.
 */
data class ScannedBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isBonded: Boolean = false
)

/**
 * Энергоэффективный сканер устройств Bluetooth Low Energy.
 * Ограничивает время активного сканирования и использует аппаратные фильтры контроллера.
 */
@SuppressLint("MissingPermission")
class BleDeviceScanner(
    private val bluetoothAdapter: BluetoothAdapter
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanTimeoutJob: Job? = null

    private val _discoveredDevices = MutableStateFlow<List<ScannedBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ScannedBleDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    companion object {
        private const val TAG = "BleDeviceScanner"
        private const val SCAN_TIMEOUT_MS = 12_000L
    }

    /**
     * Загружает список устройств, уже привязанных в настройках Bluetooth системы Android,
     * и запускает энергоэффективное BLE-сканирование с фильтрацией по сервисам Huami.
     */
    fun startScan(useStrictHuamiFilter: Boolean = false) {
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth выключен, сканирование невозможно.")
            return
        }

        stopScan()

        // Предзагрузка уже сопряженных устройств (мгновенное обнаружение, 0% расхода батареи)
        val initialList = mutableListOf<ScannedBleDevice>()
        try {
            bluetoothAdapter.bondedDevices?.forEach { device ->
                val deviceName = device.name ?: "Неизвестное устройство"
                if (deviceName.contains("Band", ignoreCase = true) ||
                    deviceName.contains("Xiaomi", ignoreCase = true) ||
                    deviceName.contains("Amazfit", ignoreCase = true) ||
                    !useStrictHuamiFilter
                ) {
                    initialList.add(
                        ScannedBleDevice(
                            name = deviceName,
                            address = device.address,
                            rssi = -50,
                            isBonded = true
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Нет разрешения BLUETOOTH_CONNECT для чтения сопряженных устройств: ${e.message}")
        }

        _discoveredDevices.value = initialList

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        val filters = if (useStrictHuamiFilter) {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(MiBandConstants.UUID_SERVICE_MIBAND_CUSTOM))
                    .build()
            )
        } else {
            emptyList()
        }

        // Балансный режим сканирования для минимизации нагрузки на радиомодуль
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0L)
            .build()

        try {
            _isScanning.value = true
            scanner.startScan(filters, settings, scanCallback)
            Log.i(TAG, "BLE сканирование запущено (strictFilter=$useStrictHuamiFilter).")

            // Автоматическая остановка через 12 секунд для экономии заряда батареи
            scanTimeoutJob = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                stopScan()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска BLE сканера: ${e.message}")
            _isScanning.value = false
        }
    }

    /**
     * Останавливает процесс активного радиосканирования.
     */
    fun stopScan() {
        scanTimeoutJob?.cancel()
        if (_isScanning.value) {
            try {
                bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка при остановке сканера: ${e.message}")
            }
            _isScanning.value = false
            Log.i(TAG, "BLE сканирование остановлено.")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            val name = try {
                device.name ?: result.scanRecord?.deviceName ?: "BLE Device"
            } catch (e: SecurityException) {
                "BLE Device"
            }
            val address = device.address

            // Фильтруем безымянные шумовые маяки, если имя пустое и это не наш браслет
            val currentList = _discoveredDevices.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.address == address }

            val item = ScannedBleDevice(
                name = name,
                address = address,
                rssi = result.rssi,
                isBonded = existingIndex >= 0 && currentList[existingIndex].isBonded
            )

            if (existingIndex >= 0) {
                currentList[existingIndex] = item
            } else {
                currentList.add(item)
            }

            // Сортируем: сначала сопряженные и с лучшим уровнем сигнала RSSI
            _discoveredDevices.value = currentList.sortedWith(
                compareByDescending<ScannedBleDevice> { it.isBonded }
                    .thenByDescending { it.rssi }
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Аппаратный сбой BLE сканирования. Код: $errorCode")
            _isScanning.value = false
        }
    }
}
