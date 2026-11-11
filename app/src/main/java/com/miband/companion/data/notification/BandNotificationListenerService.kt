package com.miband.companion.data.notification

import android.app.Notification
import android.bluetooth.BluetoothGattCharacteristic
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.miband.companion.data.ble.ConnectionState
import com.miband.companion.data.ble.protocol.MiBandConstants
import com.miband.companion.data.ble.service.MiBandBleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

/**
 * Служба перехвата входящих уведомлений операционной системы Android.
 * Форматирует текст и передает его в BLE-контроллер для мгновенного отображения на Mi Smart Band 4.
 */
class BandNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "NotificationListener"

        // Игнорируемые системные пакеты Android для предотвращения спама на браслет
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.miband.companion",
            "com.google.android.gms"
        )

        // Идентификаторы категорий оповещения по спецификации Bluetooth Alert Notification Service
        private const val ALERT_CATEGORY_EMAIL: Byte = 0x01
        private const val ALERT_CATEGORY_SMS: Byte = 0x05
        private const val ALERT_CATEGORY_GENERIC: Byte = 0x09
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName
        if (IGNORED_PACKAGES.contains(packageName)) return

        // Фильтрация несъемных сервисных уведомлений (Foreground Services, медиаплееры, прогресс загрузок)
        if (!sbn.isClearable) return

        val notification = sbn.notification ?: return
        val extras: Bundle = notification.extras ?: return

        // Извлечение заголовка (автор сообщения / имя контакта)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""

        // Извлечение текста самого сообщения
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        // Пропускаем пустые уведомления
        if (title.isEmpty() && text.isEmpty()) return

        val appName = getApplicationLabel(packageName)
        val category = resolveAlertCategory(packageName)

        Log.d(TAG, "Перехвачено уведомление: [$appName] $title: $text")

        scope.launch {
            dispatchNotificationToBand(category, appName, title, text)
        }
    }

    /**
     * Определение категории приложения для отображения корректной иконки на браслете.
     */
    private fun resolveAlertCategory(packageName: String): Byte {
        val lower = packageName.lowercase()
        return when {
            lower.contains("mail") || lower.contains("gmail") || lower.contains("outlook") -> ALERT_CATEGORY_EMAIL
            lower.contains("mms") || lower.contains("sms") || lower.contains("messaging") -> ALERT_CATEGORY_SMS
            else -> ALERT_CATEGORY_GENERIC
        }
    }

    /**
     * Преобразование имени пакета в читаемое название приложения.
     */
    private fun getApplicationLabel(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /**
     * Упаковка заголовка и текста в бинарный пакет BLE Alert Notification (0x2A46).
     * Структура пакета Huami New Alert:
     * [0] = Category ID (0x01 - Email, 0x05 - SMS, 0x09 - Instant Message)
     * [1] = Alert Count (0x01)
     * [2..N] = Строка UTF-8: "AppName\nTitle: Text" (обрезается до безопасного лимита символов)
     */
    private suspend fun dispatchNotificationToBand(
        category: Byte,
        appName: String,
        title: String,
        text: String
    ) {
        val bleService = MiBandBleService.activeInstance ?: return
        val manager = bleService.bleManager ?: return

        if (manager.connectionState.value !is ConnectionState.Authenticated) {
            Log.w(TAG, "Браслет не авторизован. Отправка уведомления пропущена.")
            return
        }

        val messageBuilder = StringBuilder()
        if (appName.isNotBlank()) {
            messageBuilder.append(appName).append("\n")
        }
        if (title.isNotBlank()) {
            messageBuilder.append(title).append(": ")
        }
        messageBuilder.append(text)

        // Лимит отображения текста на Mi Smart Band 4 во избежание переполнения буфера прошивки
        val safeMessage = messageBuilder.toString().take(240)
        val textBytes = safeMessage.toByteArray(StandardCharsets.UTF_8)

        val packet = ByteArray(2 + textBytes.size)
        packet[0] = category
        packet[1] = 0x01 // Счетчик непрочитанных сообщений
        System.arraycopy(textBytes, 0, packet, 2, textBytes.size)

        val success = manager.sendRawData(
            serviceUuid = MiBandConstants.UUID_SERVICE_ALERT_NOTIFICATION,
            charUuid = MiBandConstants.UUID_CHAR_NEW_ALERT,
            data = packet,
            withResponse = false
        )

        if (success) {
            Log.i(TAG, "Уведомление успешно доставлено на Mi Smart Band 4.")
        } else {
            Log.e(TAG, "Не удалось отправить пакет уведомления в GATT-стек.")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Очистка при необходимости
    }
}
