package io.github.x6801318d8d.kimaitimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.math.abs

class SenderTest {

    /** A Kimai of one user, in memory: entries by id, plus switches to break it. */
    private class FakeKimai : Remote {
        val entries = linkedMapOf<Int, Timer>()
        val modified = mutableMapOf<Int, Long>()
        var nextId = 100
        var clock = 10_000_000L
        /** Every call fails as if the server were off. */
        var down = false
        /** The next create goes through, but its answer gets lost. */
        var loseAnswer = false
        /** The next create or setEnd is refused with this status. */
        var refuse = 0
        var creates = 0

        fun add(timer: Timer): Timer {
            entries[timer.id] = timer
            modified[timer.id] = clock
            return timer
        }

        private fun call() {
            if (down) throw IOException("connection refused")
            clock += 1000
        }

        private fun refused() {
            if (refuse != 0) throw KimaiApi.HttpException(refuse, "HTTP $refuse").also { refuse = 0 }
        }

        override fun active(): List<Timer> {
            call()
            return entries.values.filter { it.end == 0L }.sortedByDescending { it.begin }
        }

        override fun timesheet(id: Int): Timer? {
            call()
            return entries[id]
        }

        override fun create(start: Change.Start, end: Long?): Int {
            call()
            refused()
            creates++
            val id = nextId++
            add(Timer(id, start.begin, "P${start.projectId}", "A${start.activityId}", "", start.description, end = end ?: 0,
                projectId = start.projectId, activityId = start.activityId))
            if (loseAnswer) {
                loseAnswer = false
                throw IOException("connection reset")
            }
            return id
        }

        override fun restart(id: Int, begin: Long): Int {
            call()
            val from = entries.getValue(id)
            val new = nextId++
            add(from.copy(id = new, begin = begin, end = 0, description = ""))
            return new
        }

        override fun setEnd(id: Int, end: Long) {
            call()
            refused()
            val entry = entries[id] ?: throw KimaiApi.HttpException(404, "HTTP 404")
            add(entry.copy(end = end))
        }

        override fun find(start: Change.Start): Int? {
            call()
            return entries.values.filter {
                it.projectId == start.projectId && it.activityId == start.activityId &&
                    abs(it.begin - start.begin) <= 300_000 && modified.getValue(it.id) >= start.sentAt - 300_000
            }.minByOrNull { abs(it.begin - start.begin) }?.id
        }
    }

    private class MemoryStore(var local: Local = Local()) : Store {
        override fun read() = local

        override fun update(block: (Local) -> Local) {
            local = block(local)
        }
    }

    private val kimai = FakeKimai()
    private val store = MemoryStore()
    private val refused = mutableListOf<Change>()
    private val sender = Sender(kimai, store) { kimai.clock }
    private val writing = Timer(0, 0, "Paper", "Writing", "Research", "", projectId = 7, activityId = 3)

    private fun flush() = sender.flush { change, _, _ -> refused += change }

    private fun flushFails() {
        try {
            flush()
            fail("expected the flush to fail")
        } catch (e: IOException) {
            // Kimai not there: the changes wait
        }
    }

    /** A timer that Kimai has and this device knows as running. */
    private fun runningInKimai(begin: Long): Timer {
        val timer = kimai.add(Timer(1, begin, "Paper", "Writing", "Research", "", projectId = 7, activityId = 3))
        store.local = store.local.copy(running = timer)
        return timer
    }

    @Test
    fun stopWhileKimaiIsDownIsSentWithTheTimeOfTheTap() {
        runningInKimai(begin = 1_000_000)
        store.update { it.stop(1, 5_000_000) }
        kimai.down = true
        flushFails()
        assertEquals(1, store.local.changes.size)

        kimai.down = false
        flush()
        assertEquals(5_000_000, kimai.entries.getValue(1).end)
        assertTrue(store.local.changes.isEmpty())
    }

    @Test
    fun startAndStopOfflineBecomeOneEntryWithBothTimes() {
        store.update { it.start(writing, "draft", 1_000_000).stop(-1, 2_000_000) }
        flush()
        assertEquals(1, kimai.creates)
        val entry = kimai.entries.values.single()
        assertEquals(1_000_000, entry.begin)
        assertEquals(2_000_000, entry.end)
        assertEquals("draft", entry.description)
        assertTrue(store.local.changes.isEmpty())
        assertEquals(entry.id, store.local.last!!.id)
    }

    @Test
    fun timerStartedOfflineGetsItsKimaiIdAndLaterStop() {
        store.update { it.start(writing, "", 1_000_000) }
        flush()
        val id = store.local.running!!.id
        assertTrue(id > 0)
        assertEquals(0L, kimai.entries.getValue(id).end)

        store.update { it.stop(id, 3_000_000) }
        flush()
        assertEquals(3_000_000, kimai.entries.getValue(id).end)
    }

    @Test
    fun startingHereStopsTheRunningTimerAtTheSameMoment() {
        runningInKimai(begin = 1_000_000)
        store.update { it.start(writing, "", 2_000_000) }
        assertEquals(listOf(Change.Stop(1, 2_000_000)), store.local.changes.filterIsInstance<Change.Stop>())
        flush()
        assertEquals(2_000_000, kimai.entries.getValue(1).end)
        assertEquals(2_000_000, kimai.entries.getValue(store.local.running!!.id).begin)
    }

    @Test
    fun timerStartedLaterElsewhereEndsTheOneStartedHere() {
        store.update { it.start(writing, "", 1_000_000) }
        kimai.add(Timer(50, 1_500_000, "Other", "Work", "", ""))
        flush()
        val mine = kimai.entries.values.single { it.id != 50 }
        assertEquals(1_500_000, mine.end)
        assertEquals(0L, kimai.entries.getValue(50).end)
    }

    @Test
    fun timerStartedEarlierElsewhereEndsWhenTheOneStartedHereBegan() {
        kimai.add(Timer(50, 500_000, "Other", "Work", "", ""))
        store.update { it.start(writing, "", 1_000_000) }
        flush()
        assertEquals(1_000_000, kimai.entries.getValue(50).end)
        assertEquals(0L, kimai.entries.getValue(store.local.running!!.id).end)
    }

    @Test
    fun theEarlierStopCounts() {
        runningInKimai(begin = 1_000_000)
        store.update { it.stop(1, 5_000_000) }
        // stopped earlier on the computer
        kimai.add(kimai.entries.getValue(1).copy(end = 3_000_000))
        flush()
        assertEquals(3_000_000, kimai.entries.getValue(1).end)

        kimai.add(kimai.entries.getValue(1).copy(end = 6_000_000))
        store.update { it.copy(changes = listOf(Change.Stop(1, 5_000_000))) }
        flush()
        assertEquals(5_000_000, kimai.entries.getValue(1).end)
    }

    @Test
    fun lostAnswerDoesNotMakeADuplicate() {
        store.update { it.start(writing, "", 1_000_000) }
        kimai.loseAnswer = true
        flushFails()
        flush()
        assertEquals(1, kimai.creates)
        assertEquals(1, kimai.entries.size)
        assertEquals(kimai.entries.keys.single(), store.local.running!!.id)
    }

    @Test
    fun refusedStartIsDroppedWithItsStopAndReported() {
        store.update { it.start(writing, "", 1_000_000).stop(-1, 2_000_000) }
        kimai.refuse = 400
        flush()
        assertTrue(store.local.changes.isEmpty())
        assertTrue(kimai.entries.isEmpty())
        assertEquals(1, refused.size)
        assertTrue(refused.single() is Change.Start)
    }

    @Test
    fun stopOfAnEntryDeletedElsewhereIsDropped() {
        store.local = Local(running = Timer(1, 1_000_000, "Paper", "Writing", "", ""))
        store.update { it.stop(1, 2_000_000) }
        flush()
        assertTrue(store.local.changes.isEmpty())
        assertTrue(refused.isEmpty())
    }

    @Test
    fun refusedTokenKeepsTheQueue() {
        store.update { it.start(writing, "", 1_000_000) }
        kimai.refuse = 401
        flushFails()
        assertEquals(1, store.local.changes.size)
        flush()
        assertTrue(store.local.changes.isEmpty())
    }

    @Test
    fun refreshKeepsTheStoppedCardOfTheEntryStoppedHere() {
        runningInKimai(begin = 1_000_000)
        store.update { it.stop(1, 2_000_000) }
        flush()
        val refreshed = store.local.refreshed(null, listOf(kimai.entries.getValue(1)))
        assertEquals(2_000_000 + Local.STOPPED_MS, refreshed.stoppedUntil)
        assertNull(refreshed.running)
        assertEquals(0L, refreshed.refreshed(null, listOf(Timer(9, 0, "", "", "", ""))).stoppedUntil)
    }

    @Test
    fun stateSurvivesItsJson() {
        val local = Local().start(writing, "d", 1_000_000).stop(-1, 2_000_000).start(writing, "", 3_000_000)
        for (change in local.changes) assertEquals(change, Change.fromJson(change.toJson()))
        for (timer in local.recent + listOfNotNull(local.running)) assertEquals(timer, Timer.fromJson(timer.toJson()))
    }
}
