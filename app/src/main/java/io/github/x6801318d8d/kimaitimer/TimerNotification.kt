package io.github.x6801318d8d.kimaitimer

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The notification on the lock screen, one card with a big clock in every state:
 *
 * - running: the clock ticks in HH:MM:SS, with project, activity, customer and a Stop button;
 * - stopped (after a stop from the notification): the final duration and a Restart button, for a few minutes;
 * - idle, only with the direct connection, whose service must always show a notification: "No timer running", with
 *   the latest entry and Restart.
 *
 * The card shows the timer as this device knows it ([Local]): a tap changes it at once, with or without Kimai. While
 * Kimai has not received everything, the header says so ("2 changes waiting", "Kimai unreachable").
 *
 * The same layout in every state lets Android update the card in place; a notification identical to the one shown is
 * not posted again, since some systems (MIUI) redraw it visibly each time.
 *
 * The clock is a Chronometer, which Android keeps ticking without waking the app. It formats the time as MM:SS below
 * one hour and H:MM:SS above, so the missing zeros go in its format string ("00:%s", "0%s", "%s") and an alarm at the
 * 1 h and 10 h marks redraws the notification with the next format.
 *
 * A second notification lists the changes Kimai refused, so that they can be entered by hand.
 */
object TimerNotification {
    private const val CHANNEL = "timer"
    private const val CHANNEL_REFUSED = "refused"
    const val ID = 1
    private const val ID_REFUSED = 2
    private const val HOUR = 3_600_000L

    private enum class State { RUNNING, STOPPED, IDLE }

    /** What the card shows now, to skip posting the same again. */
    @Volatile private var shown: String? = null

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.channel_timer),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_timer_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val refused = NotificationChannel(
            CHANNEL_REFUSED,
            context.getString(R.string.channel_refused),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_refused_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, refused))
    }

    /** Shows what the saved state says, or removes the notification when there is nothing to show. */
    fun update(context: Context) {
        val card = card(context, persistent = StreamService.isWanted(context))
        if (card == null) {
            cancel(context)
        } else {
            notify(context, card)
        }
    }

    /** The notification for the saved state; [persistent]: the "No timer running" card rather than nothing. */
    fun current(context: Context, persistent: Boolean): Notification? = card(context, persistent)?.second

    fun cancel(context: Context) {
        scheduleRedraw(context, 0)
        shown = null
        NotificationManagerCompat.from(context).cancel(ID)
    }

    /** The changes Kimai refused, newest last; dismissing the notification forgets them. */
    @SuppressLint("MissingPermission")
    fun showRefused(context: Context, lines: List<String>) {
        val manager = NotificationManagerCompat.from(context)
        if (lines.isEmpty() || !manager.areNotificationsEnabled()) return
        val title = context.resources.getQuantityString(R.plurals.refused_title, lines.size, lines.size)
        val notification = NotificationCompat.Builder(context, CHANNEL_REFUSED)
            .setSmallIcon(R.drawable.ic_timer)
            .setColor(context.getColor(R.color.kimai_blue))
            .setContentTitle(title)
            .setContentText(lines.last())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n") + "\n" + context.getString(R.string.refused_hint)))
            .setContentIntent(openKimai(context))
            .setDeleteIntent(ActionReceiver.pending(context, ActionReceiver.FORGET_REFUSED, 0))
            .setAutoCancel(true)
            .build()
        manager.notify(ID_REFUSED, notification)
    }

    private fun card(context: Context, persistent: Boolean): Pair<String, Notification>? {
        val local = Tracker.read(context)
        val running = local.running
        val last = local.last
        val status = status(context, local)
        return when {
            running != null -> build(context, State.RUNNING, running, local, status, persistent)
            last != null && local.stoppedUntil > System.currentTimeMillis() -> build(context, State.STOPPED, last, local, status, persistent)
            persistent -> build(context, State.IDLE, last, local, status, persistent)
            else -> null
        }
    }

    /** The header line while Kimai has not received everything: changes waiting, or Kimai not answering. */
    private fun status(context: Context, local: Local): String? {
        val settings = Settings(context)
        val waiting = local.changes.size
        return when {
            waiting > 0 && (settings.unreachable || !Tracker.online(context)) ->
                context.resources.getQuantityString(R.plurals.waiting, waiting, waiting)
            settings.unreachable -> context.getString(R.string.unreachable)
            else -> null
        }
    }

    /** The notification and a key of what it shows. */
    private fun build(context: Context, state: State, timer: Timer?, local: Local, status: String?, persistent: Boolean): Pair<String, Notification> {
        val now = System.currentTimeMillis()
        val elapsed = when (state) {
            State.RUNNING -> now - timer!!.begin
            State.STOPPED -> timer!!.duration * 1000
            State.IDLE -> 0L
        }
        val format = when {
            elapsed < HOUR -> "00:%s"
            elapsed < 10 * HOUR -> "0%s"
            else -> "%s"
        }
        val details = listOfNotNull(timer?.activity, timer?.customer).filter { it.isNotEmpty() }.joinToString(" · ")
        val title = if (state == State.IDLE) context.getString(R.string.idle_title) else timer!!.project.ifEmpty { timer.title }
        val subtitle = when (state) {
            State.RUNNING -> details
            State.STOPPED -> listOf(context.getString(R.string.stopped), details).filter { it.isNotEmpty() }.joinToString(" · ")
            State.IDLE -> timer?.let { context.getString(R.string.idle_last, it.title, Timer.formatDuration(it.duration)) }.orEmpty()
        }
        val description = if (state == State.IDLE) "" else timer?.description.orEmpty()

        val small = RemoteViews(context.packageName, R.layout.notification_timer)
        val big = RemoteViews(context.packageName, R.layout.notification_timer_big)
        for (views in listOf(small, big)) {
            // the format before the base: setChronometer sets the base first, and a clock that does not tick keeps the
            // text drawn then ("11:09" instead of "00:11:09")
            views.setString(R.id.clock, "setFormat", format)
            views.setChronometer(R.id.clock, SystemClock.elapsedRealtime() - elapsed, format, state == State.RUNNING)
            views.setViewVisibility(R.id.clock, if (state == State.IDLE) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.title, title)
            views.setTextViewText(R.id.subtitle, subtitle)
        }
        big.setTextViewText(R.id.description, description)
        big.setViewVisibility(R.id.description, if (description.isEmpty()) View.GONE else View.VISIBLE)

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_timer)
            .setColor(context.getColor(R.color.kimai_blue))
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSubText(status)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openKimai(context))
        when (state) {
            State.RUNNING -> builder.addAction(0, context.getString(R.string.stop), ActionReceiver.pending(context, ActionReceiver.STOP, timer!!.id))
            State.STOPPED, State.IDLE -> timer?.let {
                builder.addAction(0, context.getString(R.string.restart), ActionReceiver.pending(context, ActionReceiver.RESTART, it.id))
            }
        }
        val stoppedFor = local.stoppedUntil - now
        if (state == State.STOPPED && !persistent) {
            builder.setAutoCancel(true).setTimeoutAfter(stoppedFor)
        } else {
            builder.setOngoing(true)
        }

        scheduleRedraw(
            context,
            when {
                state == State.RUNNING && elapsed < HOUR -> timer!!.begin + HOUR
                state == State.RUNNING && elapsed < 10 * HOUR -> timer!!.begin + 10 * HOUR
                // with the direct connection, the stopped card turns into the idle one
                state == State.STOPPED && persistent -> now + stoppedFor
                else -> 0L
            },
        )
        val key = listOf(state, timer?.id, timer?.begin, timer?.duration, title, subtitle, description, format, status, persistent).joinToString("|")
        return key to builder.build()
    }

    /** Tapping the notification opens Kimai in the app (or the app's timer screen, when Kimai cannot be reached). */
    private fun openKimai(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, card: Pair<String, Notification>) {
        if (card.first == shown) return
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            manager.notify(ID, card.second)
            shown = card.first
        }
    }

    /** Redraws the notification at [at] (epoch milliseconds), or cancels the pending redraw if 0. */
    private fun scheduleRedraw(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = ActionReceiver.pending(context, ActionReceiver.REDRAW, 0)
        if (at == 0L) {
            alarms.cancel(intent)
        } else if (alarms.canScheduleExactAlarms()) {
            // not a wakeup alarm: while the phone sleeps nobody looks at the clock, and it runs as soon as it wakes
            alarms.setExact(AlarmManager.RTC, at, intent)
        } else {
            alarms.set(AlarmManager.RTC, at, intent)
        }
    }
}
