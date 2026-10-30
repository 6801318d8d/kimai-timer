package io.github.x6801318d8d.kimaitimer

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Everything that talks to Kimai's API runs here, in the background: reading the timer and redrawing the notification
 * (after a push message or a stream event, at app start, every 15 minutes), the Stop and Restart buttons, and handing
 * the UnifiedPush endpoint to Kimai. Buttons pressed offline are retried when the network is back.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val settings = Settings(applicationContext)
        val api = settings.api() ?: return Result.success()
        val action = inputData.getString(KEY_ACTION) ?: REFRESH
        val id = inputData.getInt(KEY_ID, 0)
        try {
            when (action) {
                STOP -> {
                    val timer = settings.running?.takeIf { it.id == id }
                    try {
                        val duration = api.stop(id)
                        if (timer != null) {
                            settings.last = timer.copy(duration = duration)
                            settings.stoppedUntil = System.currentTimeMillis() + TimerNotification.STOPPED_MS
                        }
                    } catch (e: KimaiApi.HttpException) {
                        // already stopped or deleted elsewhere: the refresh below shows how things are
                        if (e.code != 400 && e.code != 404) throw e
                    }
                    refresh(settings, api)
                }
                RESTART -> {
                    api.restart(id)
                    refresh(settings, api)
                }
                REGISTER -> register(settings, api)
                UNREGISTER -> inputData.getString(KEY_ENDPOINT)?.let {
                    try {
                        api.unregisterEndpoint(it)
                    } catch (e: KimaiApi.HttpException) {
                        // 404: Kimai did not have it (refused, or already removed)
                        if (e.code != 404) throw e
                    }
                }
                PERIODIC -> {
                    refresh(settings, api)
                    // brings the direct connection back if the system closed it
                    Delivery.apply(applicationContext, register = false)
                }
                else -> refresh(settings, api)
            }
            settings.lastError = ""
            return Result.success()
        } catch (e: Exception) {
            settings.lastError = e.message ?: e.javaClass.simpleName
            if (action in RETRIED && e is IOException && e !is KimaiApi.HttpException && runAttemptCount < MAX_ATTEMPTS) {
                return Result.retry()
            }
            // a button that did not work: show the timer as it was
            TimerNotification.update(applicationContext)
            return Result.success()
        }
    }

    private fun refresh(settings: Settings, api: KimaiApi) {
        val timer = api.active().firstOrNull()
        settings.running = timer
        if (timer == null) {
            api.latest()?.let { latest ->
                // keep the stopped card of the entry just stopped from the notification
                if (latest.id != settings.last?.id) settings.stoppedUntil = 0
                settings.last = latest
            }
        } else {
            settings.stoppedUntil = 0
        }
        settings.lastSync = System.currentTimeMillis()
        TimerNotification.update(applicationContext)
    }

    private fun register(settings: Settings, api: KimaiApi) {
        val endpoint = settings.endpoint
        if (endpoint.isEmpty()) return
        try {
            api.registerEndpoint(endpoint, "${Build.MANUFACTURER} ${Build.MODEL}")
            settings.endpointRegistered = true
            settings.pushError = ""
        } catch (e: KimaiApi.HttpException) {
            // 400: endpoint refused (push server not allowed); 404: Kimai without the AppPushBundle plugin
            Delivery.pushFailed(applicationContext, applicationContext.getString(R.string.error_push_refused, e.message))
        }
    }

    companion object {
        const val REFRESH = "refresh"
        const val PERIODIC = "periodic"
        const val STOP = "stop"
        const val RESTART = "restart"
        const val REGISTER = "register"
        const val UNREGISTER = "unregister"
        private val RETRIED = setOf(STOP, RESTART, REGISTER, UNREGISTER)
        private const val KEY_ACTION = "action"
        private const val KEY_ID = "id"
        private const val KEY_ENDPOINT = "endpoint"
        private const val MAX_ATTEMPTS = 20

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(context: Context, action: String, id: Int = 0, endpoint: String = "") {
            val request = OneTimeWorkRequest.Builder(SyncWorker::class.java)
                .setInputData(data(action, id, endpoint))
                .setConstraints(network)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            // a newer refresh replaces a pending one; button presses queue up in order
            val (name, policy) = when (action) {
                REFRESH -> "refresh" to ExistingWorkPolicy.REPLACE
                REGISTER -> "register" to ExistingWorkPolicy.REPLACE
                else -> "actions" to ExistingWorkPolicy.APPEND_OR_REPLACE
            }
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequest.Builder(SyncWorker::class.java, 15, TimeUnit.MINUTES)
                .setInputData(data(PERIODIC, 0, ""))
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        private fun data(action: String, id: Int, endpoint: String) =
            Data.Builder().putString(KEY_ACTION, action).putInt(KEY_ID, id).putString(KEY_ENDPOINT, endpoint).build()
    }
}
