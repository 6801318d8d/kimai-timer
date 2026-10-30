package io.github.x6801318d8d.kimaitimer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The notification's buttons and its redraw alarm. */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = Settings(context)
        val id = intent.getIntExtra(EXTRA_ID, 0)
        when (intent.action) {
            STOP -> {
                settings.running?.takeIf { it.id == id }?.let { TimerNotification.showStopping(context, it) }
                SyncWorker.enqueue(context, SyncWorker.STOP, id)
            }
            RESTART -> {
                settings.last?.takeIf { it.id == id }?.let { TimerNotification.showStarting(context, it) }
                settings.stoppedUntil = 0
                SyncWorker.enqueue(context, SyncWorker.RESTART, id)
            }
            REDRAW -> TimerNotification.update(context)
        }
    }

    companion object {
        const val STOP = "io.github.x6801318d8d.kimaitimer.STOP"
        const val RESTART = "io.github.x6801318d8d.kimaitimer.RESTART"
        const val REDRAW = "io.github.x6801318d8d.kimaitimer.REDRAW"
        private const val EXTRA_ID = "id"

        fun pending(context: Context, action: String, id: Int): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
