package org.schabi.newpipe.local.subscription

import android.app.Application
import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionImportPreferenceTest {
    private lateinit var database: AppDatabase
    private lateinit var manager: SubscriptionManager

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        manager = SubscriptionManager(database)
    }

    @After
    fun cleanup() = database.close()

    @Test
    fun `reimport updates metadata and feed while retaining preference identity and group`() {
        val original = SubscriptionEntity(serviceId = 0, url = url(1), name = "old", notificationMode = NotificationMode.ENABLED)
        val uid = database.subscriptionDAO().insert(original)
        val group = database.feedGroupDAO().insert(FeedGroupEntity(0, "group", FeedGroupIcon.ALL))
        database.feedGroupDAO().updateSubscriptionsForGroup(group, listOf(uid))
        repeat(2) { pass ->
            manager.upsertAll(listOf(channel(1, "updated $pass"), channel(2)))
            val stored = database.subscriptionDAO().getSubscription(uid)
            assertEquals(NotificationMode.ENABLED, stored.notificationMode)
            assertEquals("updated $pass", stored.name)
            assertEquals("description", stored.description)
            assertEquals("https://example.test/avatar/1", stored.avatarUrl)
            assertEquals(42L, stored.subscriberCount)
            assertEquals(NotificationMode.DISABLED, database.subscriptionDAO().getSubscription(0, url(2)).blockingGet()!!.notificationMode)
            assertEquals(listOf(uid), database.feedGroupDAO().getSubscriptionIdsFor(group).blockingFirst())
            assertEquals(2L, database.subscriptionDAO().rowCount().blockingFirst())
            database.openHelper.readableDatabase.query("SELECT subscription_id FROM feed ORDER BY subscription_id").use {
                assertEquals(2, it.count)
                assertTrue(it.moveToFirst())
                assertEquals(uid, it.getLong(0))
            }
        }
        manager.updateNotificationMode(0, url(1), NotificationMode.DISABLED).blockingAwait()
        assertEquals(NotificationMode.DISABLED, database.subscriptionDAO().getSubscription(uid).notificationMode)
        val incoming = SubscriptionEntity(serviceId = 0, url = url(1), name = "again")
        database.subscriptionDAO().upsertAll(listOf(incoming))
        assertEquals(uid, incoming.uid)
    }

    @Test
    fun `later failed batch rolls back metadata preference and feed but keeps first fifty`() {
        val uid = database.subscriptionDAO().insert(
            SubscriptionEntity(serviceId = 0, url = url(1), name = "original", notificationMode = NotificationMode.ENABLED)
        )
        manager.upsertAll((1..50).map { channel(it) })
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_import BEFORE INSERT ON streams WHEN NEW.url = 'https://example.test/stream/51' " +
                "BEGIN SELECT RAISE(ABORT, 'fixture failure'); END"
        )
        val failure = runCatching { manager.upsertAll(listOf(channel(1, "must roll back"), channel(51))) }
        assertTrue(failure.isFailure)
        assertEquals(50L, database.subscriptionDAO().rowCount().blockingFirst())
        assertEquals("channel 1", database.subscriptionDAO().getSubscription(uid).name)
        assertEquals(NotificationMode.ENABLED, database.subscriptionDAO().getSubscription(uid).notificationMode)
        assertEquals(50, database.streamDAO().getAll().blockingFirst().size)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM feed").use {
            assertTrue(it.moveToFirst())
            assertEquals(50, it.getInt(0))
        }
    }

    private fun url(id: Int) = "https://example.test/channel/$id"

    private fun channel(id: Int, name: String = "channel $id"): Pair<ChannelInfo, ChannelTabInfo> {
        val info = ChannelInfo(0, url(id), url(id), "$id", name).apply {
            description = "description"
            subscriberCount = 42
            avatars = listOf(Image("https://example.test/avatar/$id", 100, 100, Image.ResolutionLevel.MEDIUM))
        }
        val tab = ChannelTabInfo(0, ListLinkHandler(url(id), url(id), "$id", listOf(ChannelTabs.VIDEOS), emptyList())).apply {
            relatedItems = listOf(
                StreamInfoItem(0, "https://example.test/stream/$id", "stream $id", StreamType.LIVE_STREAM).apply {
                    uploaderName = name
                }
            )
        }
        return info to tab
    }
}
