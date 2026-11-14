fun connectToBand(forcePair: Boolean = false) {
        val state = _uiState.value
        if (state.macAddress.isBlank()) {
            showBanner("Укажите MAC-адрес браслета или выберите его в сканере.")
            return
        }
        if (!state.isAuthKeyValid) {
            showBanner("Некорректный Auth Key! Необходимо ровно 32 Hex-символа.")
            return
        }

        val context = getApplication<Application>()
        val intent = Intent(context, MiBandBleService::class.java).apply {
            action = MiBandBleService.ACTION_CONNECT
            putExtra(MiBandBleService.EXTRA_MAC_ADDRESS, state.macAddress)
            putExtra(MiBandBleService.EXTRA_AUTH_KEY, state.authKeyHex)
            putExtra(MiBandBleService.EXTRA_FORCE_PAIR, forcePair)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
