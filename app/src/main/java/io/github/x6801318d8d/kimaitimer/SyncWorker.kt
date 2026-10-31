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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Everything that talks to Kimai's API runs here, in the background. A sync sends the changes made on this device
 * (see [Sender]), then reads the timer and the latest entries, and now and then the projects and activities; it runs
 * after each tap, after a push message or a stream event, at app start and every 15 minutes. While Kimai cannot be
 * reached, the changes wait and the sync tries again: at once when the network comes back, else after a pause that
 * grows with the outage, from 10 seconds to 15 minutes. Also hands the UnifiedPush endpoint to Kimai.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val settings = Settings(applicationContext)
        if (!settings.configured) return Result.success()
        when (val action = inputData.getString(KEY_ACTION) ?: SYNC) {
            LATER -> enqueue(applicationContext, SYNC)
            REGISTER, UNREGISTER -> return endpoint(settings, action)
            else -> {
                sync(settings, inputData.getBoolean(KEY_CATALOG, false))
                // brings the direct connection back if the system closed it
                if (action == PERIODIC) Delivery.apply(applicationContext, register = false)
            }
        }
        return Result.success()
    }

    /** One sync at a time: the workers of the different triggers wait for each other. */
    private fun sync(settings: Settings, catalog: Boolean) = synchronized(LOCK) {
        val context = applicationContext
        try {
            if (catalog || settings.timezone.isEmpty() || System.currentTimeMillis() - settings.catalogSync > CATALOG_MS) {
                settings.timezone = settings.api()!!.user().timezone
                settings.catalog = settings.api()!!.catalog()
            }
            val api = settings.api()!!
            val sender = Sender(api, Tracker.store(context))
            do {
                sender.flush { change, before, message -> refused(settings, change, before, message) }
                val running = api.active().firstOrNull()
                val recent = api.recent(Local.RECENT)
                // a tap while reading: send it first, Kimai does not know it yet
                var done = false
                Tracker.update(context) { local ->
                    if (local.changes.isEmpty()) local.refreshed(running, recent).also { done = true } else local
                }
            } while (!done)
            settings.lastSync = System.currentTimeMillis()
            settings.lastError = ""
            settings.unreachableSince = 0
        } catch (e: Exception) {
            settings.lastError = e.message ?: e.javaClass.simpleName
            if (transient(e)) {
                val now = System.currentTimeMillis()
                if (!settings.unreachable) settings.unreachableSince = now
                if (Tracker.read(context).changes.isNotEmpty()) later(context, now - settings.unreachableSince)
            }
        }
        TimerNotification.update(context)
    }

    /** A change Kimai refused: the user is told, with what to enter by hand. */
    private fun refused(settings: Settings, change: Change, before: Local, message: String) {
        val context = applicationContext
        val line = when (change) {
            is Change.Start -> {
                val end = before.changes.filterIsInstance<Change.Stop>().firstOrNull { it.id == change.id }?.end
                context.getString(R.string.refused_start, change.title, period(change.begin, end), message)
            }
            is Change.Stop -> {
                val title = (before.recent + listOfNotNull(before.running)).firstOrNull { it.id == change.id }?.title.orEmpty()
                context.getString(R.string.refused_stop, title, time(change.end), message)
            }
        }
        settings.refused = (settings.refused + line).takeLast(MAX_REFUSED)
        TimerNotification.showRefused(context, settings.refused)
    }

    private fun endpoint(settings: Settings, action: String): Result {
        val api = settings.api() ?: return Result.success()
        try {
            if (action == REGISTER) {
                register(settings, api)
            } else {
                inputData.getString(KEY_ENDPOINT)?.let {
                    try {
                        api.unregisterEndpoint(it)
                    } catch (e: KimaiApi.HttpException) {
                        // 404: Kimai did not have it (refused, or already removed)
                        if (e.code != 404) throw e
                    }
                }
            }
            return Result.success()
        } catch (e: Exception) {
            settings.lastError = e.message ?: e.javaClass.simpleName
            if (e is IOException && e !is KimaiApi.HttpException && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            return Result.success()
        }
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
        const val SYNC = "sync"
        const val PERIODIC = "periodic"
        const val REGISTER = "register"
        const val UNREGISTER = "unregister"
        private const val LATER = "later"
        private const val KEY_ACTION = "action"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_CATALOG = "catalog"
        private const val MAX_ATTEMPTS = 20
        private const val MAX_REFUSED = 10
        private const val CATALOG_MS = 6 * 3_600_000L
        private const val MIN_PAUSE = 10_000L
        private const val MAX_PAUSE = 15 * 60_000L
        private val LOCK = Any()

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** [catalog]: read the projects and activities too, not only every few hours. */
        fun enqueue(context: Context, action: String, endpoint: String = "", catalog: Boolean = false) {
            val request = OneTimeWorkRequest.Builder(SyncWorker::class.java)
                .setInputData(data(action, endpoint, catalog))
                .setConstraints(network)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            // a sync asked for while one runs comes after it, so that it sees what changed meanwhile
            val (name, policy) = when (action) {
                REGISTER -> "register" to ExistingWorkPolicy.REPLACE
                SYNC -> "sync" to ExistingWorkPolicy.APPEND_OR_REPLACE
                else -> "endpoints" to ExistingWorkPolicy.APPEND_OR_REPLACE
            }
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequest.Builder(SyncWorker::class.java, 15, TimeUnit.MINUTES)
                .setInputData(data(PERIODIC, "", false))
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Another sync after a pause of a quarter of the time Kimai has been [away] (10 s to 15 minutes): soon after a
         * short outage, rarely during a long one, however many taps came meanwhile.
         */
        private fun later(context: Context, away: Long) {
            val pause = (away / 4).coerceIn(MIN_PAUSE, MAX_PAUSE)
            val request = OneTimeWorkRequest.Builder(SyncWorker::class.java)
                .setInputData(data(LATER, "", false))
                .setInitialDelay(pause, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("later", ExistingWorkPolicy.REPLACE, request)
        }

        /** Kimai or the network not there (or overloaded): worth trying again. */
        private fun transient(e: Exception): Boolean =
            e is IOException && (e !is KimaiApi.HttpException || e.code == 408 || e.code == 429 || e.code >= 500)

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private fun time(epochMillis: Long): String = TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

        private fun period(begin: Long, end: Long?): String = time(begin) + "–" + (end?.let { time(it).takeLast(5) } ?: "")

        private fun data(action: String, endpoint: String, catalog: Boolean) =
            Data.Builder().putString(KEY_ACTION, action).putString(KEY_ENDPOINT, endpoint).putBoolean(KEY_CATALOG, catalog).build()
    }
}
