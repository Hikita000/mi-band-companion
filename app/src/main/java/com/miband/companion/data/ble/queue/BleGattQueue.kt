package com.miband.companion.data.ble.queue

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Потокобезопасная последовательная очередь GATT-операций.
 * Автоматически согласовывает допустимый writeType для каждой характеристики.
 */
@SuppressLint("MissingPermission")
class BleGattQueue(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val mutex = Mutex()
    private var pendingOperationDeferred: CompletableDeferred<Boolean>? = null

    companion object {
        private const val TAG = "BleGattQueue"
        private const val DEFAULT_TIMEOUT_MS = 5000L
    }

    suspend fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        payload: ByteArray
    ): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            pendingOperationDeferred = deferred

            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeDescriptor(descriptor, payload)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "writeDescriptor вернул код: $status")
                }
                status == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = payload
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            if (!initiated) {
                pendingOperationDeferred = null
                return@withContext false
            }

            val result = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                deferred.await()
            } ?: false

            pendingOperationDeferred = null
            result
        }
    }

    /**
     * Запись характеристики с автоматическим выбором поддерживаемого типа записи.
     */
    suspend fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        preferredWriteType: Int? = null
    ): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val props = characteristic.properties
            val resolvedWriteType = when {
                preferredWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE &&
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 -> {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                preferredWriteType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT &&
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 -> {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 -> {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 -> {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                else -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            val deferred = CompletableDeferred<Boolean>()
            pendingOperationDeferred = deferred

            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(characteristic, payload, resolvedWriteType)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "writeCharacteristic(${characteristic.uuid}) code=$status, type=$resolvedWriteType")
                }
                status == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                characteristic.writeType = resolvedWriteType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            if (!initiated) {
                pendingOperationDeferred = null
                return@withContext false
            }

            val result = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                deferred.await()
            } ?: false

            pendingOperationDeferred = null
            result
        }
    }

    suspend fun requestMtu(
        gatt: BluetoothGatt,
        mtu: Int
    ): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            pendingOperationDeferred = deferred

            val initiated = gatt.requestMtu(mtu)
            if (!initiated) {
                pendingOperationDeferred = null
                return@withContext false
            }

            val result = withTimeoutOrNull(DEFAULT_TIMEOUT_MS) {
                deferred.await()
            } ?: false

            pendingOperationDeferred = null
            result
        }
    }

    fun notifyOperationCompleted(success: Boolean) {
        pendingOperationDeferred?.complete(success)
    }

    fun reset() {
        pendingOperationDeferred?.complete(false)
        pendingOperationDeferred = null
    }
}
