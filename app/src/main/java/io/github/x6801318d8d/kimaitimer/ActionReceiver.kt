package io.github.x6801318d8d.kimaitimer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The notification's buttons, its redraw alarm, and the dismissal of the refused changes. */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        when (intent.action) {
            STOP -> Tracker.stop(context, id)
            RESTART -> Tracker.restart(context, id)
            REDRAW -> TimerNotification.update(context)
            FORGET_REFUSED -> Settings(context).refused = emptyList()
        }
    }

    companion object {
        const val STOP = "io.github.x6801318d8d.kimaitimer.STOP"
        const val RESTART = "io.github.x6801318d8d.kimaitimer.RESTART"
        const val FORGET_REFUSED = "io.github.x6801318d8d.kimaitimer.FORGET_REFUSED"
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
