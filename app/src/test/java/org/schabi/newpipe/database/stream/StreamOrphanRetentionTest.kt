/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.database.stream

import android.app.Application
import android.util.Pair
import androidx.room.Room
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.LocalItem.LocalItemType
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.bookmark.BookmarkSaveManager
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.playlist.LocalPlaylistManager

/** Generated Room queries with native host SQLite; no downloader or persistence double. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class StreamOrphanRetentionTest {
    private lateinit var database: AppDatabase
    private lateinit var playlists: LocalPlaylistManager
    private lateinit var feeds: FeedDatabaseManager
    private val cutoff = OffsetDateTime.parse("2025-01-01T00:00:00Z")

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        playlists = LocalPlaylistManager(database, Schedulers.trampoline())
        feeds = FeedDatabaseManager(database)
    }

    @After
    fun cleanup() = database.close()

    @Test
    fun permanentThumbnailSurvivesRemovingItsStreamFromContents() {
        stream(1)
        stream(2)
        val playlist = playlist(1, permanent = false)
        database.playlistStreamDAO().insertAll(listOf(PlaylistStreamEntity(playlist, 1, 0), PlaylistStreamEntity(playlist, 2, 1)))
        assertEquals(1, playlists.changePlaylistThumbnail(playlist, 1, true).blockingGet())
        playlists.updateJoin(playlist, listOf(2)).blockingAwait()

        assertEquals(listOf(2L), database.playlistStreamDAO().getOrderedStreamsOfSync(playlist).map { it.streamId })
        assertEquals(0, database.streamDAO().deleteOrphans())
        assertEquals(setOf(1L, 2L), streamIds())
        assertEquals(thumbnail(1), metadata(playlist).thumbnailUrl)
        assertTrue(metadata(playlist).isThumbnailPermanent!!)
    }

    @Test
    fun feedClearRetainsThumbnailHistoryAndContentsButDeletesGenuineOrphans() {
        (1L..5L).forEach { stream(it) }
        val cover = playlist(1)
        val contents = playlist(PlaylistEntity.DEFAULT_THUMBNAIL_ID)
        database.playlistStreamDAO().insert(PlaylistStreamEntity(contents, 3, 0))
        database.streamHistoryDAO().insert(StreamHistoryEntity(4, cutoff, 1))
        feed(1, 2)

        feeds.clear()

        assertEquals(setOf(1L, 3L, 4L), streamIds())
        assertEquals(0, feedCount())
        assertEquals(thumbnail(1), metadata(cover).thumbnailUrl)
    }

    @Test
    fun agePruningKeepsOldThumbnailAfterItsFeedReferenceIsRemoved() {
        stream(1, cutoff.minusDays(2))
        stream(2, cutoff.minusDays(1))
        stream(3, cutoff.plusDays(1))
        val playlist = playlist(1)
        feed(1, 2, 3)

        feeds.removeOrphansOrOlderStreams(cutoff)

        assertEquals(setOf(1L, 3L), streamIds())
        assertEquals(1, feedCount())
        assertEquals(thumbnail(1), metadata(playlist).thumbnailUrl)
    }

    @Test
    fun unsettingAnEmptyPlaylistsCoverMakesTheOldStreamCollectible() {
        stream(1)
        val playlist = playlist(1)
        unset(playlist)

        assertEquals(PlaylistEntity.DEFAULT_THUMBNAIL_ID, metadata(playlist).thumbnailStreamId)
        assertEquals(1, database.streamDAO().deleteOrphans())
        assertTrue(streamIds().isEmpty())
        assertNull(metadata(playlist).thumbnailUrl)
    }

    @Test
    fun unsettingWithContentsRetainsTheAutomaticStreamAndReleasesTheOldCover() {
        stream(1)
        stream(2)
        val playlist = playlist(1)
        database.playlistStreamDAO().insert(PlaylistStreamEntity(playlist, 2, 0))

        unset(playlist)

        assertFalse(metadata(playlist).isThumbnailPermanent!!)
        assertEquals(2L, metadata(playlist).thumbnailStreamId)
        assertEquals(1, database.streamDAO().deleteOrphans())
        assertEquals(setOf(2L), streamIds())
        assertEquals(thumbnail(2), metadata(playlist).thumbnailUrl)
    }

    @Test
    fun sharedCoverIsCollectedOnlyAfterItsLastPlaylistIsDeleted() {
        stream(1)
        val first = playlist(1)
        val second = playlist(1)
        deletePlaylist(first)
        assertEquals(0, database.streamDAO().deleteOrphans())
        assertEquals(thumbnail(1), metadata(second).thumbnailUrl)

        deletePlaylist(second)
        assertEquals(1, database.streamDAO().deleteOrphans())
        assertTrue(streamIds().isEmpty())
    }

    @Test
    fun automaticAndPermanentPointersBothRetainTheirReferencedRows() {
        stream(1)
        stream(2)
        val automatic = playlist(1, permanent = false)
        val permanent = playlist(2)

        assertEquals(0, database.streamDAO().deleteOrphans())
        assertEquals(thumbnail(1), metadata(automatic).thumbnailUrl)
        assertEquals(thumbnail(2), metadata(permanent).thumbnailUrl)
    }

    @Test
    fun changingACoverReleasesOnlyUnreferencedImages() {
        stream(1)
        stream(2)
        val first = playlist(1)
        val second = playlist(1)
        assertEquals(1, playlists.changePlaylistThumbnail(first, 2, true).blockingGet())
        assertEquals(0, database.streamDAO().deleteOrphans())
        unset(second)
        assertEquals(1, database.streamDAO().deleteOrphans())
        assertEquals(setOf(2L), streamIds())
        assertEquals(thumbnail(2), metadata(first).thumbnailUrl)
        unset(first)
        assertEquals(1, database.streamDAO().deleteOrphans())
        assertTrue(streamIds().isEmpty())
    }

    @Test
    fun playlistNumericIdAndDefaultThumbnailDoNotRetainAnUnrelatedStream() {
        stream(1)
        val playlist = playlist(PlaylistEntity.DEFAULT_THUMBNAIL_ID)
        assertEquals(1L, playlist)

        assertEquals(1, database.streamDAO().deleteOrphans())
        assertTrue(streamIds().isEmpty())
        assertNotNull(database.playlistDAO().getPlaylistSync(playlist))
    }

    @Test
    fun failedOrphanDeletionRollsBackFeedClearingWithoutLosingTheCover() {
        stream(1)
        stream(2)
        val playlist = playlist(1)
        feed(1, 2)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_orphan BEFORE DELETE ON streams WHEN OLD.uid = 2 " +
                "BEGIN SELECT RAISE(ABORT, 'controlled orphan failure'); END"
        )
        val failure = assertThrows(RuntimeException::class.java) { feeds.clear() }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("controlled orphan failure") == true })
        assertEquals(2, feedCount())
        assertEquals(setOf(1L, 2L), streamIds())
        assertEquals(thumbnail(1), metadata(playlist).thumbnailUrl)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_orphan")
        feeds.clear()
        assertEquals(0, feedCount())
        assertEquals(setOf(1L), streamIds())
    }

    private fun stream(id: Long, date: OffsetDateTime = cutoff): Long = database.streamDAO().insert(
        StreamEntity(
            uid = id, serviceId = 0, url = "https://example.invalid/stream/$id", title = "stream-$id",
            streamType = StreamType.VIDEO_STREAM, duration = 120, uploader = "fixture",
            thumbnailUrl = thumbnail(id), uploadDate = date
        )
    )

    private fun thumbnail(id: Long) = "file:///fixture/thumbnail-$id.png"
    private fun streamIds() = database.streamDAO().getAll().blockingFirst().map { it.uid }.toSet()
    private fun playlist(thumbnail: Long, permanent: Boolean = true) = database.playlistDAO()
        .insert(PlaylistEntity(name = "playlist", isThumbnailPermanent = permanent, thumbnailStreamId = thumbnail, displayIndex = 0))
    private fun metadata(id: Long) = database.playlistStreamDAO().getPlaylistMetadata().blockingFirst().single { it.uid == id }

    private fun unset(id: Long) {
        val updated = playlists.getAutomaticPlaylistThumbnailStreamId(id)
            .flatMapMaybe { playlists.changePlaylistThumbnail(id, it, false) }.blockingGet()
        assertEquals(1, updated)
    }

    private fun deletePlaylist(id: Long) = BookmarkSaveManager(database)
        .save(emptyList(), listOf(Pair(id, LocalItemType.PLAYLIST_LOCAL_ITEM))).blockingAwait()

    private fun feed(vararg streams: Long) {
        val subscription = database.subscriptionDAO().insert(SubscriptionEntity(serviceId = 0, url = "https://example.invalid/channel", name = "fixture"))
        database.feedDAO().insertAll(streams.map { FeedEntity(it, subscription) })
    }

    private fun feedCount(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM feed").use {
        check(it.moveToFirst())
        it.getInt(0)
    }
}
