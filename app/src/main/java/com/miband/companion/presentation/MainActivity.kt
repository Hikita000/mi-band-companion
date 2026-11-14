package com.miband.companion.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.miband.companion.presentation.screens.MainScreen
import com.miband.companion.presentation.theme.MiBandCompanionTheme
import com.miband.companion.presentation.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemPermissionsState()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startScreenMirroring(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredRuntimePermissions()

        setContent {
            MiBandCompanionTheme {
                val uiState by viewModel.uiState.collectAsState()

                MainScreen(
                    uiState = uiState,
                    onConnectClick = { viewModel.connectToBand(forcePair = false) },
                    onDisconnectClick = { viewModel.disconnectFromBand() },
                    onFindBandClick = { viewModel.triggerFindBandVibration() },
                    onToggleNotifications = { viewModel.setNotificationsEnabled(it) },
                    onOpenNotificationSettings = { openNotificationListenerSettings() },
                    onSendTestNotification = { viewModel.sendTestNotificationToBand() },
                    onStartScreenMirroring = { launchMediaProjectionPrompt() },
                    onStopScreenMirroring = { viewModel.stopScreenMirroring() },
                    onMacChanged = { viewModel.updateMacAddress(it) },
                    onAuthKeyChanged = { viewModel.updateAuthKey(it) },
                    onToggleScan = { viewModel.toggleScan(strictHuamiFilter = false) },
                    onSelectDevice = { viewModel.selectScannedDevice(it) },
                    onRequestBatteryIgnore = { requestIgnoreBatteryOptimizations() },
                    onForcePairClick = { viewModel.connectToBand(forcePair = true) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSystemPermissionsState()
    }

    private fun requestRequiredRuntimePermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun launchMediaProjectionPrompt() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
