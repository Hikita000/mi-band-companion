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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object ConnectedGatt : ConnectionState()
    object Authenticating : ConnectionState()
    object Authenticated : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

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
    private var discoveryRetryDone = false
    private var forcePairMode = false
    private var authRejectedStopReconnect = false

    // Состояние поиска протокола 01 81 -> 82 00 -> 83 00
    private var init0181Index = 0
    private var sessionCandidateIndex = 0
    private var sessionSaltA: ByteArray? = null

    private val init0181Requests = listOf(
        "0181 (2b)" to byteArrayOf(0x01, 0x81.toByte()),
        "018100 (3b)" to byteArrayOf(0x01, 0x81.toByte(), 0x00),
        "0181+zeros (18b)" to (byteArrayOf(0x01, 0x81.toByte()) + ByteArray(16))
    )

    private var targetDeviceAddress: String? = null
    private var authKeyHex: String = MiBandConstants.DEFAULT_AUTH_KEY_HEX

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _realtimeSteps = MutableStateFlow(0)
    val realtimeSteps: StateFlow<Int> = _realtimeSteps.asStateFlow()

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _buttonEvents = MutableSharedFlow<Byte>()
    val buttonEvents: SharedFlow<Byte> = _buttonEvents.asSharedFlow()

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        private const val TAG = "MiBandBleManager"
        private const val RECONNECT_DELAY_MS = 4000L
        private const val TOTAL_SESSION_CANDIDATES = 5
    }

    private fun logBle(message: String) {
        Log.d(TAG, message)
        val line = "${timeFormat.format(Date())} | $message"
        _debugLogs.value = (_debugLogs.value + line).takeLast(60)
    }

    fun connect(macAddress: String, customAuthKeyHex: String? = null, forcePair: Boolean = false) {
        targetDeviceAddress = macAddress
        if (!customAuthKeyHex.isNullOrBlank()) {
            authKeyHex = customAuthKeyHex
        }

        forcePairMode = forcePair
        authRejectedStopReconnect = false
        reconnectJob?.cancel()
        disconnectCurrentGatt()

        if (!bluetoothAdapter.isEnabled) {
            logBle("ERR: Bluetooth выключен")
            _connectionState.value = ConnectionState.Error("Bluetooth выключен")
            return
        }

        val device: BluetoothDevice = try {
            bluetoothAdapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            logBle("ERR: Невалидный MAC $macAddress")
            _connectionState.value = ConnectionState.Error("Невалидный MAC: $macAddress")
            return
        }

        discoveryRetryDone = false
        init0181Index = 0
        sessionCandidateIndex = 0
        sessionSaltA = null
        _connectionState.value = ConnectionState.Connecting
        val keyPreview = "${authKeyHex.take(4)}...${authKeyHex.takeLast(4)}"
        logBle("Connect -> $macAddress (key=$keyPreview)")

        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun disconnect() {
        targetDeviceAddress = null
        authRejectedStopReconnect = false
        reconnectJob?.cancel()
        disconnectCurrentGatt()
        _connectionState.value = ConnectionState.Disconnected
        logBle("Отключено пользователем")
    }

    private fun disconnectCurrentGatt() {
        gattQueue.reset()
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка закрытия GATT: ${e.message}")
            }
        }
        bluetoothGatt = null
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            val result = refreshMethod.invoke(gatt) as? Boolean ?: false
            logBle("GATT cache refresh() = $result")
        } catch (e: Exception) {
            logBle("refresh() недоступен: ${e.message}")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (authRejectedStopReconnect) {
            logBle("Реконнект остановлен (auth rejected)")
            return
        }
        val address = targetDeviceAddress ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            logBle("Реконнект через 4с ($reason)")
            disconnectCurrentGatt()
            _connectionState.value = ConnectionState.Disconnected
            delay(RECONNECT_DELAY_MS)
            connect(address, authKeyHex, forcePair = false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logBle("ConnState: status=$status, state=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                refreshGattCache(gatt)
                scheduleReconnect("GATT err=$status")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.ConnectedGatt
                    scope.launch {
                        delay(700)
                        logBle("Запуск discoverServices()...")
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    scheduleReconnect("Remote disconnected")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logBle("ServicesDiscovered: status=$status, count=${gatt.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                refreshGattCache(gatt)
                scheduleReconnect("Discover err=$status")
                return
            }

            val authService = gatt.getService(MiBandConstants.UUID_SERVICE_AUTH)
            if (authService == null && !discoveryRetryDone) {
                discoveryRetryDone = true
                logBle("Auth сервис не найден, сброс кэша и повтор...")
                refreshGattCache(gatt)
                scope.launch {
                    delay(800)
                    gatt.discoverServices()
                }
                return
            }

            scope.launch {
                startAuthenticationSequence(gatt)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingNotification(gatt, characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleIncomingNotification(gatt, characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logBle("CharWrite err=$status")
            }
            gattQueue.notifyOperationCompleted(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            logBle("DescWrite status=$status")
            gattQueue.notifyOperationCompleted(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            logBle("MTU согласован: $mtu (status=$status)")
            gattQueue.notifyOperationCompleted(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private suspend fun startAuthenticationSequence(gatt: BluetoothGatt) {
        _connectionState.value = ConnectionState.Authenticating

        val authService = gatt.getService(MiBandConstants.UUID_SERVICE_AUTH)
        if (authService == null) {
            logBle("ERR: Auth FEE1 не найден")
            _connectionState.value = ConnectionState.Error("Сервис Auth не найден")
            return
        }

        val authChar = authService.getCharacteristic(MiBandConstants.UUID_CHAR_AUTH)
        if (authChar == null) {
            logBle("ERR: Char 0009 не найден")
            _connectionState.value = ConnectionState.Error("Характеристика Auth не найдена")
            return
        }

        gatt.setCharacteristicNotification(authChar, true)

        val descriptor = authChar.getDescriptor(MiBandConstants.UUID_DESCRIPTOR_CCCD)
        if (descriptor != null) {
            val descOk = gattQueue.writeDescriptor(
                gatt,
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
            if (!descOk) {
                logBle("ERR: Сбой записи CCCD")
                scheduleReconnect("CCCD fail")
                return
            }
        }

        if (forcePairMode) {
            sendPairKeyPacket(gatt, authChar)
        } else {
            sendNext0181Request(gatt, authChar)
        }
    }

    private suspend fun sendPairKeyPacket(
        gatt: BluetoothGatt,
        authChar: BluetoothGattCharacteristic
    ) {
        try {
            val keyBytes = MiBand4Crypto.hexStringToByteArray(authKeyHex)
            val packet = ByteArray(2 + keyBytes.size)
            packet[0] = 0x01
            packet[1] = 0x00
            System.arraycopy(keyBytes, 0, packet, 2, keyBytes.size)

            logBle("TX PairKey (18b)")
            gattQueue.writeCharacteristic(gatt, authChar, packet)
        } catch (e: Exception) {
            logBle("ERR PairKey: ${e.message}")
            _connectionState.value = ConnectionState.Error("Ошибка формата ключа")
        }
    }

    private suspend fun sendNext0181Request(
        gatt: BluetoothGatt,
        authChar: BluetoothGattCharacteristic
    ) {
        if (init0181Index >= init0181Requests.size) {
            logBle("ERR: Ни один запрос 0181 не принят")
            authRejectedStopReconnect = true
            targetDeviceAddress = null
            _connectionState.value = ConnectionState.Error("Команда 0181 отклонена браслетом")
            return
        }
        val (label, cmd) = init0181Requests[init0181Index]
        logBle("TX [$label]: ${MiBand4Crypto.bytesToHex(cmd)}")
        val ok = gattQueue.writeCharacteristic(gatt, authChar, cmd)
        if (!ok) {
            scheduleReconnect("Write 0181 fail")
        }
    }

    private suspend fun sendChallengeRequest8200(
        gatt: BluetoothGatt,
        authChar: BluetoothGattCharacteristic
    ) {
        if (sessionCandidateIndex >= TOTAL_SESSION_CANDIDATES) {
            logBle("ERR: Все 5 формул сессионного ключа отклонены")
            authRejectedStopReconnect = true
            targetDeviceAddress = null
            _connectionState.value = ConnectionState.Error("Сессионный ключ 0181 не подошёл")
            return
        }
        val cmd = byteArrayOf(0x82.toByte(), 0x00)
        logBle("TX [cand=$sessionCandidateIndex]: 8200")
        val ok = gattQueue.writeCharacteristic(gatt, authChar, cmd)
        if (!ok) {
            scheduleReconnect("Write 8200 fail")
        }
    }

    private fun handleIncomingNotification(gatt: BluetoothGatt, uuid: UUID, data: ByteArray?) {
        if (data == null || data.isEmpty()) return

        when (uuid) {
            MiBandConstants.UUID_CHAR_AUTH -> {
                logBle("RX Auth: ${MiBand4Crypto.bytesToHex(data)}")
                handleAuthResponse(gatt, data)
            }
            MiBandConstants.UUID_CHAR_REALTIME_STEPS -> parseRealtimeSteps(data)
            MiBandConstants.UUID_CHAR_BATTERY_INFO -> parseBatteryInfo(data)
            MiBandConstants.UUID_CHAR_HEART_RATE_MEASUREMENT -> parseHeartRate(data)
            MiBandConstants.UUID_CHAR_DEVICE_EVENT -> parseDeviceEvent(data)
        }
    }

    private fun handleAuthResponse(gatt: BluetoothGatt, data: ByteArray) {
        if (data.size < 3) return
        if (data[0] != MiBandConstants.AUTH_RESPONSE_HEADER) return

        val authChar = gatt.getService(MiBandConstants.UUID_SERVICE_AUTH)
            ?.getCharacteristic(MiBandConstants.UUID_CHAR_AUTH) ?: return

        // Обработка ответа на 01 81: формат [10, 01, 81, status, ...16 байт...]
        if (data[1] == 0x01.toByte() && data.size >= 4 && data[2] == 0x81.toByte()) {
            val status0181 = data[3]
            if (status0181 == MiBandConstants.AUTH_RESPONSE_STATUS_SUCCESS && data.size >= 20) {
                val salt = ByteArray(16)
                System.arraycopy(data, 4, salt, 0, 16)
                sessionSaltA = salt
                sessionCandidateIndex = 0
                logBle("OK 0181! Получена соль (16b): ${MiBand4Crypto.bytesToHex(salt)}")
                scope.launch { sendChallengeRequest8200(gatt, authChar) }
            } else {
                logBle("0181[${init0181Index}] отклонён (status=$status0181)")
                init0181Index++
                scope.launch { sendNext0181Request(gatt, authChar) }
            }
            return
        }

        val commandOpcode = (data[1].toInt() and 0x7F).toByte()
        val statusCode = data[2]

        if (commandOpcode == 0x01.toByte()) {
            if (statusCode == MiBandConstants.AUTH_RESPONSE_STATUS_SUCCESS) {
                logBle("Ключ принят! Запрос 8200...")
                forcePairMode = false
                scope.launch { sendChallengeRequest8200(gatt, authChar) }
            } else {
                authRejectedStopReconnect = true
                _connectionState.value = ConnectionState.Error("Привязка отклонена ($statusCode)")
            }
            return
        }

        if (commandOpcode == 0x02.toByte()) {
            if (statusCode == MiBandConstants.AUTH_RESPONSE_STATUS_SUCCESS && data.size >= 19) {
                val challengeR = ByteArray(16)
                System.arraycopy(data, 3, challengeR, 0, 16)

                scope.launch {
                    try {
                        val authKey = MiBand4Crypto.hexStringToByteArray(authKeyHex)
                        val saltA = sessionSaltA ?: ByteArray(16)
                        val encryptedResp = computeSessionResponse(
                            candidate = sessionCandidateIndex,
                            authKey = authKey,
                            saltA = saltA,
                            challengeR = challengeR
                        )

                        val packet = ByteArray(2 + encryptedResp.size)
                        packet[0] = 0x83.toByte()
                        packet[1] = 0x00
                        System.arraycopy(encryptedResp, 0, packet, 2, encryptedResp.size)

                        logBle("TX Enc [cand=$sessionCandidateIndex]: ${MiBand4Crypto.bytesToHex(packet)}")
                        gattQueue.writeCharacteristic(gatt, authChar, packet)
                    } catch (e: GeneralSecurityException) {
                        logBle("ERR AES: ${e.message}")
                        _connectionState.value = ConnectionState.Error("Ошибка AES")
                    }
                }
            } else {
                logBle("8200 отклонён ($statusCode)")
                authRejectedStopReconnect = true
                _connectionState.value = ConnectionState.Error("Challenge 8200 отклонён ($statusCode)")
            }
            return
        }

        if (commandOpcode == 0x03.toByte()) {
            if (statusCode == MiBandConstants.AUTH_RESPONSE_STATUS_SUCCESS) {
                logBle("АВТОРИЗАЦИЯ ПРОЙДЕНА (cand=$sessionCandidateIndex)!")
                authRejectedStopReconnect = false
                _connectionState.value = ConnectionState.Authenticated
                scope.launch {
                    onHandshakeCompleted(gatt)
                }
            } else {
                logBle("cand=$sessionCandidateIndex отклонён на 8300 (status=$statusCode)")
                sessionCandidateIndex++
                scope.launch { sendChallengeRequest8200(gatt, authChar) }
            }
        }
    }

    /**
     * Проверяет криптографические способы получения ответа для 8300 из соли 0181 и challenge 8200.
     */
    private fun computeSessionResponse(
        candidate: Int,
        authKey: ByteArray,
        saltA: ByteArray,
        challengeR: ByteArray
    ): ByteArray {
        return when (candidate) {
            // 0: saltA = AES_ENC(authKey, sessionKey) -> расшифровываем sessionKey и шифруем challengeR
            0 -> {
                val sessionKey = aesEcb(Cipher.DECRYPT_MODE, authKey, saltA)
                aesEcb(Cipher.ENCRYPT_MODE, sessionKey, challengeR)
            }
            // 1: sessionKey = AES_ENC(authKey, saltA) -> шифруем challengeR сессионным ключом
            1 -> {
                val sessionKey = aesEcb(Cipher.ENCRYPT_MODE, authKey, saltA)
                aesEcb(Cipher.ENCRYPT_MODE, sessionKey, challengeR)
            }
            // 2: 0181 только разблокировал режим 82/83, шифрование идёт напрямую через authKey
            2 -> aesEcb(Cipher.ENCRYPT_MODE, authKey, challengeR)
            // 3: XOR challenge с saltA перед шифрованием authKey
            3 -> {
                val xored = ByteArray(16) { i -> (challengeR[i].toInt() xor saltA[i].toInt()).toByte() }
                aesEcb(Cipher.ENCRYPT_MODE, authKey, xored)
            }
            // 4: XOR authKey с saltA как сессионный ключ
            else -> {
                val sessionKey = ByteArray(16) { i -> (authKey[i].toInt() xor saltA[i].toInt()).toByte() }
                aesEcb(Cipher.ENCRYPT_MODE, sessionKey, challengeR)
            }
        }
    }

    private fun aesEcb(mode: Int, key: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(input)
    }

    private suspend fun onHandshakeCompleted(gatt: BluetoothGatt) {
        gattQueue.requestMtu(gatt, MiBandConstants.DESIRED_MTU_SIZE)
        enableNotification(gatt, MiBandConstants.UUID_SERVICE_MIBAND_CUSTOM, MiBandConstants.UUID_CHAR_REALTIME_STEPS)
        enableNotification(gatt, MiBandConstants.UUID_SERVICE_MIBAND_CUSTOM, MiBandConstants.UUID_CHAR_BATTERY_INFO)
        enableNotification(gatt, MiBandConstants.UUID_SERVICE_MIBAND_CUSTOM, MiBandConstants.UUID_CHAR_DEVICE_EVENT)
    }

    private suspend fun enableNotification(gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID) {
        val char = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(MiBandConstants.UUID_DESCRIPTOR_CCCD) ?: return
        gattQueue.writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    private fun parseRealtimeSteps(data: ByteArray) {
        if (data.size >= 5) {
            val steps = (data[1].toInt() and 0xFF) or
                    ((data[2].toInt() and 0xFF) shl 8) or
                    ((data[3].toInt() and 0xFF) shl 16) or
                    ((data[4].toInt() and 0xFF) shl 24)
            _realtimeSteps.value = steps
        }
    }

    private fun parseBatteryInfo(data: ByteArray) {
        if (data.size >= 2) {
            _batteryLevel.value = data[1].toInt() and 0xFF
        }
    }

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

    private fun parseDeviceEvent(data: ByteArray) {
        if (data.isNotEmpty()) {
            scope.launch { _buttonEvents.emit(data[0]) }
        }
    }

    suspend fun triggerVibration(strong: Boolean = true): Boolean {
        val gatt = bluetoothGatt ?: return false
        val alertChar = gatt.getService(MiBandConstants.UUID_SERVICE_IMMEDIATE_ALERT)
            ?.getCharacteristic(MiBandConstants.UUID_CHAR_ALERT_LEVEL) ?: return false

        val payload = if (strong) MiBandConstants.ALERT_LEVEL_STRONG else MiBandConstants.ALERT_LEVEL_OFF
        return gattQueue.writeCharacteristic(
            gatt,
            alertChar,
            payload,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        )
    }

    suspend fun sendRawData(
        serviceUuid: UUID,
        charUuid: UUID,
        data: ByteArray,
        withResponse: Boolean = false
    ): Boolean {
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
