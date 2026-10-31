package io.github.x6801318d8d.kimaitimer

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush, through whichever distributor app is installed: the endpoint goes to Kimai (AppPushBundle), and every
 * message, which only says "your timesheets changed", makes the app read the timer again.
 */
class PushService : org.unifiedpush.android.connector.PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val settings = Settings(this)
        if (settings.endpoint != endpoint.url) {
            settings.endpointRegistered = false
        }
        settings.endpoint = endpoint.url
        SyncWorker.enqueue(this, SyncWorker.REGISTER)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        SyncWorker.enqueue(this, SyncWorker.SYNC)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Delivery.pushFailed(this, "UnifiedPush: $reason")
    }

    override fun onUnregistered(instance: String) {
        val settings = Settings(this)
        val old = settings.endpoint
        settings.endpoint = ""
        settings.endpointRegistered = false
        if (old.isNotEmpty()) {
            SyncWorker.enqueue(this, SyncWorker.UNREGISTER, endpoint = old)
        }
    }
}
