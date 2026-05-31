package com.rjnr.pocketnode.data.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SyncForegroundService : Service() {

    @Inject lateinit var gatewayRepository: GatewayRepository
    @Inject lateinit var walletPreferences: WalletPreferences
    @Inject lateinit var syncNotificationManager: SyncNotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand (flags=$flags, startId=$startId)")
        // startForeground can throw on Android 12+ if the system rejects the
        // start (ForegroundServiceStartNotAllowedException), and on Android 14+
        // if the service-type declaration is missing or mismatched. Samsung's
        // bg-restrict ROMs also surface a SecurityException here when waking
        // from deep sleep. Catching here is critical: an uncaught exception in
        // onStartCommand crashes the host process. (matt, Telegram, 2026-05)
        try {
            startAsForeground()
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed; stopping self to avoid crash", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Guard against duplicate coroutines on repeated onStartCommand
        if (observeJob?.isActive != true) {
            serviceScope.launch {
                try {
                    // Re-init wallet if needed (e.g. after process restart via START_STICKY)
                    if (gatewayRepository.walletInfo.value == null) {
                        Log.d(TAG, "Wallet not initialized, attempting re-init")
                        gatewayRepository.initializeWallet()
                    }
                    gatewayRepository.startSyncPolling()
                } catch (e: Throwable) {
                    Log.e(TAG, "Service-scope sync bootstrap failed", e)
                }
            }
            observeProgress()
        }

        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = syncNotificationManager.buildSyncingNotification(0, "Starting...")
        ServiceCompat.startForeground(
            this,
            SyncNotificationManager.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun observeProgress() {
        observeJob = serviceScope.launch {
            gatewayRepository.syncProgress.collectLatest { progress ->
                // NotificationManager.notify() can throw a SecurityException on
                // Android 13+ when the user revokes POST_NOTIFICATIONS, and on
                // some Samsung ROMs throws a RemoteException if the system
                // notification service is briefly unavailable during a
                // background → foreground transition. Catching here keeps the
                // sync running even when the notification surface is sick.
                try {
                    val notification = if (progress.isSyncing) {
                        syncNotificationManager.buildSyncingNotification(
                            progress.percentage.toInt(),
                            progress.etaDisplay
                        )
                    } else {
                        syncNotificationManager.buildSyncedNotification()
                    }
                    syncNotificationManager.notify(notification)
                } catch (e: Throwable) {
                    Log.w(TAG, "notification update failed; continuing", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        observeJob?.cancel()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    companion object {
        private const val TAG = "SyncForegroundService"

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
