/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.playlist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** Exercises playlist content decisions and persisted effects through the manager interface. */
class LocalPlaylistContentsTest {
    private lateinit var database: AppDatabase
    private lateinit var manager: LocalPlaylistManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = LocalPlaylistManager(database, Schedulers.trampoline())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun appendAndReplaceKeepDuplicateOccurrencesAndRequestedOrder() {
        val streams = insertStreams(3)
        val playlist = playlist(emptyList())
        val append = manager.appendToPlaylist(playlist, listOf(streams[1], streams[0], streams[1], streams[2]))

        append.test().awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()

        assertContents(playlist, listOf(streams[1], streams[0], streams[1], streams[2]))
        assertEquals(streams[1].uid, storedPlaylist(playlist).thumbnailStreamId)
        val requestedOrder = listOf(streams[2].uid, streams[0].uid, streams[2].uid)
        manager.updateJoin(playlist, requestedOrder).test()
            .awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        assertContents(playlist, listOf(streams[2], streams[0], streams[2]))
        assertEquals(streams[2].uid, storedPlaylist(playlist).thumbnailStreamId)
        assertFalse(storedPlaylist(playlist).isThumbnailPermanent)
        assertEquals(3, rowCount("streams"))
    }

    @Test
    fun duplicateRemovalKeepsFirstOccurrencesAndAutomaticThumbnailStillInContents() {
        val streams = insertStreams(3)
        val playlist = playlist(
            listOf(streams[1], streams[0], streams[1], streams[2], streams[0]),
            thumbnail = streams[0].uid
        )

        val result = remove(playlist, LocalPlaylistManager.Removal.DUPLICATES)

        assertEquals(listOf(streams[1].uid, streams[0].uid, streams[2].uid), result.map { it.streamId })
        assertContents(playlist, listOf(streams[1], streams[0], streams[2]))
        assertEquals(streams[0].uid, storedPlaylist(playlist).thumbnailStreamId)
        assertFalse(storedPlaylist(playlist).isThumbnailPermanent)
        assertEquals(3, rowCount("streams"))
    }

    @Test
    fun watchedRemovalRequiresHistoryAndActualStateAndDistinguishesPartialProgress() {
        val streams = insertStreams(6)
        // 0 untouched, 1 history only, 2 completed state only, 3 partial with history,
        // 4 completed with history, 5 a present zero-progress state with history.
        listOf(1, 3, 4, 5).forEach { index -> addHistory(streams[index].uid) }
        addState(streams[2].uid, 120_000L)
        addState(streams[3].uid, 10_000L)
        addState(streams[4].uid, 120_000L)
        addState(streams[5].uid, 0L)
        val completedOnly = playlist(streams, thumbnail = streams[4].uid)
        val includingPartial = playlist(streams, thumbnail = streams[4].uid, permanent = true)

        val completedResult = remove(completedOnly, LocalPlaylistManager.Removal.WATCHED)
        val partialResult = remove(includingPartial, LocalPlaylistManager.Removal.WATCHED_AND_PARTIALLY_WATCHED)

        val completedSurvivors = listOf(streams[0], streams[1], streams[2], streams[3], streams[5])
        val partialSurvivors = listOf(streams[0], streams[1], streams[2])
        assertEquals(completedSurvivors.map { it.uid }, completedResult.map { it.streamId })
        assertEquals(partialSurvivors.map { it.uid }, partialResult.map { it.streamId })
        assertContents(completedOnly, completedSurvivors)
        assertContents(includingPartial, partialSurvivors)
        assertEquals(streams[0].uid, storedPlaylist(completedOnly).thumbnailStreamId)
        assertFalse(storedPlaylist(completedOnly).isThumbnailPermanent)
        assertEquals(streams[4].uid, storedPlaylist(includingPartial).thumbnailStreamId)
        assertTrue(storedPlaylist(includingPartial).isThumbnailPermanent)
        assertEquals(6, rowCount("streams"))
        assertEquals(4, rowCount("stream_history"))
        assertEquals(4, rowCount("stream_state"))
    }

    @Test
    fun removingEveryWatchedEntryResetsAutomaticThumbnailAndKeepsPermanentThumbnail() {
        val stream = insertStreams(1).single()
        addHistory(stream.uid)
        addState(stream.uid, 120_000L)
        val automatic = playlist(listOf(stream), thumbnail = stream.uid)
        val permanent = playlist(listOf(stream), thumbnail = stream.uid, permanent = true)

        assertTrue(remove(automatic, LocalPlaylistManager.Removal.WATCHED).isEmpty())
        assertTrue(remove(permanent, LocalPlaylistManager.Removal.WATCHED).isEmpty())

        assertContents(automatic, emptyList())
        assertContents(permanent, emptyList())
        assertEquals(PlaylistEntity.DEFAULT_THUMBNAIL_ID, storedPlaylist(automatic).thumbnailStreamId)
        assertEquals(stream.uid, storedPlaylist(permanent).thumbnailStreamId)
        assertTrue(storedPlaylist(permanent).isThumbnailPermanent)
        assertEquals(2, rowCount("playlists"))
        assertEquals(1, rowCount("streams"))
        assertEquals(1, rowCount("stream_history"))
        assertEquals(1, rowCount("stream_state"))
    }

    private fun remove(playlistId: Long, removal: LocalPlaylistManager.Removal): List<PlaylistStreamEntry> {
        val observer = manager.removeStreams(playlistId, removal).test()
            .awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        return observer.values().single()
    }

    private fun assertContents(playlistId: Long, expected: List<StreamEntity>) {
        val stored = manager.getPlaylistStreams(playlistId).blockingFirst()
        assertEquals(expected.map { it.uid }, stored.map { it.streamId })
        assertEquals(expected.indices.toList(), stored.map { it.joinIndex })
    }

    private fun storedPlaylist(playlistId: Long): PlaylistEntity = database.playlistDAO()
        .getPlaylist(playlistId).blockingFirst().single()

    private fun playlist(
        streams: List<StreamEntity>,
        thumbnail: Long = streams.firstOrNull()?.uid ?: PlaylistEntity.DEFAULT_THUMBNAIL_ID,
        permanent: Boolean = false
    ): Long {
        val playlistId = database.playlistDAO().insert(
            PlaylistEntity(name = "playlist", isThumbnailPermanent = permanent, thumbnailStreamId = thumbnail, displayIndex = 0)
        )
        database.playlistStreamDAO().insertAll(
            streams.mapIndexed { index, stream -> PlaylistStreamEntity(playlistId, stream.uid, index) }
        )
        return playlistId
    }

    private fun insertStreams(count: Int): List<StreamEntity> = (1..count).map { index ->
        StreamEntity(
            serviceId = 1,
            url = "https://example.com/watch/$index",
            title = "stream-$index",
            streamType = StreamType.VIDEO_STREAM,
            duration = 120L,
            uploader = "uploader",
            uploaderUrl = "https://example.com/channel"
        ).also { stream -> stream.uid = database.streamDAO().insert(stream) }
    }

    private fun addHistory(streamId: Long) {
        database.streamHistoryDAO().insert(StreamHistoryEntity(streamId, OffsetDateTime.now(ZoneOffset.UTC), 1L))
    }

    private fun addState(streamId: Long, progress: Long) {
        database.streamStateDAO().insert(StreamStateEntity(streamId, progress))
    }

    private fun rowCount(table: String): Int = database.query("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }
}
