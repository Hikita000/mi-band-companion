package com.miband.companion.data.ble.protocol

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Криптографический модуль аутентификации для браслетов Xiaomi / Huami.
 */
object MiBand4Crypto {

    private const val AES_TRANSFORMATION = "AES/ECB/NoPadding"
    private const val AES_ALGORITHM = "AES"
    private const val EXPECTED_KEY_LENGTH = 16

    fun hexStringToByteArray(hex: String): ByteArray {
        val sanitizedHex = hex.trim().replace(" ", "")
        require(sanitizedHex.length == EXPECTED_KEY_LENGTH * 2) {
            "Ключ должен содержать ровно 32 Hex символа (16 байт). Сейчас: ${sanitizedHex.length}"
        }

        val result = ByteArray(EXPECTED_KEY_LENGTH)
        for (i in 0 until sanitizedHex.length step 2) {
            val highNibble = Character.digit(sanitizedHex[i], 16)
            val lowNibble = Character.digit(sanitizedHex[i + 1], 16)

            require(highNibble != -1 && lowNibble != -1) {
                "Невалидный Hex символ в позиции $i"
            }
            result[i / 2] = ((highNibble shl 4) or lowNibble).toByte()
        }
        return result
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            if (value < 16) sb.append('0')
            sb.append(Integer.toHexString(value))
        }
        return sb.toString().uppercase()
    }

    @Throws(GeneralSecurityException::class)
    fun encryptChallenge(challenge16Bytes: ByteArray, authKey16Bytes: ByteArray): ByteArray {
        require(challenge16Bytes.size == EXPECTED_KEY_LENGTH)
        require(authKey16Bytes.size == EXPECTED_KEY_LENGTH)

        val secretKeySpec = SecretKeySpec(authKey16Bytes, AES_ALGORITHM)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec)
        return cipher.doFinal(challenge16Bytes)
    }

    /**
     * Формирует ответный пакет [0x03, authFlag, ...16 байт зашифрованного challenge...]
     */
    fun buildAuthResponsePacket(encryptedChallenge: ByteArray, authFlag: Byte = 0x00): ByteArray {
        require(encryptedChallenge.size == EXPECTED_KEY_LENGTH)

        val packet = ByteArray(2 + EXPECTED_KEY_LENGTH)
        packet[0] = MiBandConstants.AUTH_SEND_ENCRYPTED_AUTH_NUMBER_FLAG
        packet[1] = authFlag
        System.arraycopy(encryptedChallenge, 0, packet, 2, EXPECTED_KEY_LENGTH)
        return packet
    }
}
