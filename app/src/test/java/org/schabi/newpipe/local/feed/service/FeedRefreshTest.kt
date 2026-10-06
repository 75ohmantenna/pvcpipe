package org.schabi.newpipe.local.feed.service

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Notification
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.observers.TestObserver
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.io.IOException
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.database.subscription.SubscriptionEntity

class FeedRefreshTest {
    private val threshold = OffsetDateTime.parse("2026-10-06T10:00:00Z")

    @Test
    fun `successful refresh stores results before trimming and announces completion`() {
        val operations = TestOperations(4)
        val events = ArrayList<FeedEventManager.Event>()
        val refresh = refresh(operations, events)
        val progress = refresh.progress.test()

        val result = refresh.result.test().assertComplete().assertNoErrors().values().single()

        assertEquals(setOf(1L, 2L, 3L, 4L), result.map { it.value!!.uid }.toSet())
        assertTrue(result.all { it.isOnNext })
        assertEquals(result, operations.storedBatches.single())
        assertEquals(listOf("store", "trim"), operations.effects)
        assertEquals(1, operations.trimCalls)
        assertEquals(7L, operations.selectedGroup)
        assertEquals(threshold, operations.selectedThreshold)
        assertTrue(operations.fetchMethods.all { it })
        assertEquals(FeedLoadState("", 4, 0), progress.values().first())
        assertEquals(FeedLoadState("processing", -1, -1), progress.values().last())
        assertEquals(listOf(0, 1, 2, 3, 4), progress.values().dropLast(1).map { it.currentProgress })
        assertTrue(events.last() is FeedEventManager.Event.SuccessResultEvent)
    }

    @Test
    fun `empty selection still trims and publishes successful completion`() {
        val operations = TestOperations(0)
        val events = ArrayList<FeedEventManager.Event>()
        val refresh = refresh(operations, events)
        val progress = refresh.progress.test()

        refresh.result.test().assertValue(emptyList()).assertComplete()

        assertTrue(operations.fetches.isEmpty())
        assertTrue(operations.storedBatches.isEmpty())
        assertEquals(listOf("trim"), operations.effects)
        assertEquals(listOf(FeedLoadState("processing", -1, -1)), progress.values())
        assertTrue(events.last() is FeedEventManager.Event.SuccessResultEvent)
    }

    @Test
    fun `refresh persists full batches and final remainder with progress for every channel`() {
        val operations = TestOperations(45)
        val events = ArrayList<FeedEventManager.Event>()
        val refresh = refresh(operations, events)
        val progress = refresh.progress.test()

        val result = refresh.result.test().assertComplete().values().single()

        assertEquals(listOf(20, 20, 5), operations.storedBatches.map { it.size })
        assertEquals((1L..45L).toSet(), operations.storedBatches.flatten().map { it.value!!.uid }.toSet())
        assertEquals(45, result.size)
        assertEquals((0..45).toList(), progress.values().dropLast(1).map { it.currentProgress })
        assertTrue(progress.values().dropLast(1).all { it.maxProgress == 45 })
        assertEquals(listOf("store", "store", "store", "trim"), operations.effects)
    }

    @Test
    fun `channel failure is materialized and other channels still reach storage`() {
        val cause = IOException("channel unavailable")
        val operations = TestOperations(3).apply { channelErrors[2L] = cause }
        val events = ArrayList<FeedEventManager.Event>()
        val refresh = refresh(operations, events)
        val progress = refresh.progress.test()

        val result = refresh.result.test().assertComplete().assertNoErrors().values().single()

        assertEquals(setOf(1L, 3L), result.mapNotNull { it.value?.uid }.toSet())
        val error = result.single { it.isOnError }.error as FeedRefresh.RequestException
        assertEquals(2L, error.subscriptionId)
        assertEquals("42:https://example.test/channel/2", error.message)
        assertSame(cause, error.cause)
        assertEquals(result, operations.storedBatches.single())
        val completed = events.last() as FeedEventManager.Event.SuccessResultEvent
        assertEquals(listOf(error), completed.itemsErrors)
        assertEquals(listOf(0, 1, 2, 3), progress.values().dropLast(1).map { it.currentProgress })
        assertEquals(1, operations.trimCalls)
    }

    @Test
    fun `fatal subscription selection error performs no writes or trimming`() {
        val cause = IOException("query failed")
        val operations = TestOperations(4).apply { queryError = cause }
        val events = ArrayList<FeedEventManager.Event>()

        refresh(operations, events).result.test().assertError(cause)

        assertTrue(operations.fetches.isEmpty())
        assertTrue(operations.effects.isEmpty())
        assertFalse(events.any { it is FeedEventManager.Event.SuccessResultEvent })
    }

    @Test
    fun `fatal batch storage error stops cleanup and does not announce success`() {
        val cause = IOException("transaction failed")
        val operations = TestOperations(3).apply { storeError = cause }
        val events = ArrayList<FeedEventManager.Event>()

        refresh(operations, events).result.test().assertError(cause)

        assertEquals(1, operations.storeAttempts)
        assertTrue(operations.storedBatches.isEmpty())
        assertEquals(0, operations.trimCalls)
        assertFalse(events.any { it is FeedEventManager.Event.SuccessResultEvent })
    }

    @Test
    fun `legacy fatal trim error preserves storage but publishes no terminal feed event`() {
        val cause = IOException("cleanup failed")
        val operations = TestOperations(3).apply { trimError = cause }
        val events = ArrayList<FeedEventManager.Event>()

        refresh(operations, events).result.test().assertError(cause)

        assertEquals(3, operations.storedBatches.single().size)
        assertEquals(listOf("store", "trim"), operations.effects)
        assertEquals(1, operations.trimCalls)
        assertFalse(events.any { it is FeedEventManager.Event.SuccessResultEvent })
        assertFalse(events.any { it is FeedEventManager.Event.ErrorResultEvent })
    }

    @Test
    fun `cancellation before subscription skips extraction but completes cleanup`() {
        val operations = TestOperations(30)
        val events = ArrayList<FeedEventManager.Event>()
        val refresh = refresh(operations, events)
        refresh.cancel()

        refresh.result.test().assertValue(emptyList()).assertComplete()

        assertTrue(operations.fetches.isEmpty())
        assertTrue(operations.storedBatches.isEmpty())
        assertEquals(listOf("trim"), operations.effects)
        assertTrue(events.last() is FeedEventManager.Event.SuccessResultEvent)
    }

    @Test
    fun `cooperative cancellation flushes a partial batch including current extraction`() {
        val operations = TestOperations(30)
        val events = ArrayList<FeedEventManager.Event>()
        val refresh = refresh(operations, events)
        operations.afterFetch = { if (operations.fetches.size == 5) refresh.cancel() }

        val result = refresh.result.test().assertComplete().values().single()

        assertEquals(5, operations.fetches.size)
        assertEquals(5, result.size)
        assertEquals(operations.fetches.toSet(), result.map { it.value!!.uid }.toSet())
        assertEquals(result, operations.storedBatches.single())
        assertEquals(listOf("store", "trim"), operations.effects)
        assertTrue(events.last() is FeedEventManager.Event.SuccessResultEvent)
    }

    @Test
    fun `a fresh refresh is not cancelled by an earlier refresh`() {
        val operations = TestOperations(2)
        val events = ArrayList<FeedEventManager.Event>()
        refresh(operations, events).cancel()

        refresh(operations, events).result.test().assertComplete().assertValue { it.size == 2 }

        assertEquals(2, operations.fetches.size)
        assertEquals(1, operations.trimCalls)
    }

    @Test
    fun `legacy cold result resubscription repeats extraction storage and completion`() {
        val io = TestScheduler()
        val main = TestScheduler()
        val operations = TestOperations(3)
        val events = ArrayList<FeedEventManager.Event>()
        val refresh = refresh(operations, events, io, main)

        val first = refresh.result.test()
        drain(io, main)
        first.assertComplete()
        val second = refresh.result.test()
        drain(io, main)
        second.assertComplete()

        assertEquals(2, operations.queries)
        assertEquals(6, operations.fetches.size)
        assertEquals(2, operations.storedBatches.size)
        assertEquals(2, operations.trimCalls)
        assertEquals(2, events.filterIsInstance<FeedEventManager.Event.SuccessResultEvent>().size)
    }

    @Test
    fun `legacy observer disposal loses collected partial results and skips cleanup`() {
        val io = TestScheduler()
        val main = TestScheduler()
        val operations = TestOperations(8)
        val events = ArrayList<FeedEventManager.Event>()
        val refresh = refresh(operations, events, io, main)
        lateinit var result: TestObserver<List<Notification<FeedUpdateInfo>>>
        val progress = refresh.progress.subscribe { state ->
            if (state.currentProgress == 5) result.dispose()
        }
        result = refresh.result.test()

        drain(io, main)

        assertTrue(result.isDisposed)
        assertTrue(operations.fetches.size >= 5)
        assertTrue(operations.storedBatches.isEmpty())
        assertEquals(0, operations.trimCalls)
        assertFalse(events.any { it is FeedEventManager.Event.SuccessResultEvent })
        progress.dispose()
    }

    private fun refresh(
        operations: TestOperations,
        events: MutableList<FeedEventManager.Event>,
        io: Scheduler = Schedulers.trampoline(),
        main: Scheduler = Schedulers.trampoline()
    ) = FeedRefresh(7L, threshold, true, operations, "processing", io, main) { events.add(it) }

    private fun drain(io: TestScheduler, main: TestScheduler) {
        repeat(8) {
            io.triggerActions()
            main.triggerActions()
        }
    }

    private class TestOperations(count: Int) : FeedRefresh.Operations {
        private val selected = (1..count).map { index ->
            SubscriptionEntity(
                uid = index.toLong(),
                serviceId = 42,
                url = "https://example.test/channel/$index",
                name = "channel $index"
            )
        }
        val fetches = ArrayList<Long>()
        val fetchMethods = ArrayList<Boolean>()
        val channelErrors = HashMap<Long, Throwable>()
        val storedBatches = ArrayList<List<Notification<FeedUpdateInfo>>>()
        val effects = ArrayList<String>()
        var selectedGroup: Long? = null
        var selectedThreshold: OffsetDateTime? = null
        var queryError: Throwable? = null
        var storeError: Throwable? = null
        var trimError: Throwable? = null
        var afterFetch: (() -> Unit)? = null
        var queries = 0
        var storeAttempts = 0
        var trimCalls = 0

        override fun subscriptions(
            groupId: Long,
            outdatedThreshold: OffsetDateTime
        ): Flowable<List<SubscriptionEntity>> {
            selectedGroup = groupId
            selectedThreshold = outdatedThreshold
            return Flowable.defer {
                queries++
                queryError?.let { Flowable.error(it) } ?: Flowable.just(selected)
            }
        }

        override fun fetch(subscriptionEntity: SubscriptionEntity, useFeedExtractor: Boolean): FeedUpdateInfo {
            fetches.add(subscriptionEntity.uid)
            fetchMethods.add(useFeedExtractor)
            afterFetch?.invoke()
            channelErrors[subscriptionEntity.uid]?.let { throw it }
            return FeedUpdateInfo(
                uid = subscriptionEntity.uid,
                notificationMode = subscriptionEntity.notificationMode,
                name = subscriptionEntity.name!!,
                avatarUrl = null,
                url = subscriptionEntity.url!!,
                serviceId = subscriptionEntity.serviceId,
                description = null,
                subscriberCount = null,
                streams = emptyList(),
                errors = emptyList()
            )
        }

        override fun storeBatch(list: List<Notification<FeedUpdateInfo>>): List<Throwable> {
            storeAttempts++
            storeError?.let { throw it }
            storedBatches.add(list.toList())
            effects.add("store")
            list.forEach { it.value?.newStreams = emptyList() }
            return list.mapNotNull { it.error }
        }

        override fun trim() {
            trimCalls++
            effects.add("trim")
            trimError?.let { throw it }
        }
    }
}
