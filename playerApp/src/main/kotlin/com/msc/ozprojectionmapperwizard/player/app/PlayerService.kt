package com.msc.ozprojectionmapperwizard.player.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.msc.ozprojectionmapperwizard.core.protocol.PlayerProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps the player reachable: runs the HTTP API and advertises it on the local network, as a
 * foreground service so the system does not stop it while a show plays.
 */
class PlayerService : Service() {
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        // Binding the socket is network work, which Android does not allow on the main thread.
        player.scope.launch(Dispatchers.IO) {
            runCatching { player.server.start(PlayerProtocol.DEFAULT_PORT) }
                .onFailure { Log.e(TAG, "Could not start the player server", it) }
        }
        advertise()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        nsdListener?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        multicastLock?.takeIf { it.isHeld }?.release()
        player.scope.launch(Dispatchers.IO) { player.server.stop() }
        super.onDestroy()
    }

    private fun advertise() {
        multicastLock = getSystemService(WifiManager::class.java).createMulticastLock(TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        val info = NsdServiceInfo().apply {
            serviceName = AndroidPlatformInfo(this@PlayerService, player.storage).name
            serviceType = PlayerProtocol.SERVICE_TYPE
            port = PlayerProtocol.DEFAULT_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertised as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "Advertising failed: $error")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        nsdListener = listener
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PlayerService"
        private const val CHANNEL = "player"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlayerService::class.java))
        }
    }
}
