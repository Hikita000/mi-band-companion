package com.miband.companion.data.projection

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.miband.companion.MiBandApplication
import com.miband.companion.data.ble.ConnectionState
import com.miband.companion.data.ble.protocol.MiBandConstants
import com.miband.companion.data.ble.service.MiBandBleService
import com.miband.companion.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Сервис реального времени для захвата экрана и потоковой передачи кадров на Mi Smart Band 4.
 */
class ScreenMirroringService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var streamingJob: Job? = null

    companion object {
        private const val TAG = "ScreenMirroringService"
        private const val NOTIFICATION_ID = 2002

        const val ACTION_START_PROJECTION = "com.miband.companion.action.START_PROJECTION"
        const val ACTION_STOP_PROJECTION = "com.miband.companion.action.STOP_PROJECTION"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        // Троттлинг: 3 FPS (333 мс между кадрами) для предотвращения забивания BLE буфера
        private const val FRAME_INTERVAL_MS = 333L
        
        // Размер одного чанка при передаче через MTU 517 (оставляем запас под заголовок GATT)
        private const val CHUNK_SIZE = 240
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundWithNotification()
                    initMediaProjection(resultCode, resultData)
                } else {
                    Log.e(TAG, "Отклонено разрешение MediaProjection пользователем.")
                    stopSelf()
                }
            }
            ACTION_STOP_PROJECTION -> {
                stopProjection()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, MiBandApplication.CHANNEL_PROJECTION_SERVICE_ID)
            .setContentTitle("Зеркалирование экрана")
            .setContentText("Трансляция интерфейса на дисплей браслета...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("WrongConstant")
    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        // Инициализируем ImageReader с аппаратным размером дисплея браслета (120x240)
        imageReader = ImageReader.newInstance(
            MiBandConstants.SCREEN_WIDTH,
            MiBandConstants.SCREEN_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )

        // Виртуальный дисплей с плотностью 160 dpi
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MiBandVirtualDisplay",
            MiBandConstants.SCREEN_WIDTH,
            MiBandConstants.SCREEN_HEIGHT,
            160,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        startFrameProcessingLoop()
    }

    /**
     * Цикл захвата кадра, конвертации и отправки с троттлингом частоты.
     */
    private fun startFrameProcessingLoop() {
        streamingJob?.cancel()
        streamingJob = serviceScope.launch {
            while (true) {
                val startTime = System.currentTimeMillis()

                val bleManager = MiBandBleService.activeInstance?.bleManager
                if (bleManager == null || bleManager.connectionState.value !is ConnectionState.Authenticated) {
                    delay(1000)
                    continue
                }

                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    processAndSendImage(image)
                    image.close()
                }

                val elapsedTime = System.currentTimeMillis() - startTime
                val sleepTime = FRAME_INTERVAL_MS - elapsedTime
                if (sleepTime > 0) {
                    delay(sleepTime)
                }
            }
        }
    }

    /**
     * Конвертирует Image из ImageReader в Bitmap, затем в монохромный байтовый массив и шлет чанками.
     */
    private suspend fun processAndSendImage(image: Image) {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * MiBandConstants.SCREEN_WIDTH

        val bitmap = Bitmap.createBitmap(
            MiBandConstants.SCREEN_WIDTH + rowPadding / pixelStride,
            MiBandConstants.SCREEN_HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Обрезаем паддинг строк GPU, если он присутствует
        val cleanBitmap = if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, MiBandConstants.SCREEN_WIDTH, MiBandConstants.SCREEN_HEIGHT)
            bitmap.recycle()
            cropped
        } else {
            bitmap
        }

        val frameBytes = BandDisplayConverter.convertToMonochromeFrame(cleanBitmap)
        cleanBitmap.recycle()

        sendFrameOverBle(frameBytes)
    }

    /**
     * Нарезка 3600-байтового кадра на чанки и отправка в BLE без ожидания подтверждения (Write Without Response).
     */
    private suspend fun sendFrameOverBle(frameData: ByteArray) {
        val bleManager = MiBandBleService.activeInstance?.bleManager ?: return

        var offset = 0
        var chunkIndex = 0

        while (offset < frameData.size) {
            val bytesRemaining = frameData.size - offset
            val currentChunkSize = minOf(CHUNK_SIZE, bytesRemaining)

            // Формат пакета чанка:
            // [0] = Порядковый номер чанка в текущем кадре
            // [1..N] = Сырые данные монохромного среза экрана
            val chunkPacket = ByteArray(1 + currentChunkSize)
            chunkPacket[0] = chunkIndex.toByte()
            System.arraycopy(frameData, offset, chunkPacket, 1, currentChunkSize)

            bleManager.sendRawData(
                serviceUuid = MiBandConstants.UUID_SERVICE_MIBAND_CUSTOM,
                charUuid = MiBandConstants.UUID_CHAR_CHUNKED_TRANSFER,
                data = chunkPacket,
                withResponse = false
            )

            offset += currentChunkSize
            chunkIndex++
            // Микропауза 5 мс для исключения переполнения аппаратного кольцевого буфера BLE
            delay(5)
        }
    }

    private fun stopProjection() {
        streamingJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        Log.i(TAG, "Стриминг экрана остановлен.")
    }

    override fun onDestroy() {
        stopProjection()
        serviceScope.cancel()
        super.onDestroy()
    }
}
