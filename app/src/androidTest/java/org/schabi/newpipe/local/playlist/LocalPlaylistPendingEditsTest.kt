/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.playlist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** Exercises pending playlist edits through the complete accepted cleanup interface. */
class LocalPlaylistPendingEditsTest {
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
    fun pendingReorderIsFrozenBeforeDuplicateRemovalAndThumbnailSelection() {
        val streams = streams(3)
        val playlist = playlist(streams)
        val pending = mutableListOf(streams[2].uid, streams[1].uid, streams[2].uid)
        val operation = manager.removeStreams(playlist, LocalPlaylistManager.Removal.DUPLICATES, pending)

        pending.clear()
        pending.add(streams[0].uid)
        val result = operation.test().awaitDone(10, TimeUnit.SECONDS)
            .assertComplete().assertNoErrors().values().single()

        assertEquals(listOf(streams[2].uid, streams[1].uid), result.map { it.streamId })
        assertContents(playlist, listOf(streams[2].uid, streams[1].uid))
        assertEquals(streams[2].uid, metadata(playlist).thumbnailStreamId)
    }

    @Test
    fun emptyPendingSnapshotClearsContentsInsteadOfUsingSavedContents() {
        val streams = streams(2)
        val playlist = playlist(streams)

        val result = manager.removeStreams(playlist, LocalPlaylistManager.Removal.DUPLICATES, emptyList()).test()
            .awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors().values().single()

        assertTrue(result.isEmpty())
        assertContents(playlist, emptyList())
        assertEquals(PlaylistEntity.DEFAULT_THUMBNAIL_ID, metadata(playlist).thumbnailStreamId)
    }

    @Test
    fun nullPendingSnapshotUsesSavedContentsAndKeepsItsFirstOccurrences() {
        val streams = streams(2)
        val playlist = playlist(listOf(streams[1], streams[0], streams[1]))

        val result = manager.removeStreams(playlist, LocalPlaylistManager.Removal.DUPLICATES, null).test()
            .awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors().values().single()

        assertEquals(listOf(streams[1].uid, streams[0].uid), result.map { it.streamId })
        assertContents(playlist, listOf(streams[1].uid, streams[0].uid))
        assertEquals(streams[1].uid, metadata(playlist).thumbnailStreamId)
    }

    @Test
    fun invalidPendingStreamReferenceFailsAndLeavesOriginalContentsAndMetadata() {
        val streams = streams(2)
        val playlist = playlist(streams)
        val original = metadata(playlist)

        manager.removeStreams(playlist, LocalPlaylistManager.Removal.DUPLICATES, listOf(streams[0].uid, 999_999L)).test()
            .awaitDone(10, TimeUnit.SECONDS).assertError { true }.assertNoValues()

        assertContents(playlist, streams.map { it.uid })
        assertEquals(original, metadata(playlist))
    }

    private fun assertContents(playlistId: Long, expected: List<Long>) {
        val stored = database.playlistStreamDAO().getOrderedStreamsOf(playlistId).blockingFirst()
        assertEquals(expected, stored.map { it.streamId })
        assertEquals(expected.indices.toList(), stored.map { it.joinIndex })
    }

    private fun metadata(playlistId: Long): PlaylistEntity = database.playlistDAO()
        .getPlaylist(playlistId).blockingFirst().single()

    private fun playlist(streams: List<StreamEntity>): Long {
        val id = database.playlistDAO().insert(
            PlaylistEntity(
                name = "playlist",
                isThumbnailPermanent = false,
                thumbnailStreamId = streams.firstOrNull()?.uid ?: PlaylistEntity.DEFAULT_THUMBNAIL_ID,
                displayIndex = 0
            )
        )
        database.playlistStreamDAO().insertAll(
            streams.mapIndexed { index, stream -> PlaylistStreamEntity(id, stream.uid, index) }
        )
        return id
    }

    private fun streams(count: Int): List<StreamEntity> = (1..count).map { index ->
        StreamEntity(
            serviceId = 1,
            url = "https://example.com/watch/$index",
            title = "stream-$index",
            streamType = StreamType.VIDEO_STREAM,
            duration = 120L,
            uploader = "uploader"
        ).also { it.uid = database.streamDAO().insert(it) }
    }
}
