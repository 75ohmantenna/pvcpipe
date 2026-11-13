package org.schabi.newpipe.local.feed.service

import io.reactivex.rxjava3.schedulers.TestScheduler
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.local.feed.notifications.NotificationWorker
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ErrorResultEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.IdleEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ProgressEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.SuccessResultEvent

class FeedEventManagerTest {
    private val store = FeedEventManager.EventStore()

    @Test
    fun `superseded producer cannot publish progress completion error or close newer run`() {
        val observer = store.events(7L).test()
        val old = store.begin(7L)
        val newer = store.begin(7L)
        newer.post(ProgressEvent(1, 10))
        val expected = observer.values().last()
        old.post(ProgressEvent(9, 9))
        old.post(SuccessResultEvent())
        old.post(ErrorResultEvent(IOException("old")))
        old.close()
        assertEquals(expected, observer.values().last())
        assertTrue(store.isCurrent(expected))
    }

    @Test
    fun `old terminal acknowledgment cannot clear a newer run`() {
        val observer = store.events(7L).test()
        store.begin(7L).post(SuccessResultEvent())
        val oldResult = observer.values().last()
        store.begin(7L).post(ProgressEvent(1, 10))
        val newerProgress = observer.values().last()
        store.acknowledge(oldResult)
        store.events(7L).test().assertValue(newerProgress)
        assertFalse(store.isCurrent(oldResult))
    }

    @Test
    fun `acknowledgment preserves displayed result but new observers replay idle`() {
        val observer = store.events(7L).test()
        val session = store.begin(7L)
        session.post(ErrorResultEvent(IOException("failure")))
        val terminal = observer.values().last()
        store.acknowledge(terminal)
        assertEquals(terminal, observer.values().last())
        store.events(7L).test().assertValue { it.event is IdleEvent }
        // Normal teardown must not publish a second terminal or reopen progress.
        session.close()
        session.post(ProgressEvent(3, 9))
        assertEquals(terminal, observer.values().last())
    }

    @Test
    fun `terminal survives teardown until acknowledged and cannot be overwritten`() {
        val observer = store.events(7L).test()
        val session = store.begin(7L)
        session.post(SuccessResultEvent())
        val result = observer.values().last()
        session.close()
        session.post(ErrorResultEvent(IOException("late")))
        session.post(ProgressEvent(1, 10))
        store.events(7L).test().assertValue(result)
    }

    @Test
    fun `closing interrupted run releases spinner and fences draining producer`() {
        val observer = store.events(7L).test()
        val session = store.begin(7L)
        session.close()
        val idle = observer.values().last()
        assertTrue(idle.event is IdleEvent)
        session.post(SuccessResultEvent())
        session.post(ErrorResultEvent(IOException("late")))
        assertEquals(idle, observer.values().last())
    }

    @Test
    fun `unrelated group sees idle and cannot acknowledge another groups error`() {
        val selected = store.events(7L).test()
        val other = store.events(8L).test()
        val all = store.events(FeedGroupEntity.GROUP_ALL_ID).test()
        store.begin(7L).post(ErrorResultEvent(IOException("group7")))
        assertTrue(selected.values().last().event is ErrorResultEvent)
        assertTrue(other.values().last().event is IdleEvent)
        assertTrue(all.values().last().event is IdleEvent)
        store.acknowledge(other.values().last())
        store.events(7L).test().assertValue { it.event is ErrorResultEvent }
        store.begin(8L)
        assertTrue(selected.values().last().event is IdleEvent)
        assertTrue(other.values().last().event is ProgressEvent)
    }

    @Test
    fun `unrelated progress does not repeatedly reload idle and queued idle stays current`() {
        val selected = store.events(7L).test()
        val other = store.begin(8L)
        val idle = selected.values().last()
        repeat(100) { other.post(ProgressEvent(it, 100)) }
        other.post(SuccessResultEvent())
        assertEquals(2, selected.values().size) // initial Idle plus one new run's Idle
        assertTrue(store.isCurrent(idle))
        store.begin(7L)
        assertFalse(store.isCurrent(idle))
    }

    @Test
    fun `all groups refresh is relevant to every group`() {
        store.begin(FeedGroupEntity.GROUP_ALL_ID).post(ProgressEvent(1, 10))
        store.events(7L).test().assertValue { it.event == ProgressEvent(1, 10) }
        store.events(8L).test().assertValue { it.event == ProgressEvent(1, 10) }
    }

    @Test
    fun `delayed terminal progress and idle cannot render over newer progress`() {
        val scheduler = TestScheduler()
        val rendered = mutableListOf<FeedEventManager.Envelope>()
        val observer = store.events(7L)
            .throttleLatest(100L, TimeUnit.MILLISECONDS, scheduler)
            .observeOn(scheduler)
            .subscribe { if (store.isCurrent(it)) rendered.add(it) }
        val old = store.begin(7L)
        old.post(ProgressEvent(1, 2))
        old.post(SuccessResultEvent())
        store.begin(7L).post(ProgressEvent(1, 10))
        scheduler.advanceTimeBy(1L, TimeUnit.SECONDS)
        assertEquals(listOf(ProgressEvent(1, 10)), rendered.map { it.event })
        observer.dispose()
    }

    @Test
    fun `reentrant new run cannot be cleared by acknowledgment from old subscriber`() {
        val observer = store.events(7L).subscribe {
            if (it.event is SuccessResultEvent) {
                store.begin(7L).post(ProgressEvent(1, 10))
                store.acknowledge(it)
            }
        }
        store.begin(7L).post(SuccessResultEvent())
        store.events(7L).test().assertValue { it.event == ProgressEvent(1, 10) }
        observer.dispose()
    }

    @Test
    fun `reentrant subscriber after acknowledgment starts idle even before processor drains`() {
        var replay: FeedEventManager.Envelope? = null
        val observer = store.events(7L).subscribe {
            if (it.event is SuccessResultEvent) {
                store.acknowledge(it)
                replay = store.events(7L).blockingFirst()
            }
        }
        store.begin(7L).post(SuccessResultEvent())
        assertTrue(replay?.event is IdleEvent)
        observer.dispose()
    }

    @Test
    fun `concurrent worker foreground slots are unique and separate from manual service`() {
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val workers = (1..4).map {
            Thread { repeat(100) { ids.add(NotificationWorker.allocateForegroundNotificationId()) } }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(5000L) }
        assertTrue(workers.none { it.isAlive })
        assertEquals(400, ids.size)
        assertTrue(ids.all { it < 0 && it != FeedLoadService.NOTIFICATION_ID })
    }

    @Test
    fun `single argument processing event already selects message constructor`() {
        val event = ProgressEvent(R.string.feed_processing_message)
        assertEquals(-1, event.currentProgress)
        assertEquals(-1, event.maxProgress)
        assertEquals(R.string.feed_processing_message, event.progressMessage)
    }
}
