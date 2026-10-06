/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.service

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.SubscriptionManager

/** Exercises the refresh pipeline and production storage against an isolated Room database. */
class FeedRefreshDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var preferences: SharedPreferences
    private lateinit var preferencesName: String
    private lateinit var releaseFetch: CountDownLatch
    private lateinit var terminal: CountDownLatch
    private val events = ConcurrentLinkedQueue<FeedEventManager.Event>()
    private var started = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferencesName = "feed-refresh-integration-${UUID.randomUUID()}"
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        releaseFetch = CountDownLatch(1)
        terminal = CountDownLatch(1)
    }

    @After
    fun tearDown() {
        // Release an extraction even if an assertion fails while it is blocked.
        releaseFetch.countDown()
        if (started) {
            terminal.await(10, TimeUnit.SECONDS)
        }
        database.close()
        preferences.edit().clear().commit()
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun serviceDestructionCommitsPartialBatchAndReplayDoesNotWriteAgain() {
        val subscriptions = insertSubscriptions(12)
        val reachedFifth = CountDownLatch(1)
        val fetched = ConcurrentLinkedQueue<Long>()
        val fetchCount = AtomicInteger()
        val refresh = refresh { subscription ->
            fetched.add(subscription.uid)
            if (fetchCount.incrementAndGet() == 5) {
                reachedFifth.countDown()
                check(releaseFetch.await(10, TimeUnit.SECONDS)) { "Extraction was not released" }
            }
            update(subscription)
        }

        // Inject the active run and observers into a cold foreground host. Do not invoke creation
        // or startup, which would involve application-wide storage and notifications.
        val service = FeedLoadService()
        val progressObserver = refresh.progress.subscribe { }
        started = true
        val observer = refresh.result.test()
        setField(service, "feedRefresh", refresh)
        setField(service, "loadingDisposable", observer)
        setField(service, "notificationDisposable", progressObserver)
        assertTrue("The fifth extraction must be in flight", reachedFifth.await(10, TimeUnit.SECONDS))
        assertEquals(0, rowCount("streams"))
        assertEquals(0, rowCount("feed"))

        InstrumentationRegistry.getInstrumentation().runOnMainSync { service.onDestroy() }
        assertTrue("Destroyed hosts must detach result observation", observer.isDisposed)
        assertTrue("Destroyed hosts must detach progress observation", progressObserver.isDisposed)
        releaseFetch.countDown()

        assertTrue("Accepted results must finish persisting", terminal.await(10, TimeUnit.SECONDS))
        val replay = refresh.result.timeout(10, TimeUnit.SECONDS).blockingGet()
        assertEquals(5, replay.size)
        assertEquals(5, fetchCount.get())
        assertEquals(5, rowCount("streams"))
        assertEquals(5, rowCount("feed"))
        assertEquals(5, updatedSubscriptionCount())
        assertEquals(1, events.filterIsInstance<FeedEventManager.Event.SuccessResultEvent>().size)
        assertTrue(events.filterIsInstance<FeedEventManager.Event.ErrorResultEvent>().isEmpty())
        assertTrue(replay.all { it.value!!.newStreams.size == 1 })

        val completedIds = fetched.toSet()
        subscriptions.forEach { original ->
            val stored = database.subscriptionDAO().getSubscription(original.uid)
            if (original.uid in completedIds) {
                assertEquals("updated-${original.uid}", stored.name)
                assertEquals("new description", stored.description)
                assertEquals(42L, stored.subscriberCount)
                assertTrue(database.streamDAO().exists(original.serviceId, streamUrl(original.uid)))
            } else {
                assertEquals(original.name, stored.name)
                assertFalse(database.streamDAO().exists(original.serviceId, streamUrl(original.uid)))
            }
        }

        // Repeated observation must not repeat the accepted refresh or its database writes.
        refresh.result.timeout(10, TimeUnit.SECONDS).blockingGet()
        assertEquals(5, fetchCount.get())
        assertEquals(5, rowCount("streams"))
        assertEquals(5, rowCount("feed"))
        assertEquals(1, events.filterIsInstance<FeedEventManager.Event.SuccessResultEvent>().size)
    }

    @Test
    fun batchFailureRollsBackEarlierStreamsFeedLinksAndMetadataAndPublishesOneError() {
        val subscriptions = insertSubscriptions(3)
        val fetchCount = AtomicInteger()
        var deletedId = -1L
        val refresh = refresh { subscription ->
            if (fetchCount.incrementAndGet() == 3) {
                // All subscriptions were selected already. Remove the final result's subscription
                // so actual storage fails after processing the earlier results in the same batch.
                deletedId = subscription.uid
                database.subscriptionDAO().delete(subscription)
            }
            update(subscription)
        }

        started = true
        refresh.result.test()
            .awaitDone(10, TimeUnit.SECONDS)
            .assertError { true }
            .assertNoValues()
        assertTrue("The storage error must be published", terminal.await(10, TimeUnit.SECONDS))
        assertEquals(3, fetchCount.get())
        assertEquals(0, rowCount("streams"))
        assertEquals(0, rowCount("feed"))
        assertEquals(0, rowCount("feed_last_updated"))
        assertEquals(2, rowCount("subscriptions"))
        subscriptions.filter { it.uid != deletedId }.forEach { original ->
            val stored = database.subscriptionDAO().getSubscription(original.uid)
            assertEquals(original.name, stored.name)
            assertEquals(original.description, stored.description)
            assertEquals(original.subscriberCount, stored.subscriberCount)
        }
        assertEquals(1, events.filterIsInstance<FeedEventManager.Event.ErrorResultEvent>().size)
        assertTrue(events.filterIsInstance<FeedEventManager.Event.SuccessResultEvent>().isEmpty())

        refresh.result.test()
            .awaitDone(10, TimeUnit.SECONDS)
            .assertError { true }
            .assertNoValues()
        assertEquals(3, fetchCount.get())
        assertEquals(0, rowCount("streams"))
        assertEquals(1, events.filterIsInstance<FeedEventManager.Event.ErrorResultEvent>().size)
    }

    private fun refresh(fetch: (SubscriptionEntity) -> FeedUpdateInfo): FeedRefresh {
        val real = FeedLoadManager.AndroidOperations(
            context,
            preferences,
            SubscriptionManager(database),
            FeedDatabaseManager(database)
        )
        val operations = object : FeedRefresh.Operations by real {
            override fun fetch(
                subscriptionEntity: SubscriptionEntity,
                useFeedExtractor: Boolean
            ): FeedUpdateInfo = fetch(subscriptionEntity)
        }
        return FeedRefresh(
            groupId = FeedGroupEntity.GROUP_ALL_ID,
            outdatedThreshold = OffsetDateTime.now(ZoneOffset.UTC),
            useFeedExtractor = false,
            operations = operations,
            processingDescription = "processing",
            // Room emits the cold subscription query on its own executor. Trampoline schedulers
            // then keep extraction ordering deterministic without replacing global Rx schedulers.
            io = Schedulers.trampoline(),
            main = Schedulers.trampoline(),
            postEvent = { event ->
                events.add(event)
                if (event is FeedEventManager.Event.SuccessResultEvent ||
                    event is FeedEventManager.Event.ErrorResultEvent
                ) {
                    terminal.countDown()
                }
            }
        )
    }

    private fun insertSubscriptions(count: Int): List<SubscriptionEntity> {
        return database.subscriptionDAO().upsertAll(
            (1..count).map { index ->
                SubscriptionEntity(
                    serviceId = 1,
                    url = "https://example.com/channel/$index",
                    name = "original-$index",
                    description = "old description",
                    subscriberCount = 7L,
                    notificationMode = NotificationMode.ENABLED
                )
            }
        )
    }

    private fun update(subscription: SubscriptionEntity): FeedUpdateInfo {
        val stream = StreamInfoItem(
            subscription.serviceId,
            streamUrl(subscription.uid),
            "stream-${subscription.uid}",
            StreamType.VIDEO_STREAM
        ).apply {
            uploaderName = "uploader-${subscription.uid}"
            uploaderUrl = subscription.url
            duration = 60
            uploadDate = DateWrapper(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
        }
        return FeedUpdateInfo(
            uid = subscription.uid,
            notificationMode = subscription.notificationMode,
            name = "updated-${subscription.uid}",
            avatarUrl = null,
            url = subscription.url!!,
            serviceId = subscription.serviceId,
            description = "new description",
            subscriberCount = 42L,
            streams = listOf(stream),
            errors = emptyList()
        )
    }

    private fun rowCount(table: String): Int {
        return database.query("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun updatedSubscriptionCount(): Int {
        return database.query(
            "SELECT COUNT(*) FROM feed_last_updated WHERE last_updated IS NOT NULL",
            emptyArray()
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun streamUrl(subscriptionId: Long) = "https://example.com/watch/$subscriptionId"

    private fun setField(service: FeedLoadService, name: String, value: Any) {
        FeedLoadService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(service, value)
    }
}
