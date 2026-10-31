package io.github.x6801318d8d.kimaitimer

/** What sending the queue needs from Kimai: [KimaiApi], or a fake one in the tests. */
interface Remote {
    /** The user's running timers. */
    fun active(): List<Timer>

    /** One entry; null if it was deleted. */
    fun timesheet(id: Int): Timer?

    /** Creates the entry of [start], stopped at [end] unless null; returns its id. */
    fun create(start: Change.Start, end: Long?): Int

    /** Starts the project and activity of entry [id] again at [begin]; returns the new entry's id. */
    fun restart(id: Int, begin: Long): Int

    fun setEnd(id: Int, end: Long)

    /** The entry that an earlier attempt to send [start] created, if Kimai's answer was lost; null if none. */
    fun find(start: Change.Start): Int?
}

/** Where the state and the queue live; [update] is atomic. */
interface Store {
    fun read(): Local

    fun update(block: (Local) -> Local)
}

/**
 * Sends the changes made on this device to Kimai, in order, with the times of the taps. While Kimai cannot be reached
 * a change stays in the queue and [flush] throws (the caller tries again later); a change that Kimai refuses
 * (validation, permissions) is dropped and handed to `onRefused` with the state before the drop, so that the user can
 * enter it by hand.
 *
 * Other devices may have changed things meanwhile, and Kimai runs one timer at a time:
 * - a timer started here while another one runs in Kimai: the one started later wins, the other one ends when it
 *   began;
 * - a timer stopped both here and elsewhere: the earlier stop counts;
 * - an entry deleted elsewhere: its change is dropped.
 */
class Sender(private val remote: Remote, private val store: Store, private val now: () -> Long = System::currentTimeMillis) {

    fun flush(onRefused: (change: Change, before: Local, message: String) -> Unit) {
        while (true) {
            val local = store.read()
            val change = local.changes.firstOrNull() ?: return
            try {
                when (change) {
                    is Change.Stop -> stop(change)
                    is Change.Start -> start(change, local)
                }
            } catch (e: KimaiApi.HttpException) {
                if (!refused(e)) throw e
                store.update { it.dropped(change) }
                onRefused(change, local, e.message ?: "HTTP ${e.code}")
            }
        }
    }

    private fun stop(change: Change.Stop) {
        // a negative id here belongs to a refused start
        if (change.id > 0) end(change.id, change.end)
        store.update { it.copy(changes = it.changes - change) }
    }

    private fun start(change: Change.Start, local: Local) {
        val stop = local.changes.filterIsInstance<Change.Stop>().firstOrNull { it.id == change.id }
        var end = stop?.end
        var id = if (change.sentAt > 0 && change.projectId > 0) remote.find(change) else null
        if (id == null) {
            for (other in remote.active()) {
                if (other.begin >= change.begin) {
                    end = minOf(end ?: other.begin, other.begin)
                } else {
                    try {
                        remote.setEnd(other.id, change.begin)
                    } catch (e: KimaiApi.HttpException) {
                        // the other entry cannot be changed: Kimai stops it itself when this one starts
                        if (!refused(e)) throw e
                    }
                }
            }
            val sent = change.copy(sentAt = now())
            store.update { state -> state.copy(changes = state.changes.map { if (it == change) sent else it }) }
            id = if (change.projectId > 0) {
                remote.create(sent, end)
            } else {
                remote.restart(change.from, change.begin).also { if (end != null) remote.setEnd(it, end) }
            }
        } else if (end != null) {
            end(id, end)
        }
        val created = id
        val ended = end != null
        store.update { state ->
            val mapped = state.created(change.id, created)
            // an entry that already has its end needs no stop any more
            mapped.copy(changes = mapped.changes.filter {
                !(it is Change.Start && it.id == change.id) && !(ended && it is Change.Stop && it.id == created)
            })
        }
    }

    /** Stops entry [id] at [end], unless it was deleted or already stopped earlier. */
    private fun end(id: Int, end: Long) {
        val entry = remote.timesheet(id) ?: return
        if (entry.end == 0L || entry.end > end) remote.setEnd(id, end)
    }

    companion object {
        /** Kimai answered, and will answer the same again: the change cannot go through. */
        fun refused(e: KimaiApi.HttpException): Boolean = e.code in 400..499 && e.code !in RETRIED

        /** Errors that may go away: Kimai or the network not there, the token not accepted (yet). */
        private val RETRIED = setOf(401, 408, 429)
    }
}
