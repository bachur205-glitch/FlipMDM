package com.moshe.flipmdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Stays running to:
 *  - catch the shutdown signal and un-hide system apps before power-off
 *    (Android only sends ACTION_SHUTDOWN to running apps), and
 *  - keep the remote-control link alive and send a status heartbeat.
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            Remote.heartbeat(this@GuardService)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            BootSafe.beforeShutdown(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "MDM protection", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Flip MDM is active")
            .setOngoing(true)
            .build()
        startForeground(1, n)

        val filter = IntentFilter(Intent.ACTION_SHUTDOWN)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(shutdownReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(shutdownReceiver, filter)
        }

        // Remote control (no-op unless a database URL is configured).
        Remote.start(this)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Remote.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        try { unregisterReceiver(shutdownReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "guard"
        private const val HEARTBEAT_MS = 60_000L
        fun start(c: Context) {
            c.startForegroundService(Intent(c, GuardService::class.java))
        }
    }
}
