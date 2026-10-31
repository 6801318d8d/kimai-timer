package io.github.x6801318d8d.kimaitimer

/**
 * The timer as this device knows it, which the notification and the app's own timer screen show. The buttons change
 * it at once, with the time of the tap, and queue the [changes] that Kimai still has to receive; once Kimai has them
 * all, what Kimai says replaces it ([refreshed]).
 *
 * One timer runs at a time, as in Kimai: starting one stops the running one at the same moment.
 */
data class Local(
    val running: Timer? = null,
    /** Finished entries, newest first; the first one is what Restart starts again. */
    val recent: List<Timer> = emptyList(),
    /** After a stop here, the notification's "Stopped" card stays until this time. */
    val stoppedUntil: Long = 0,
    val changes: List<Change> = emptyList(),
    /** The local id of the next timer started here: -1, -2... */
    val nextId: Int = -1,
) {
    val last: Timer?
        get() = recent.firstOrNull()

    fun stop(id: Int, now: Long): Local {
        val timer = running?.takeIf { it.id == id } ?: return this
        val stopped = timer.copy(end = now, duration = (now - timer.begin) / 1000)
        return copy(
            running = null,
            recent = (listOf(stopped) + recent).take(RECENT),
            stoppedUntil = now + STOPPED_MS,
            changes = changes + Change.Stop(id, now),
        )
    }

    /** A timer with the project and activity of [template] and [description]; the running one stops at [now]. */
    fun start(template: Timer, description: String, now: Long): Local {
        val base = running?.let { stop(it.id, now) } ?: this
        val timer = Timer(
            id = nextId,
            begin = now,
            project = template.project,
            activity = template.activity,
            customer = template.customer,
            description = description,
            projectId = template.projectId,
            activityId = template.activityId,
        )
        val change = Change.Start(
            id = timer.id,
            projectId = timer.projectId,
            activityId = timer.activityId,
            description = description,
            begin = now,
            title = timer.title,
            from = template.id.coerceAtLeast(0),
        )
        return base.copy(running = timer, stoppedUntil = 0, changes = base.changes + change, nextId = nextId - 1)
    }

    /** Kimai created the entry started here as [local]: it is [id] from now on. */
    fun created(local: Int, id: Int): Local {
        fun Timer.mapped() = if (this.id == local) copy(id = id) else this
        return copy(
            running = running?.mapped(),
            recent = recent.map { it.mapped() },
            changes = changes.map { if (it is Change.Stop && it.id == local) it.copy(id = id) else it },
        )
    }

    /** Kimai refused [change]: it goes, and with a refused start (whatever its [Change.Start.sentAt]) its stop too. */
    fun dropped(change: Change): Local = copy(
        changes = when (change) {
            is Change.Start -> changes.filterNot { (it is Change.Start && it.id == change.id) || (it is Change.Stop && it.id == change.id) }
            is Change.Stop -> changes - change
        },
    )

    /** What Kimai says, once it has every change made here; the "Stopped" card stays for the entry stopped here. */
    fun refreshed(running: Timer?, recent: List<Timer>): Local {
        val keep = running == null && recent.firstOrNull()?.id == last?.id
        return copy(running = running, recent = recent, stoppedUntil = if (keep) stoppedUntil else 0)
    }

    /** The entry [id] has changes that Kimai has not received. */
    fun pending(id: Int): Boolean = id < 0 || changes.any { it is Change.Stop && it.id == id }

    companion object {
        const val STOPPED_MS = 15 * 60_000L
        const val RECENT = 10
    }
}
