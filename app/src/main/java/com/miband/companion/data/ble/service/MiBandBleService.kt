package com.miband.companion.data.ble.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.miband.companion.MiBandApplication
import com.miband.companion.data.ble.ConnectionState
import com.miband.companion.data.ble.MiBandBleManager
import com.miband.companion.data.ble.protocol.MiBandConstants
import com.miband.companion.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MiBandBleService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()

    private var wakeLock: PowerManager.WakeLock? = null
    var bleManager: MiBandBleManager? = null
        private set

    companion object {
        private const val TAG = "MiBandBleService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "com.miband.companion.action.CONNECT"
        const val ACTION_DISCONNECT = "com.miband.companion.action.DISCONNECT"
        const val ACTION_VIBRATE = "com.miband.companion.action.VIBRATE"
        const val EXTRA_MAC_ADDRESS = "extra_mac_address"
        const val EXTRA_AUTH_KEY = "extra_auth_key"
        const val EXTRA_FORCE_PAIR = "extra_force_pair"

        @Volatile
        var activeInstance: MiBandBleService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): MiBandBleService = this@MiBandBleService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        activeInstance = this

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiBandCompanion::BleWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        bleManager = MiBandBleManager(applicationContext, adapter, Dispatchers.IO)

        observeConnectionState()
        startForegroundServiceNotification("Инициализация сервиса связи...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val mac = intent.getStringExtra(EXTRA_MAC_ADDRESS)
                val key = intent.getStringExtra(EXTRA_AUTH_KEY) ?: MiBandConstants.DEFAULT_AUTH_KEY_HEX
                val forcePair = intent.getBooleanExtra(EXTRA_FORCE_PAIR, false)
                if (!mac.isNullOrBlank()) {
                    bleManager?.connect(mac, key, forcePair)
                }
            }
            ACTION_DISCONNECT -> {
                bleManager?.disconnect()
            }
            ACTION_VIBRATE -> {
                serviceScope.launch {
                    bleManager?.triggerVibration(strong = true)
                }
            }
        }
        return START_STICKY
    }

    private fun observeConnectionState() {
        bleManager?.connectionState?.onEach { state ->
            val text = when (state) {
                is ConnectionState.Disconnected -> "Отключено от Mi Smart Band 4"
                is ConnectionState.Connecting -> "Подключение к браслету..."
                is ConnectionState.ConnectedGatt -> "Сокет открыт, обнаружение сервисов..."
                is ConnectionState.Authenticating -> "Криптографическое рукопожатие AES-128..."
                is ConnectionState.Authenticated -> "Подключено: Mi Smart Band 4 активен"
                is ConnectionState.Error -> "Ошибка: ${state.message}"
            }
            updateNotification(text)
        }?.launchIn(serviceScope)
    }

    private fun startForegroundServiceNotification(contentText: String) {
        val notification = buildForegroundNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val notification = buildForegroundNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, MiBandApplication.CHANNEL_BLE_SERVICE_ID)
            .setContentTitle("Mi Smart Band 4 Companion")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        activeInstance = null
        serviceScope.cancel()
        bleManager?.disconnect()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        super.onDestroy()
        Log.i(TAG, "Служба MiBandBleService остановлена.")
    }
}
