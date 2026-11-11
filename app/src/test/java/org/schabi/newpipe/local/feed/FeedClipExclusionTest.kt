/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed

import android.app.Application
import android.content.Context
import androidx.room.Room
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.search.filter.FilterItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.util.ChannelTabHelper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class FeedClipExclusionTest {
    private lateinit var database: AppDatabase
    private lateinit var manager: FeedDatabaseManager
    private val date = OffsetDateTime.parse("2026-11-01T12:00:00Z")
    private val kick = ServiceList.Kick.serviceId

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        manager = FeedDatabaseManager(database)
    }

    @After
    fun cleanup() = database.close()

    @Test
    fun canonicalAndWebsiteKickClipsAreNotInsertedIntoFeedStorage() {
        val clips = listOf(
            "https://kick.com/channel/clips/clip_ABC",
            "https://kick.com/channel?clip=clip_DEF",
            "https://www.kick.com/channel?utm_source=share&clip=clip_GHI",
            "http://kick.com/channel?%63lip=clip_JKL"
        ).map { item(kick, it) }
        store(kick, clips)

        assertTrue(database.streamDAO().getAll().blockingFirst().isEmpty())
        assertEquals(0, feedCount())
    }

    @Test
    fun liveStreamsRecordingsAndOtherServiceItemsArePreserved() {
        val live = item(kick, "https://kick.com/channel?live=1770000000", StreamType.LIVE_STREAM).apply { uploadDate = null }
        val vod = item(kick, "https://kick.com/channel/videos/12345678-1234-1234-1234-123456789abc")
        val ordinary = item(kick, "https://kick.com/channel?utm_source=clips")
        val clip = item(kick, "https://kick.com/channel/clips/clip_ABC")
        store(kick, listOf(live, vod, ordinary, clip))
        val other = item(ServiceList.YouTube.serviceId, "https://example.invalid/channel/clips/clip_ABC")
        store(ServiceList.YouTube.serviceId, listOf(other))

        assertEquals(setOf(live.url, vod.url, ordinary.url, other.url), database.streamDAO().getAll().blockingFirst().map { it.url }.toSet())
        assertEquals(4, feedCount())
    }

    @Test
    fun unrecognizedKickUrlsAreNotDiscardedBySubstringMatching() {
        val urls = listOf(
            "https://example.invalid/channel/clips/clip_ABC",
            "https://kick.com/channel?utm_source=clip_ABC",
            "https://kick.com/channel?reference=clip%3Dclip_ABC"
        )
        store(kick, urls.map { item(kick, it) })

        assertEquals(urls.toSet(), database.streamDAO().getAll().blockingFirst().map { it.url }.toSet())
    }

    @Test
    fun kickClipTabIsExcludedEvenWhenOldPreferenceEnablesIt() {
        val context = mock(Context::class.java)
        for (key in listOf(
            R.string.feed_fetch_channel_tabs_key,
            R.string.fetch_channel_tabs_clips,
            R.string.fetch_channel_tabs_videos,
            R.string.fetch_channel_tabs_livestreams,
            R.string.show_channel_tabs_key
        )) {
            `when`(context.getString(key)).thenReturn("setting-$key")
        }
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("feed-clips", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        assertFalse(ChannelTabHelper.fetchFeedChannelTab(context, preferences, tab(ChannelTabs.CLIPS), kick))
        assertTrue(ChannelTabHelper.fetchFeedChannelTab(context, preferences, tab(ChannelTabs.VIDEOS), kick))
        assertTrue(ChannelTabHelper.fetchFeedChannelTab(context, preferences, tab(ChannelTabs.LIVESTREAMS), kick))
        preferences.edit().putStringSet(
            context.getString(R.string.feed_fetch_channel_tabs_key),
            setOf(context.getString(R.string.fetch_channel_tabs_clips))
        ).commit()

        assertFalse(ChannelTabHelper.fetchFeedChannelTab(context, preferences, tab(ChannelTabs.CLIPS), kick))
        assertTrue(ChannelTabHelper.fetchFeedChannelTab(context, preferences, tab(ChannelTabs.CLIPS), ServiceList.YouTube.serviceId))
        assertFalse(ChannelTabHelper.fetchFeedChannelTab(context, preferences, tab(ChannelTabs.VIDEOS), kick))
        assertTrue(ChannelTabHelper.showChannelTab(context, preferences, ChannelTabs.CLIPS))
    }

    private fun store(service: Int, items: List<StreamInfoItem>) {
        val subscription = database.subscriptionDAO().insert(
            SubscriptionEntity(serviceId = service, url = "https://example.invalid/channel/$service", name = "fixture")
        )
        manager.upsertAll(subscription, items, date.minusDays(1))
    }

    private fun item(service: Int, url: String, type: StreamType = StreamType.VIDEO_STREAM) = StreamInfoItem(service, url, "fixture", type).apply {
        uploaderName = "fixture"
        uploadDate = DateWrapper(date)
    }

    private fun tab(filter: FilterItem) = ListLinkHandler("https://kick.com/channel", "https://kick.com/channel", "channel", listOf(filter), emptyList())

    private fun feedCount(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM feed").use {
        check(it.moveToFirst())
        it.getInt(0)
    }
}
