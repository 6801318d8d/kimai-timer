package io.github.x6801318d8d.kimaitimer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * This device's side of the timer. The buttons of the notification and of the app's timer screen change [Local] at
 * once, with the time of the tap, and queue the change; the sync ([SyncWorker]) sends the queue to Kimai whenever it
 * can and reads Kimai back. So the timer works the same when the network or Kimai is not there.
 */
object Tracker {
    private val lock = Any()

    fun read(context: Context): Local = synchronized(lock) { Settings(context).local }

    fun update(context: Context, block: (Local) -> Local): Local = synchronized(lock) {
        val settings = Settings(context)
        val old = settings.local
        block(old).also { if (it != old) settings.local = it }
    }

    fun store(context: Context): Store = object : Store {
        override fun read(): Local = read(context)

        override fun update(block: (Local) -> Local) {
            update(context, block)
        }
    }

    fun stop(context: Context, id: Int) = changed(context) { it.stop(id, now()) }

    fun start(context: Context, template: Timer, description: String) = changed(context) { it.start(template, description, now()) }

    /** Restart: the project and activity of entry [id] again, without its description, as Kimai's own restart. */
    fun restart(context: Context, id: Int) = changed(context) { local ->
        local.last?.takeIf { it.id == id }?.let { local.start(it, "", now()) } ?: local
    }

    private fun changed(context: Context, block: (Local) -> Local) {
        update(context, block)
        TimerNotification.update(context)
        SyncWorker.enqueue(context, SyncWorker.SYNC)
    }

    /** A network that reaches the internet; without one the sync waits for it. */
    fun online(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** Kimai keeps whole seconds. */
    private fun now(): Long = System.currentTimeMillis() / 1000 * 1000
}
