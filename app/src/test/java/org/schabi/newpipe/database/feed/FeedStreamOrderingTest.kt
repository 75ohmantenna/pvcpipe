/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.database.feed

import android.app.Application
import androidx.room.Room
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.subscription.FeedGroupIcon

/** Exercise the generated feed query, including sorting before its result limit. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class FeedStreamOrderingTest {
    private lateinit var database: AppDatabase
    private var subscription = 0L
    private val date = OffsetDateTime.parse("2026-11-01T12:00:00Z")

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        subscription = database.subscriptionDAO().insert(
            SubscriptionEntity(serviceId = 0, url = "https://example.invalid/channel", name = "fixture")
        )
    }

    @After
    fun cleanup() = database.close()

    @Test
    fun ongoingVideoAndAudioLeadUndatedUploadsAndEndedStreams() {
        stream(1, StreamType.VIDEO_STREAM, null)
        stream(2, StreamType.VIDEO_STREAM, date)
        stream(3, StreamType.POST_LIVE_STREAM, date.plusDays(1))
        stream(4, StreamType.POST_LIVE_AUDIO_STREAM, date.plusDays(2))
        stream(5, StreamType.LIVE_STREAM, date.minusDays(2))
        stream(6, StreamType.AUDIO_LIVE_STREAM, date.minusDays(1))

        assertEquals(listOf(6L, 5L, 1L, 4L, 3L, 2L), streams())
    }

    @Test
    fun bothCategoriesRetainUndatedThenNewestThenUploaderOrdering() {
        stream(1, StreamType.LIVE_STREAM, null, "Zulu")
        stream(2, StreamType.AUDIO_LIVE_STREAM, null, "Alpha")
        stream(3, StreamType.LIVE_STREAM, date, "Zulu")
        stream(4, StreamType.AUDIO_LIVE_STREAM, date, "Alpha")
        stream(5, StreamType.LIVE_STREAM, date.minusDays(1))
        stream(6, StreamType.VIDEO_STREAM, null, "Zulu")
        stream(7, StreamType.AUDIO_STREAM, null, "Alpha")
        stream(8, StreamType.VIDEO_STREAM, date, "Zulu")
        stream(9, StreamType.AUDIO_STREAM, date, "Alpha")
        stream(10, StreamType.VIDEO_STREAM, date.minusDays(1))

        assertEquals(listOf(2L, 1L, 4L, 3L, 5L, 7L, 6L, 9L, 8L, 10L), streams())
    }

    @Test
    fun liveStreamsArePrioritizedBeforeTheFiveHundredItemLimit() {
        (1L..501L).forEach { stream(it, StreamType.VIDEO_STREAM, date.plusSeconds(it)) }
        stream(502, StreamType.LIVE_STREAM, date.minusDays(2))
        stream(503, StreamType.AUDIO_LIVE_STREAM, date.minusDays(1))

        val result = streams()
        assertEquals(500, result.size)
        assertEquals(listOf(503L, 502L) + (501L downTo 4L).toList(), result)
    }

    @Test
    fun repeatHistoryDoesNotDuplicateLiveStreamsOrConsumeTheResultLimit() {
        stream(1, StreamType.LIVE_STREAM, date.minusDays(2))
        stream(2, StreamType.AUDIO_LIVE_STREAM, date.minusDays(3))
        (3L..502L).forEach { stream(it, StreamType.VIDEO_STREAM, date.plusSeconds(it)) }
        database.streamHistoryDAO().insertAll(
            (1L..501L).map { StreamHistoryEntity(1, date.minusMinutes(it), 1) }
        )

        assertEquals(listOf(1L, 2L) + (502L downTo 5L).toList(), streams())
    }

    @Test
    fun sharedStreamAppearsOnceAcrossSubscriptionsAndGroupMemberships() {
        stream(1, StreamType.LIVE_STREAM, date.minusDays(1))
        stream(2, StreamType.VIDEO_STREAM)
        val otherSubscription = database.subscriptionDAO().insert(
            SubscriptionEntity(serviceId = 0, url = "https://example.invalid/other", name = "other")
        )
        database.feedDAO().insert(FeedEntity(1, otherSubscription))
        val group = database.feedGroupDAO().insert(FeedGroupEntity(0, "fixture", FeedGroupIcon.ALL))
        database.feedGroupDAO().insertSubscriptionsToGroup(
            listOf(FeedGroupSubscriptionEntity(group, subscription), FeedGroupSubscriptionEntity(group, otherSubscription))
        )
        database.streamHistoryDAO().insertAll(
            listOf(StreamHistoryEntity(1, date.minusDays(1), 1), StreamHistoryEntity(1, date, 1))
        )

        assertEquals(listOf(1L, 2L), streams())
        assertEquals(listOf(1L, 2L), streams(groupId = group))
    }

    @Test
    fun deduplicationPreservesProgressAndPlaybackFilters() {
        stream(1, StreamType.LIVE_STREAM, date.minusDays(2), duration = -1)
        stream(2, StreamType.VIDEO_STREAM, date.minusDays(1))
        stream(3, StreamType.VIDEO_STREAM)
        for (id in 1L..3L) {
            database.streamHistoryDAO().insertAll(
                listOf(StreamHistoryEntity(id, date.minusDays(1), 1), StreamHistoryEntity(id, date, 1))
            )
        }
        database.streamStateDAO().insertAll(
            listOf(StreamStateEntity(1, 10_000), StreamStateEntity(2, 30_000), StreamStateEntity(3, 120_000))
        )

        assertEquals(listOf(1L, 2L), streams(includePlayed = false))
        assertEquals(listOf(1L, 3L), streams(includePartiallyPlayed = false))
        assertEquals(listOf(1L), streams(includePlayed = false, includePartiallyPlayed = false))
        val result = database.feedDAO().getStreams(FeedGroupEntity.GROUP_ALL_ID, true, true, null).blockingGet()!!
        assertEquals(listOf(10_000L, 120_000L, 30_000L), result.map { it.stateProgressMillis })
    }

    @Test
    fun groupFilterStillExcludesOtherSubscriptions() {
        stream(1, StreamType.VIDEO_STREAM)
        stream(2, StreamType.LIVE_STREAM, date.minusDays(1))
        val otherSubscription = database.subscriptionDAO().insert(
            SubscriptionEntity(serviceId = 0, url = "https://example.invalid/other", name = "other")
        )
        stream(3, StreamType.AUDIO_LIVE_STREAM, subscriptionId = otherSubscription)
        val group = database.feedGroupDAO().insert(FeedGroupEntity(0, "fixture", FeedGroupIcon.ALL))
        database.feedGroupDAO().insertSubscriptionsToGroup(listOf(FeedGroupSubscriptionEntity(group, subscription)))

        assertEquals(listOf(2L, 1L), streams(groupId = group))
        assertEquals(listOf(3L, 2L, 1L), streams())
    }

    @Test
    fun dateAndPlaybackFiltersRemainInEffect() {
        stream(1, StreamType.LIVE_STREAM, date.minusDays(1))
        stream(2, StreamType.AUDIO_LIVE_STREAM, null)
        stream(3, StreamType.VIDEO_STREAM, date.minusDays(1))
        stream(4, StreamType.VIDEO_STREAM, date.minusDays(1))
        stream(5, StreamType.VIDEO_STREAM, date.plusDays(1))
        stream(6, StreamType.LIVE_STREAM, date.plusDays(1))
        database.streamHistoryDAO().insert(StreamHistoryEntity(3, date, 1))
        database.streamStateDAO().insert(StreamStateEntity(3, 120_000))
        database.streamHistoryDAO().insert(StreamHistoryEntity(4, date, 1))
        database.streamStateDAO().insert(StreamStateEntity(4, 30_000))

        assertEquals(listOf(2L, 1L), streams(includePlayed = false, includePartiallyPlayed = false, cutoff = date))
        assertEquals(listOf(2L, 1L, 4L), streams(includePlayed = false, cutoff = date))
        assertFalse(streams(cutoff = date).contains(6L))
    }

    @Test
    fun endedStreamLosesPriorityWhenItsStoredTypeChanges() {
        stream(1, StreamType.LIVE_STREAM, date.minusDays(1))
        stream(2, StreamType.VIDEO_STREAM, date)
        assertEquals(listOf(1L, 2L), streams())

        database.openHelper.writableDatabase.execSQL("UPDATE streams SET stream_type = 'POST_LIVE_STREAM' WHERE uid = 1")

        assertEquals(listOf(2L, 1L), streams())
    }

    @Test
    fun cachedKickClipsAreExcludedBeforeLimitWithoutHidingOtherServicesOrVideos() {
        for (id in 1L..501L) {
            stream(id, StreamType.VIDEO_STREAM, date.plusDays(1), serviceId = 8, url = "https://kick.com/fixture/clips/clip_$id")
        }
        stream(502, StreamType.LIVE_STREAM, date.minusDays(1), serviceId = 8, url = "https://kick.com/fixture?live=123")
        stream(503, StreamType.VIDEO_STREAM, date, serviceId = 8, url = "https://kick.com/fixture/videos/recording")
        stream(504, StreamType.VIDEO_STREAM, date.minusMinutes(1), serviceId = 0, url = "https://kick.com/fixture/clips/clip_other_service")
        stream(505, StreamType.VIDEO_STREAM, date.minusMinutes(2), serviceId = 8, url = "https://kick.com/clipsfan")

        assertEquals(listOf(502L, 503L, 504L, 505L), streams())
    }

    private fun stream(
        id: Long,
        type: StreamType,
        uploaded: OffsetDateTime? = date,
        uploader: String = "fixture",
        subscriptionId: Long = subscription,
        duration: Long = 120,
        serviceId: Int = 0,
        url: String = "https://example.invalid/stream/$id"
    ) {
        database.streamDAO().insert(
            StreamEntity(
                uid = id,
                serviceId = serviceId,
                url = url,
                title = "stream-$id",
                streamType = type,
                duration = duration,
                uploader = uploader,
                uploadDate = uploaded
            )
        )
        database.feedDAO().insert(FeedEntity(id, subscriptionId))
    }

    private fun streams(
        groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        includePlayed: Boolean = true,
        includePartiallyPlayed: Boolean = true,
        cutoff: OffsetDateTime? = null
    ): List<Long> = database.feedDAO().getStreams(groupId, includePlayed, includePartiallyPlayed, cutoff)
        .blockingGet()!!.map { it.stream.uid }
}
