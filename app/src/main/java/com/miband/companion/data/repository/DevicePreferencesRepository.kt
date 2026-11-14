package com.miband.companion.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.miband.companion.data.ble.protocol.MiBandConstants

/**
 * Репозиторий персистентного хранения параметров браслета Mi Smart Band 4.
 */
class DevicePreferencesRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "miband_companion_secure_prefs"
        private const val KEY_MAC_ADDRESS = "key_mac_address"
        private const val KEY_AUTH_KEY = "key_auth_key"
        private const val KEY_NOTIFICATIONS_ENABLED = "key_notifications_enabled"
        private const val KEY_AUTO_RECONNECT = "key_auto_reconnect"
    }

    /**
     * Сохраняет последний подключенный MAC-адрес браслета.
     */
    fun saveMacAddress(macAddress: String) {
        prefs.edit().putString(KEY_MAC_ADDRESS, macAddress.trim().uppercase()).apply()
    }

    fun getMacAddress(): String {
        return prefs.getString(KEY_MAC_ADDRESS, "") ?: ""
    }

    /**
     * Сохраняет 16-байтовый (32 hex-символа) Auth Key для рукопожатия AES-128.
     */
    fun saveAuthKey(authKeyHex: String) {
        prefs.edit().putString(KEY_AUTH_KEY, authKeyHex.trim()).apply()
    }

    /**
     * Возвращает сохраненный Auth Key или предустановленный ключ по умолчанию:
     * D1e7a4eabc831e6f3d6c92c585b1bdd5
     */
    fun getAuthKey(): String {
        val saved = prefs.getString(KEY_AUTH_KEY, null)
        return if (saved.isNullOrBlank()) MiBandConstants.DEFAULT_AUTH_KEY_HEX else saved
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    fun isNotificationsEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
    }

    fun isAutoReconnectEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RECONNECT, true)
    }
}
