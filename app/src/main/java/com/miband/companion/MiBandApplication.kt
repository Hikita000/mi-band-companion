package com.miband.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Главный класс приложения.
 * Выполняет инициализацию жизненного цикла, регистрирует глобальные каналы уведомлений
 * для фонового BLE-сервиса и сервиса захвата экрана MediaProjection.
 */
class MiBandApplication : Application() {

    companion object {
        const val CHANNEL_BLE_SERVICE_ID = "channel_ble_service"
        const val CHANNEL_PROJECTION_SERVICE_ID = "channel_projection_service"
        
        // Синглтон-ссылка на контекст приложения для безопасного доступа из фоновых обработчиков
        private var instance: MiBandApplication? = null

        fun getAppContext(): Context {
            return instance?.applicationContext
                ?: throw IllegalStateException("MiBandApplication еще не инициализирован в системе.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    /**
     * Создает необходимые каналы уведомлений для фоновых служб Android 8.0+ (API 26+).
     * Разделение на каналы гарантирует независимый контроль приоритетов в настройках системы.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Канал постоянного BLE соединения с браслетом
            val bleChannel = NotificationChannel(
                CHANNEL_BLE_SERVICE_ID,
                "Связь с Mi Smart Band 4",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянное удержание BLE GATT сокета и обмен телеметрией"
                setShowBadge(false)
            }

            // Канал видеостриминга / захвата экрана
            val projectionChannel = NotificationChannel(
                CHANNEL_PROJECTION_SERVICE_ID,
                "Трансляция экрана на браслет",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Захват экрана через MediaProjection и передача кадров 120x240"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(bleChannel)
            notificationManager.createNotificationChannel(projectionChannel)
        }
    }
}
