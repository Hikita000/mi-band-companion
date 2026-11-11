package com.miband.companion.data.ble.queue

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
 * Предотвращает ошибки BLE-стека Android, вызванные одновременными асинхронными вызовами.
 */
class BleGattQueue(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val mutex = Mutex()
    private var pendingOperationDeferred: CompletableDeferred<Boolean>? = null

    companion object {
        private const val TAG = "BleGattQueue"
        private const val DEFAULT_TIMEOUT_MS = 5000L
    }

    /**
     * Помещает операцию записи дескриптора в очередь и приостанавливает вызов до завершения onDescriptorWrite.
     */
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
                status == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = payload
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            if (!initiated) {
                Log.e(TAG, "Не удалось инициировать запись дескриптора ${descriptor.uuid}")
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
     * Помещает операцию записи характеристики в очередь и ожидает onCharacteristicWrite.
     */
    suspend fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            pendingOperationDeferred = deferred

            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(characteristic, payload, writeType)
                status == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            if (!initiated) {
                Log.e(TAG, "Не удалось инициировать запись характеристики ${characteristic.uuid}")
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
     * Запрос расширения MTU с блокировкой очереди до ответа onMtuChanged.
     */
    suspend fun requestMtu(
        gatt: BluetoothGatt,
        mtu: Int
    ): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            pendingOperationDeferred = deferred

            val initiated = gatt.requestMtu(mtu)
            if (!initiated) {
                Log.e(TAG, "Контроллер отклонил запрос MTU: $mtu")
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
     * Сигнализирует очереди о получении аппаратного ответа от периферийного устройства.
     */
    fun notifyOperationCompleted(success: Boolean) {
        pendingOperationDeferred?.complete(success)
    }

    /**
     * Принудительно очищает и разблокирует очередь при обрыве соединения.
     */
    fun reset() {
        pendingOperationDeferred?.complete(false)
        pendingOperationDeferred = null
    }
}
