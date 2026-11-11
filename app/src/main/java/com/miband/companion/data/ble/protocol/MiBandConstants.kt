package com.miband.companion.data.ble.protocol

import java.util.UUID

/**
 * Константы протокола Huami/Xiaomi для взаимодействия с Mi Smart Band 4 по протоколу BLE.
 * Содержит системные UUID сервисов, дескрипторов и характеристик GATT, а также байтовые опкоды.
 */
object MiBandConstants {

    // Ключ аутентификации устройства (16 байт в шестнадцатеричном виде)
    const val DEFAULT_AUTH_KEY_HEX: String = "D1e7a4eabc831e6f3d6c92c585b1bdd5"

    // Физические параметры дисплея Mi Smart Band 4 (AMOLED)
    const val SCREEN_WIDTH: Int = 120
    const val SCREEN_HEIGHT: Int = 240
    const val SCREEN_TOTAL_PIXELS: Int = SCREEN_WIDTH * SCREEN_HEIGHT

    // Максимальный размер пакета MTU по умолчанию для согласования с BLE контроллером
    const val DESIRED_MTU_SIZE: Int = 517

    // Стандартный дескриптор конфигурации характеристик клиента (CCCD) для включения Notify/Indicate
    val UUID_DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- Huami Authentication Service ---
    val UUID_SERVICE_AUTH: UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_AUTH: UUID = UUID.fromString("00000009-0000-3512-2118-0009af100700")

    // --- Huami Custom Service (Шаги, батарея, системные события) ---
    val UUID_SERVICE_MIBAND_CUSTOM: UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_REALTIME_STEPS: UUID = UUID.fromString("00000007-0000-3512-2118-0009af100700")
    val UUID_CHAR_BATTERY_INFO: UUID = UUID.fromString("00000006-0000-3512-2118-0009af100700")
    val UUID_CHAR_DEVICE_EVENT: UUID = UUID.fromString("00000010-0000-3512-2118-0009af100700")
    val UUID_CHAR_CHUNKED_TRANSFER: UUID = UUID.fromString("00000020-0000-3512-2118-0009af100700")

    // --- Huami Notification Service (Текстовые уведомления на экране) ---
    val UUID_SERVICE_ALERT_NOTIFICATION: UUID = UUID.fromString("00001811-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_NEW_ALERT: UUID = UUID.fromString("00002a46-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_ALERT_CONTROL_POINT: UUID = UUID.fromString("00002a44-0000-1000-8000-00805f9b34fb")

    // --- Immediate Alert Service (Вибрация/поиск браслета) ---
    val UUID_SERVICE_IMMEDIATE_ALERT: UUID = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_ALERT_LEVEL: UUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")

    // --- Heart Rate Service ---
    val UUID_SERVICE_HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val UUID_CHAR_HEART_RATE_CONTROL_POINT: UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")

    // --- Опкоды криптографического протокола аутентификации ---
    // Шаг 1: Запрос случайного числа (Challenge Request)
    val AUTH_REQUEST_RANDOM_AUTH_NUMBER: ByteArray = byteArrayOf(0x02, 0x00)
    
    // Префикс Шага 2: Отправка зашифрованного ключа (Encrypted Challenge Response)
    const val AUTH_SEND_ENCRYPTED_AUTH_NUMBER_FLAG: Byte = 0x03
    
    // Ответные опкоды от браслета
    const val AUTH_RESPONSE_HEADER: Byte = 0x10
    const val AUTH_RESPONSE_STATUS_SUCCESS: Byte = 0x01
    const val AUTH_RESPONSE_STATUS_FAILED: Byte = 0x04

    // Вибрация: 0x00 = выкл, 0x01 = мягкая вибрация, 0x02 = сильная вибрация
    val ALERT_LEVEL_STRONG: ByteArray = byteArrayOf(0x02)
    val ALERT_LEVEL_OFF: ByteArray = byteArrayOf(0x00)
}
