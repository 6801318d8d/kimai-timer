package io.github.x6801318d8d.kimaitimer

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import java.net.HttpURLConnection
import kotlin.concurrent.thread

/**
 * The direct connection: a foreground service that keeps Kimai's event stream (AppPushBundle) open and refreshes the
 * timer at each event. Android lets an app keep a connection in the background only this way, with a notification
 * always shown: the app's own notification, which shows "No timer running" between timers.
 *
 * After a failure it tries again after 5 seconds, then waiting twice as long each time up to 5 minutes, and at once
 * when the network comes back.
 */
class StreamService : Service() {
    @Volatile private var active = false
    @Volatile private var connection: HttpURLConnection? = null
    private val lock = Object()
    private var worker: Thread? = null
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) { lock.notifyAll() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TimerNotification.createChannel(this)
        // never null when persistent
        val notification = TimerNotification.current(this, persistent = true)!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(TimerNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TimerNotification.ID, notification)
        }
        active = true
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        worker = thread(name = "kimai-stream") { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        active = false
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        connection?.disconnect()
        synchronized(lock) { lock.notifyAll() }
        Settings(this).streamConnected = false
        super.onDestroy()
    }

    private fun loop() {
        val settings = Settings(this)
        var delay = MIN_DELAY
        while (active) {
            val api = settings.api() ?: break
            val opened = System.currentTimeMillis()
            try {
                api.stream({ connection = it }) {
                    if (it == "ready") {
                        settings.streamConnected = true
                        delay = MIN_DELAY
                    }
                    // "ready" too: catch up with what changed while the connection was down
                    SyncWorker.enqueue(this, SyncWorker.REFRESH)
                }
                settings.streamConnected = false
                // the server ends each stream after half an hour: reconnect at once (but not in a loop if it
                // ends them right away)
                if (System.currentTimeMillis() - opened > 60_000) continue
            } catch (e: Exception) {
                settings.streamConnected = false
                if (!active) break
                if (e is KimaiApi.HttpException) {
                    settings.lastError = e.message ?: "HTTP ${e.code}"
                }
            }
            synchronized(lock) { if (active) lock.wait(delay) }
            delay = (delay * 2).coerceAtMost(MAX_DELAY)
        }
    }

    companion object {
        private const val MIN_DELAY = 5_000L
        private const val MAX_DELAY = 300_000L

        /** The direct connection is the mode in use. */
        fun isWanted(context: Context): Boolean = Settings(context).configured && Delivery.effective(context) == Delivery.DIRECT

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, StreamService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // started from the background without the battery exemption: the 15-minute check tries again
                Settings(context).lastError = context.getString(R.string.error_background_start)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
