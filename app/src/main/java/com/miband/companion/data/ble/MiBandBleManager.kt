package com.miband.companion.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.miband.companion.data.ble.protocol.MiBand4Crypto
import com.miband.companion.data.ble.protocol.MiBandConstants
import com.miband.companion.data.ble.queue.BleGattQueue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import java.util.UUID

/**
 * Состояния соединения с браслетом Mi Smart Band 4.
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object ConnectedGatt : ConnectionState()
    object Authenticating : ConnectionState()
    object Authenticated : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Низкоуровневый менеджер взаимодействия с Mi Smart Band 4 по протоколу BLE GATT.
 */
@SuppressLint("MissingPermission")
class MiBandBleManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gattQueue = BleGattQueue(dispatcher)

    private var bluetoothGatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null

    // Текущий сохраненный MAC-адрес и Auth Key браслета
    private var targetDeviceAddress: String? = null
    private var authKeyHex: String = MiBandConstants.DEFAULT_AUTH_KEY_HEX

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Поток телеметрии: количество шагов (реальное время)
    private val _realtimeSteps = MutableStateFlow(0)
    val realtimeSteps: StateFlow<Int> = _realtimeSteps.asStateFlow()

    // Поток телеметрии: уровень заряда батареи (%)
    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    // Поток телеметрии: пульс в реальном времени (BPM)
    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    // Поток системных событий нажатия экранных кнопок и тапов по браслету
    private val _buttonEvents = MutableSharedFlow<Byte>()
    val buttonEvents: SharedFlow<Byte> = _buttonEvents.asSharedFlow()

    companion object {
        private const val TAG = "MiBandBleManager"
        private const val RECONNECT_DELAY_MS = 4000L
    }

    /**
     * Запуск соединения с браслетом по MAC-адресу с указанием Auth Key.
     */
    fun connect(macAddress: String, customAuthKeyHex: String? = null) {
        targetDeviceAddress = macAddress
        if (!customAuthKeyHex.isNullOrBlank()) {
            authKeyHex = customAuthKeyHex
        }

        reconnectJob?.cancel()
        disconnectCurrentGatt()

        if (!bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth адаптер выключен на смартфоне")
            return
        }

        val device: BluetoothDevice = try {
            bluetoothAdapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            _connectionState.value = ConnectionState.Error("Невалидный MAC-адрес: $macAddress")
            return
        }

        _connectionState.value = ConnectionState.Connecting
        Log.i(TAG, "Подключение к GATT устройству: $macAddress")

        // autoConnect = false для мгновенного первичного установления сокета через Direct Connect
        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    /**
     * Принудительный разрыв связи и освобождение аппаратных ресурсов GATT.
     */
    fun disconnect() {
        targetDeviceAddress = null
        reconnectJob?.cancel()
        disconnectCurrentGatt()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun disconnectCurrentGatt() {
        gattQueue.reset()
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка закрытия сокета GATT: ${e.message}")
            }
        }
        bluetoothGatt = null
    }

    /**
     * Попытка автоматического переподключения при обрыве связи с задержкой для стабилизации стека.
     */
    private fun scheduleReconnect() {
        val address = targetDeviceAddress ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.w(TAG, "Связь потеряна. Ожидание $RECONNECT_DELAY_MS мс перед повторной попыткой...")
            _connectionState.value = ConnectionState.Disconnected
            delay(RECONNECT_DELAY_MS)
            connect(address, authKeyHex)
        }
    }

    /**
     * Обработчик событий ядра GATT Bluetooth.
     */
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Аппаратная ошибка GATT: $status")
                scheduleReconnect()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT подключен. Старт обнаружения сервисов...")
                    _connectionState.value = ConnectionState.ConnectedGatt
                    // Небольшая задержка перед discoverServices критически важна на чипсетах MediaTek/Qualcomm
                    scope.launch {
                        delay(600)
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT отключен удаленной стороной.")
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Ошибка обнаружения сервисов GATT: $status")
                scheduleReconnect()
                return
            }

            Log.i(TAG, "Сервисы успешно обнаружены. Инициализация рукопожатия...")
            scope.launch {
                startAuthenticationSequence(gatt)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Android 13+ сигнатура
            handleIncomingNotification(gatt, characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Устаревшая сигнатура для Android 12 и ниже
            @Suppress("DEPRECATION")
            handleIncomingNotification(gatt, characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            gattQueue.notifyOperationCompleted(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            gattQueue.notifyOperationCompleted(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "Согласован новый размер MTU: $mtu (статус=$status)")
            gattQueue.notifyOperationCompleted(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * Пошаговый сценарий аутентификации Huami:
     * 1. Включение Notify на характеристике аутентификации (00000009...)
     * 2. Отправка команды запроса challenge: [0x02, 0x00]
     */
    private suspend fun startAuthenticationSequence(gatt: BluetoothGatt) {
        _connectionState.value = ConnectionState.Authenticating

        val authService = gatt.getService(MiBandConstants.UUID_SERVICE_AUTH)
        if (authService == null) {
            Log.e(TAG, "Сервис аутентификации ${MiBandConstants.UUID_SERVICE_AUTH} не найден на устройстве")
            _connectionState.value = ConnectionState.Error("Сервис Auth не найден")
            return
        }

        val authChar = authService.getCharacteristic(MiBandConstants.UUID_CHAR_AUTH)
        if (authChar == null) {
            Log.e(TAG, "Характеристика аутентификации ${MiBandConstants.UUID_CHAR_AUTH} отсутствует")
            _connectionState.value = ConnectionState.Error("Характеристика Auth не найдена")
            return
        }

        // Включение нотификаций на уровне локального стека Android
        gatt.setCharacteristicNotification(authChar, true)

        // Включение нотификаций на уровне прошивки браслета через дескриптор CCCD
        val descriptor = authChar.getDescriptor(MiBandConstants.UUID_DESCRIPTOR_CCCD)
        if (descriptor != null) {
            val descriptorSuccess = gattQueue.writeDescriptor(
                gatt,
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
            if (!descriptorSuccess) {
                Log.e(TAG, "Сбой записи CCCD дескриптора аутентификации")
                scheduleReconnect()
                return
            }
        }

        Log.i(TAG, "CCCD дескриптор включен. Запрос случайного challenge числа...")
        // Запрос challenge: отправка байтов [0x02, 0x00]
        val writeRequestSuccess = gattQueue.writeCharacteristic(
            gatt,
            authChar,
            MiBandConstants.AUTH_REQUEST_RANDOM_AUTH_NUMBER
        )

        if (!writeRequestSuccess) {
            Log.e(TAG, "Сбой отправки запроса challenge [0x02, 0x00]")
            scheduleReconnect()
        }
    }

    /**
     * Центральный диспетчер входящих пакетов нотификаций от браслета.
     */
    private fun handleIncomingNotification(gatt: BluetoothGatt, uuid: UUID, data: ByteArray?) {
        if (data == null || data.isEmpty()) return

        when (uuid) {
            MiBandConstants.UUID_CHAR_AUTH -> {
                handleAuthResponse(gatt, data)
            }
            MiBandConstants.UUID_CHAR_REALTIME_STEPS -> {
                parseRealtimeSteps(data)
            }
            MiBandConstants.UUID_CHAR_BATTERY_INFO -> {
                parseBatteryInfo(data)
            }
            MiBandConstants.UUID_CHAR_HEART_RATE_MEASUREMENT -> {
                parseHeartRate(data)
            }
            MiBandConstants.UUID_CHAR_DEVICE_EVENT -> {
                parseDeviceEvent(data)
            }
        }
    }

    /**
     * Обработка криптографических ответов в процессе рукопожатия:
     * - [0x10, 0x02, 0x01, ...16 байт случайного challenge...]
     * - [0x10, 0x03, 0x01] - Успех авторизации
     * - [0x10, 0x03, 0x04] - Ошибка авторизации (неверный ключ)
     */
    private fun handleAuthResponse(gatt: BluetoothGatt, data: ByteArray) {
        if (data.size < 3) return

        val responseHeader = data[0]
        val commandOpcode = data[1]
        val statusCode = data[2]

        if (responseHeader != MiBandConstants.AUTH_RESPONSE_HEADER) return

        // Ответ на запрос challenge (0x02)
        if (commandOpcode == MiBandConstants.AUTH_REQUEST_RANDOM_AUTH_NUMBER[0]) {
            if (statusCode == MiBandConstants.AUTH_RESPONSE_STATUS_SUCCESS && data.size >= 19) {
                // Извлечение 16 байт challenge начиная с индекса 3
                val challenge = ByteArray(16)
                System.arraycopy(data, 3, challenge, 0, 16)
                Log.i(TAG, "Challenge получен от браслета: ${MiBand4Crypto.bytesToHex(challenge)}")

                scope.launch {
                    try {
                        val keyBytes = MiBand4Crypto.hexStringToByteArray(authKeyHex)
                        // Шифрование challenge алгоритмом AES-128 ECB NoPadding
                        val encryptedChallenge = MiBand4Crypto.encryptChallenge(challenge, keyBytes)
                        val responsePacket = MiBand4Crypto.buildAuthResponsePacket(encryptedChallenge)

                        val authChar = gatt.getService(MiBandConstants.UUID_SERVICE_AUTH)
                            ?.getCharacteristic(MiBandConstants.UUID_CHAR_AUTH)

                        if (authChar != null) {
                            Log.i(TAG, "Отправка зашифрованного challenge ответа в браслет...")
                            gattQueue.writeCharacteristic(gatt, authChar, responsePacket)
                        }
                    } catch (e: GeneralSecurityException) {
                        Log.e(TAG, "Криптографическая ошибка шифрования challenge: ${e.message}")
                        _connectionState.value = ConnectionState.Error("Криптографический сбой AES")
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Некорректный Auth Key: ${e.message}")
                        _connectionState.value = ConnectionState.Error("Неверный формат Auth Key")
                    }
                }
            } else {
                Log.e(TAG, "Браслет отклонил запрос challenge со статусом: $statusCode")
                _connectionState.value = ConnectionState.Error("Challenge отклонен браслетом")
            }
        }

        // Ответ на отправку зашифрованного challenge (0x03)
        if (commandOpcode == MiBandConstants.AUTH_SEND_ENCRYPTED_AUTH_NUMBER_FLAG) {
            if (statusCode == MiBandConstants.AUTH_RESPONSE_STATUS_SUCCESS) {
                Log.i(TAG, "АВТОРИЗАЦИЯ УСПЕШНА! Mi Smart Band 4 разблокирован.")
                _connectionState.value = ConnectionState.Authenticated
                scope.launch {
                    onHandshakeCompleted(gatt)
                }
            } else {
                Log.e(TAG, "АВТОРИЗАЦИЯ ПРОВАЛЕНА. Код ошибки браслета: $statusCode. Проверьте Auth Key!")
                _connectionState.value = ConnectionState.Error("Неверный Auth Key ($statusCode)")
            }
        }
    }

    /**
     * Действия после успешной авторизации: согласование MTU 517 и подписка на сенсоры.
     */
    private suspend fun onHandshakeCompleted(gatt: BluetoothGatt) {
        // Запрос максимального MTU для быстрой потоковой передачи кадров и длинных строк
        gattQueue.requestMtu(gatt, MiBandConstants.DESIRED_MTU_SIZE)

        // Подписка на изменение количества шагов в реальном времени
        enableNotification(gatt, MiBandConstants.UUID_SERVICE_MIBAND_CUSTOM, MiBandConstants.UUID_CHAR_REALTIME_STEPS)

        // Подписка на статус батареи
        enableNotification(gatt, MiBandConstants.UUID_SERVICE_MIBAND_CUSTOM, MiBandConstants.UUID_CHAR_BATTERY_INFO)

        // Подписка на системные события браслета (управление плеером, свайпы)
        enableNotification(gatt, MiBandConstants.UUID_SERVICE_MIBAND_CUSTOM, MiBandConstants.UUID_CHAR_DEVICE_EVENT)
    }

    private suspend fun enableNotification(gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID) {
        val char = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(MiBandConstants.UUID_DESCRIPTOR_CCCD) ?: return
        gattQueue.writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    /**
     * Побитовый парсер шагов (Little-Endian 32-bit uint).
     * [1..4] байты содержат общее число шагов.
     */
    private fun parseRealtimeSteps(data: ByteArray) {
        if (data.size >= 5) {
            val steps = (data[1].toInt() and 0xFF) or
                    ((data[2].toInt() and 0xFF) shl 8) or
                    ((data[3].toInt() and 0xFF) shl 16) or
                    ((data[4].toInt() and 0xFF) shl 24)
            _realtimeSteps.value = steps
        }
    }

    /**
     * Парсер информации о батарее. Байт [1] содержит процент заряда от 0 до 100.
     */
    private fun parseBatteryInfo(data: ByteArray) {
        if (data.size >= 2) {
            val level = data[1].toInt() and 0xFF
            _batteryLevel.value = level
        }
    }

    /**
     * Парсер частоты сердечных сокращений по стандарту BLE Heart Rate Service (0x180D).
     * Если нулевой бит флагов равен 0 — значение 8-битное (байт [1]), иначе 16-битное.
     */
    private fun parseHeartRate(data: ByteArray) {
        if (data.isEmpty()) return
        val flags = data[0].toInt()
        val is16Bit = (flags and 0x01) != 0
        val bpm = if (is16Bit && data.size >= 3) {
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else if (data.size >= 2) {
            data[1].toInt() and 0xFF
        } else 0

        _heartRate.value = bpm
    }

    /**
     * Обработчик событий браслета (тапы, управление плеером).
     */
    private fun parseDeviceEvent(data: ByteArray) {
        if (data.isNotEmpty()) {
            scope.launch {
                _buttonEvents.emit(data[0])
            }
        }
    }

    /**
     * Отправка команды вызова браслета (вибрация для поиска устройства).
     */
    suspend fun triggerVibration(strong: Boolean = true): Boolean {
        val gatt = bluetoothGatt ?: return false
        val alertService = gatt.getService(MiBandConstants.UUID_SERVICE_IMMEDIATE_ALERT) ?: return false
        val alertChar = alertService.getCharacteristic(MiBandConstants.UUID_CHAR_ALERT_LEVEL) ?: return false

        val payload = if (strong) MiBandConstants.ALERT_LEVEL_STRONG else MiBandConstants.ALERT_LEVEL_OFF
        return gattQueue.writeCharacteristic(gatt, alertChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    /**
     * Отправка сырых байтовых пакетов (для кадров стриминга экрана или кастомных команд).
     */
    suspend fun sendRawData(serviceUuid: UUID, charUuid: UUID, data: ByteArray, withResponse: Boolean = false): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return gattQueue.writeCharacteristic(gatt, char, data, writeType)
    }
}
