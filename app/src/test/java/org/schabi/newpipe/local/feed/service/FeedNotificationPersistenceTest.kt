package org.schabi.newpipe.local.feed.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Notification
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException
import java.lang.reflect.Modifier
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.feed.notifications.NotificationHelper
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.KEY_SERVICE_ID
import org.schabi.newpipe.util.KEY_TITLE
import org.schabi.newpipe.util.KEY_URL

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FeedNotificationPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var context: Context
    private lateinit var storage: FeedLoadManager.AndroidOperations
    private val events = mutableListOf<FeedEventManager.Event>()
    private var fetches = 0
    private var stores = 0

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        storage = FeedLoadManager.AndroidOperations(
            context,
            context.getSharedPreferences("feed-notification-test", Context.MODE_PRIVATE),
            SubscriptionManager(database),
            FeedDatabaseManager(database)
        )
    }

    @After
    fun cleanup() = database.close()

    @Test
    fun `real store preserves complete payload but results contain only all new notification inputs`() {
        val old = (1..100).map { stream("old-$it") }
        database.streamDAO().upsertAll(old.map { StreamEntity(it) })
        val fresh = (1..103).map { stream("new-$it") }
        val partialError = IOException("partial extraction")
        val first = payload("mixed", old + fresh, listOf(partialError))
        val second = payload("old-only", old)
        val refresh = refresh(listOf(first, second))

        val result = refresh.result.test().assertComplete().assertNoErrors().values().single()
        val summary = result.single()

        assertEquals(first.name, summary.name)
        assertEquals(first.avatarUrl, summary.avatarUrl)
        assertEquals(first.url, summary.url)
        assertEquals(first.url.hashCode(), summary.pseudoId)
        assertEquals(first.serviceId, summary.serviceId)
        assertEquals(fresh, summary.newStreams)
        fresh.zip(summary.newStreams).forEach { (expected, actual) -> assertSame(expected, actual) }
        assertEquals(203, first.streams.size)
        assertEquals(100, second.streams.size)
        assertEquals(203, database.streamDAO().getAll().blockingFirst().size)
        assertEquals(303, count("feed"))
        assertEquals("updated description", database.subscriptionDAO().getSubscription(first.uid).description)
        val errors = (events.last() as FeedEventManager.Event.SuccessResultEvent).itemsErrors
        assertEquals(1, errors.size)
        assertSame(partialError, errors.single().cause)
        database.openHelper.readableDatabase.query("SELECT last_updated FROM feed_last_updated WHERE subscription_id = ${first.uid}").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
        }
        // Structural ownership evidence, not a heap/GC measurement: the DTO cannot retain a full payload.
        assertEquals(
            setOf("name", "avatarUrl", "url", "serviceId", "newStreams"),
            FeedNotificationInfo::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        )
        refresh.result.test().assertValue(result).assertComplete()
        assertEquals(2, fetches)
        assertEquals(1, stores)
    }

    @Test
    fun `real failed store rolls back and publishes no notification summaries`() {
        val info = payload("failed", listOf(stream("rejected")))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_feed BEFORE INSERT ON streams BEGIN SELECT RAISE(ABORT, 'fixture failure'); END"
        )
        val refresh = refresh(listOf(info))
        refresh.result.test().assertError { true }.assertNoValues()
        refresh.result.test().assertError { true }.assertNoValues()
        assertEquals(0, count("feed"))
        assertEquals(0, count("streams"))
        assertEquals("original", database.subscriptionDAO().getSubscription(info.uid).name)
        assertFalse(events.any { it is FeedEventManager.Event.SuccessResultEvent })
        assertEquals(1, events.filterIsInstance<FeedEventManager.Event.ErrorResultEvent>().size)
        assertEquals(1, fetches)
        assertEquals(1, stores)
    }

    @Test
    fun `notification helper retains every stream title group identity and navigation input`() {
        val streams = (1..103).map { stream("notice-$it") }
        val data = FeedNotificationInfo("Channel title", null, "https://example.test/channel", 0, streams)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(context.getString(R.string.streams_notification_channel_id), "Streams", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // Null avatar uses a local drawable only. No image or service network requests.
        NotificationHelper(context).displayNewStreamsNotifications(data)
        assertTrue(manager.activeNotifications.all { it.tag != null })
        val notifications = manager.activeNotifications.associate { it.id to it.notification }
        assertEquals(104, notifications.size)
        val summary = notifications.getValue(data.pseudoId)
        assertEquals(data.name, summary.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals(103, summary.number)
        assertEquals(data.url, summary.group)
        assertEquals(streams.map { it.name }, summary.extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)!!.map { it.toString() })
        assertEquals(data.url, shadowOf(summary.contentIntent).savedIntent.getStringExtra(KEY_URL))
        assertEquals(data.serviceId, shadowOf(summary.contentIntent).savedIntent.getIntExtra(KEY_SERVICE_ID, -1))
        streams.forEach { stream ->
            val notice = notifications.getValue(stream.url.hashCode())
            assertEquals(stream.name, notice.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            assertEquals(stream.uploaderName, notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            assertEquals(stream.uploaderUrl, notice.group)
            val intent = shadowOf(notice.contentIntent).savedIntent
            assertEquals(stream.url, intent.getStringExtra(KEY_URL))
            assertEquals(stream.name, intent.getStringExtra(KEY_TITLE))
            assertEquals(data.serviceId, intent.getIntExtra(KEY_SERVICE_ID, -1))
        }
    }

    @Test
    fun `new stream notification tags preserve foreground slots with colliding numeric IDs`() {
        val streams = listOf(stream("notice"))
        val data = FeedNotificationInfo("Channel", null, "https://example.test/channel", 0, streams)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = context.getString(R.string.streams_notification_channel_id)
        manager.createNotificationChannel(NotificationChannel(channel, "Streams", NotificationManager.IMPORTANCE_DEFAULT))
        val foreground = android.app.Notification.Builder(context, channel).setContentTitle("Foreground").build()
        manager.notify(data.pseudoId, foreground)
        manager.notify(streams.single().url.hashCode(), foreground)
        val helper = NotificationHelper(context)
        helper.displayNewStreamsNotifications(data)
        helper.displayNewStreamsNotifications(data)
        assertEquals(4, manager.activeNotifications.size)
        assertEquals(2, manager.activeNotifications.count { it.tag == null })
        assertEquals(2, manager.activeNotifications.count { it.tag != null })
        manager.cancel(data.pseudoId)
        manager.cancel(streams.single().url.hashCode())
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.all { it.tag != null })
    }

    @Test
    fun `trim after interrupted session reloads idle feed without a terminal UI event`() {
        val feed = FeedDatabaseManager(database)
        val stale = stream("stale").apply { uploadDate = DateWrapper(OffsetDateTime.now().minusYears(1)) }
        val recent = stream("reference")
        val info = payload("stale-channel", listOf(stale, recent))
        feed.upsertAll(info.uid, listOf(stale, recent), OffsetDateTime.now().minusYears(2))
        val group = FeedGroupEntity.GROUP_ALL_ID
        val beforeTimestamp = feed.oldestSubscriptionUpdate(group).blockingFirst()
        val store = FeedEventManager.EventStore()
        val session = store.begin(group)
        session.close()
        val trimmed = CountDownLatch(1)
        val displayed = Flowable.combineLatest(
            store.events(group),
            feed.contentChanges()
        ) { envelope, _ -> envelope }
            .filter { store.isCurrent(it) && it.event is FeedEventManager.Event.IdleEvent }
            .map { feed.getStreams(group, true, true, true).blockingGet()!! }
            .doAfterNext { if (it.size == 1 && it.single().stream.url == recent.url) trimmed.countDown() }
            .test()
        try {
            displayed.awaitCount(1).assertNoErrors()
            assertEquals(2, displayed.values().last().size)
            // Neither a canceled producer nor a silent worker needs to publish success
            // to refresh the data: trim invalidates feed/streams, not feed_last_updated.
            feed.removeOrphansOrOlderStreams()
            assertTrue("trimmed feed was not reloaded", trimmed.await(10L, TimeUnit.SECONDS))
            displayed.assertNoErrors()
            assertEquals(listOf(recent.url), displayed.values().last().map { it.stream.url })
            assertEquals(beforeTimestamp, feed.oldestSubscriptionUpdate(group).blockingFirst())
            session.post(FeedEventManager.Event.SuccessResultEvent())
            store.events(group).test().assertValue { it.event is FeedEventManager.Event.IdleEvent }
        } finally {
            displayed.cancel()
        }
    }

    private fun payload(name: String, streams: List<StreamInfoItem>, errors: List<Throwable> = emptyList()): FeedUpdateInfo {
        val uid = database.subscriptionDAO().insert(SubscriptionEntity(serviceId = 0, url = "https://example.test/$name", name = "original"))
        return FeedUpdateInfo(uid, 1, name, "avatar $name", "https://example.test/$name", 0, "updated description", 42, streams, errors)
    }

    private fun stream(id: String) = StreamInfoItem(0, "https://example.test/stream/$id", "title $id", StreamType.VIDEO_STREAM).apply {
        uploaderName = "Uploader"
        uploaderUrl = "https://example.test/channel"
        uploadDate = DateWrapper(OffsetDateTime.now().minusDays(1))
    }

    private fun count(table: String) = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        check(it.moveToFirst())
        it.getInt(0)
    }

    private fun refresh(payloads: List<FeedUpdateInfo>): FeedRefresh {
        val operations = object : FeedRefresh.Operations {
            override fun subscriptions(groupId: Long, outdatedThreshold: OffsetDateTime) = Flowable.just(payloads.map { database.subscriptionDAO().getSubscription(it.uid) })

            override fun fetch(subscriptionEntity: SubscriptionEntity, useFeedExtractor: Boolean): FeedUpdateInfo {
                fetches++
                return payloads.single { it.uid == subscriptionEntity.uid }
            }

            override fun storeBatch(list: List<Notification<FeedUpdateInfo>>): List<Throwable> {
                stores++
                return storage.storeBatch(list)
            }

            override fun trim() = storage.trim()
        }
        return FeedRefresh(7, OffsetDateTime.now(), true, operations, "processing", Schedulers.trampoline(), Schedulers.trampoline()) { events.add(it) }
    }
}
