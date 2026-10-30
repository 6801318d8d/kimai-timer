package io.github.x6801318d8d.kimaitimer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.unifiedpush.android.connector.UnifiedPush

/**
 * How the app hears about timers started or stopped elsewhere (computer, Kimai's web app, another device):
 *
 * - [UNIFIEDPUSH]: Kimai sends a message through a UnifiedPush distributor app (ntfy, NextPush, Sunup...), which
 *   keeps the phone's single push connection. Best for the battery.
 * - [DIRECT]: the app keeps its own connection to Kimai open, in a foreground service, so its notification stays on
 *   the lock screen even when no timer runs. For phones without a distributor.
 * - [PERIODIC]: only the check every 15 minutes, which also runs in the other modes as a safety net.
 *
 * [AUTO] picks UnifiedPush when a distributor is installed and working, else the direct connection.
 */
object Delivery {
    const val AUTO = "auto"
    const val UNIFIEDPUSH = "unifiedpush"
    const val DIRECT = "direct"
    const val PERIODIC = "periodic"

    /** Distributor apps other apps can register with: they open the "unifiedpush://link" deep link. Apps' own
     * embedded distributors (DAVx5's, for instance) don't, and only serve their own app. */
    fun distributors(context: Context): List<String> =
        UnifiedPush.getDistributors(context).filter { it != context.packageName && opensLink(context, it) }

    private fun opensLink(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("unifiedpush://link")).setPackage(packageName)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }

    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** The distributor in use or to be used: the one already chosen if still installed, else the first one found. */
    fun distributor(context: Context): String? =
        UnifiedPush.getSavedDistributor(context)?.takeIf { it in distributors(context) } ?: distributors(context).firstOrNull()

    fun effective(context: Context): String {
        val settings = Settings(context)
        return when (settings.mode) {
            UNIFIEDPUSH, DIRECT, PERIODIC -> settings.mode
            else -> if (settings.pushError.isEmpty() && distributor(context) != null) UNIFIEDPUSH else DIRECT
        }
    }

    /**
     * Starts what the current mode needs and stops the rest. [register]: also (re)register with the distributor,
     * as UnifiedPush asks apps to do at each start (the distributor answers with the same endpoint).
     */
    fun apply(context: Context, register: Boolean) {
        val settings = Settings(context)
        if (!settings.configured) {
            StreamService.stop(context)
            return
        }
        SyncWorker.schedulePeriodic(context)
        when (effective(context)) {
            UNIFIEDPUSH -> {
                StreamService.stop(context)
                if (register || settings.endpoint.isEmpty()) {
                    val distributor = distributor(context)
                    if (distributor == null) {
                        settings.pushError = context.getString(R.string.no_distributor)
                    } else {
                        UnifiedPush.saveDistributor(context, distributor)
                        UnifiedPush.register(context, messageForDistributor = context.getString(R.string.app_name))
                    }
                }
            }
            else -> {
                unregister(context)
                if (effective(context) == DIRECT) StreamService.start(context) else StreamService.stop(context)
            }
        }
        TimerNotification.update(context)
    }

    /**
     * Drops the UnifiedPush registration and tells Kimai to forget the endpoint. The connector does not call
     * PushService.onUnregistered when the app itself unregisters, so this is done here.
     */
    fun unregister(context: Context) {
        val settings = Settings(context)
        val old = settings.endpoint
        if (old.isEmpty()) return
        UnifiedPush.unregister(context)
        settings.endpoint = ""
        settings.endpointRegistered = false
        SyncWorker.enqueue(context, SyncWorker.UNREGISTER, endpoint = old)
    }

    /** UnifiedPush did not work out: automatic mode switches to the direct connection. */
    fun pushFailed(context: Context, reason: String) {
        val settings = Settings(context)
        settings.pushError = reason
        settings.endpointRegistered = false
        if (settings.mode == AUTO) {
            apply(context, register = false)
        }
    }
}
