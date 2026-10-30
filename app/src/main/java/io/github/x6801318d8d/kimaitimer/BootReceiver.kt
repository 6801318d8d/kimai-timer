package io.github.x6801318d8d.kimaitimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot or an update of the app: updates set up again (direct connection, 15-minute check). */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Settings(context).configured) {
                TimerNotification.createChannel(context)
                Delivery.apply(context, register = false)
                SyncWorker.enqueue(context, SyncWorker.REFRESH)
            }
        }
    }
}
