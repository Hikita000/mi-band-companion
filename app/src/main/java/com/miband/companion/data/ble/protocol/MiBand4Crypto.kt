package com.miband.companion.data.ble.protocol

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Криптографический модуль аутентификации для браслетов Xiaomi / Huami.
 * Реализует симметричное шифрование вызова (challenge) алгоритмом AES-128 в режиме ECB без дополнения (NoPadding).
 */
object MiBand4Crypto {

    private const val AES_TRANSFORMATION = "AES/ECB/NoPadding"
    private const val AES_ALGORITHM = "AES"
    private const val EXPECTED_KEY_LENGTH = 16

    /**
     * Конвертирует строку Hex (32 символа = 16 байт) в сырой байтовый массив.
     * Обрабатывает как верхний, так и нижний регистр.
     * 
     * @param hex Строка в шестнадцатеричном формате, например "D1e7a4eabc831e6f3d6c92c585b1bdd5"
     * @return ByteArray размерностью 16 байт
     * @throws IllegalArgumentException при невалидной длине или наличии не-hex символов
     */
    fun hexStringToByteArray(hex: String): ByteArray {
        val sanitizedHex = hex.trim().replace(" ", "")
        require(sanitizedHex.length == EXPECTED_KEY_LENGTH * 2) {
            "Ключ аутентификации должен содержать ровно 32 шестнадцатеричных символа (16 байт). Передано: ${sanitizedHex.length}"
        }

        val result = ByteArray(EXPECTED_KEY_LENGTH)
        for (i in 0 until sanitizedHex.length step 2) {
            val highNibble = Character.digit(sanitizedHex[i], 16)
            val lowNibble = Character.digit(sanitizedHex[i + 1], 16)

            require(highNibble != -1 && lowNibble != -1) {
                "Обнаружен невалидный шестнадцатеричный символ в позиции $i: '${sanitizedHex[i]}${sanitizedHex[i+1]}'"
            }

            // Побитовый сдвиг старшей тетрады влево на 4 бита и объединение с младшей тетрадой
            result[i / 2] = ((highNibble shl 4) or lowNibble).toByte()
        }
        return result
    }

    /**
     * Конвертирует байтовый массив в форматированную шестнадцатеричную строку (для логирования).
     */
    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            // Маскирование 0xFF для устранения расширения знака в типе Byte
            val value = b.toInt() and 0xFF
            if (value < 16) {
                sb.append('0')
            }
            sb.append(Integer.toHexString(value))
        }
        return sb.toString().uppercase()
    }

    /**
     * Выполняет шифрование 16 байт challenge-запроса от браслета с использованием секретного ключа Auth Key.
     * 
     * Алгоритм:
     * 1. Инициализация AES Cipher в режиме ENCRYPT_MODE.
     * 2. Генерация SecretKeySpec из 16-байтового секретного ключа.
     * 3. Шифрование ровно одного 128-битного блока (16 байт) без padding.
     * 
     * @param challenge16Bytes 16 байт случайного числа, присланного браслетом в пакете [0x10, 0x02, 0x01, ...]
     * @param authKey16Bytes 16 байт распарсенного Auth Key
     * @return 16 байт зашифрованного ответа
     * @throws GeneralSecurityException при аппаратном сбое криптопровайдера Android
     */
    @Throws(GeneralSecurityException::class)
    fun encryptChallenge(challenge16Bytes: ByteArray, authKey16Bytes: ByteArray): ByteArray {
        require(challenge16Bytes.size == EXPECTED_KEY_LENGTH) {
            "Размер challenge от браслета должен составлять ровно 16 байт. Получено: ${challenge16Bytes.size}"
        }
        require(authKey16Bytes.size == EXPECTED_KEY_LENGTH) {
            "Размер ключа шифрования должен составлять ровно 16 байт. Получено: ${authKey16Bytes.size}"
        }

        val secretKeySpec = SecretKeySpec(authKey16Bytes, AES_ALGORITHM)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec)

        return cipher.doFinal(challenge16Bytes)
    }

    /**
     * Формирует итоговый бинарный пакет для отправки в Auth Characteristic (00000009...)
     * Структура пакета (18 байт):
     * [0] = 0x03 (Опкод ответа на challenge)
     * [1] = 0x00 (Флаг/резервный байт)
     * [2..17] = 16 байт зашифрованного challenge
     */
    fun buildAuthResponsePacket(encryptedChallenge: ByteArray): ByteArray {
        require(encryptedChallenge.size == EXPECTED_KEY_LENGTH) {
            "Зашифрованный блок должен иметь размер 16 байт."
        }

        val packet = ByteArray(2 + EXPECTED_KEY_LENGTH)
        packet[0] = MiBandConstants.AUTH_SEND_ENCRYPTED_AUTH_NUMBER_FLAG
        packet[1] = 0x00
        System.arraycopy(encryptedChallenge, 0, packet, 2, EXPECTED_KEY_LENGTH)
        return packet
    }
}
