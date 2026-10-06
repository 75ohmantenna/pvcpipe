/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.playlist

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.util.concurrent.CountDownLatch
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

/** Regressions for accepted playlist mutation ownership, input snapshots, and transactions. */
class LocalPlaylistCorrectionTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun appendPreservesPermanentDefaultThumbnail() {
        val stream = streams(1).single()
        val playlist = playlist(emptyList(), permanent = true)
        val manager = LocalPlaylistManager(database, Schedulers.trampoline())

        manager.appendToPlaylist(playlist, listOf(stream)).test()
            .awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()

        assertContents(playlist, listOf(stream.uid))
        assertEquals(PlaylistEntity.DEFAULT_THUMBNAIL_ID, metadata(playlist).thumbnailStreamId)
        assertTrue(metadata(playlist).isThumbnailPermanent)
    }

    @Test
    fun disposedAcceptedAppendFinishesThumbnailAndReplayDoesNotAppendAgain() {
        val stream = streams(1).single()
        val playlist = playlist(emptyList())
        val scheduler = TestScheduler()
        val manager = LocalPlaylistManager(database, scheduler)
        val operation = manager.appendToPlaylist(playlist, listOf(stream))
        val detached = operation.test()

        detached.dispose()
        scheduler.triggerActions()

        assertContents(playlist, listOf(stream.uid))
        assertEquals(stream.uid, metadata(playlist).thumbnailStreamId)
        operation.test().awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors().assertValueCount(1)
        scheduler.triggerActions()
        assertContents(playlist, listOf(stream.uid))
    }

    @Test
    fun disposedAcceptedReplacementFinishesThumbnailAndReplayKeepsContents() {
        val streams = streams(2)
        val playlist = playlist(streams)
        val scheduler = TestScheduler()
        val manager = LocalPlaylistManager(database, scheduler)
        val operation = manager.updateJoin(playlist, listOf(streams[1].uid))
        val detached = operation.test()

        detached.dispose()
        scheduler.triggerActions()

        assertContents(playlist, listOf(streams[1].uid))
        assertEquals(streams[1].uid, metadata(playlist).thumbnailStreamId)
        operation.test().awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        scheduler.triggerActions()
        assertContents(playlist, listOf(streams[1].uid))
    }

    @Test
    fun disposedAcceptedCleanupStillCommitsAndReplaysItsResult() {
        val streams = streams(2)
        val playlist = playlist(listOf(streams[0], streams[1], streams[0]))
        val scheduler = TestScheduler()
        val manager = LocalPlaylistManager(database, scheduler)
        val operation = manager.removeStreams(playlist, LocalPlaylistManager.Removal.DUPLICATES)
        val detached = operation.test()

        detached.dispose()
        scheduler.triggerActions()

        assertContents(playlist, streams.map { it.uid })
        val replay = operation.test().awaitDone(10, TimeUnit.SECONDS)
            .assertComplete().assertNoErrors().values().single()
        assertEquals(streams.map { it.uid }, replay.map { it.streamId })
        scheduler.triggerActions()
        assertContents(playlist, streams.map { it.uid })
    }

    @Test
    fun thumbnailUpdateFailureRollsBackReplacementAndOriginalMetadata() {
        val streams = streams(2)
        val playlist = playlist(streams)
        val original = metadata(playlist)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_replacement_thumbnail
            BEFORE UPDATE ON playlists
            WHEN NEW.uid = $playlist AND NEW.thumbnail_stream_id = ${streams[1].uid}
            BEGIN SELECT RAISE(ABORT, 'reject replacement thumbnail'); END
            """.trimIndent()
        )
        val manager = LocalPlaylistManager(database, Schedulers.trampoline())

        manager.updateJoin(playlist, listOf(streams[1].uid)).test()
            .awaitDone(10, TimeUnit.SECONDS).assertError(SQLiteException::class.java)

        assertContents(playlist, streams.map { it.uid })
        assertEquals(original, metadata(playlist))
    }

    @Test
    fun queuedAppendCannotBeOverwrittenByCleanupSelectedBeforeItsWrite() {
        val streams = streams(2)
        val playlist = playlist(listOf(streams[0], streams[0]))
        val scheduler = CountingScheduler(2)
        val firstManager = LocalPlaylistManager(database, scheduler)
        val secondManager = LocalPlaylistManager(database, scheduler)
        val append = firstManager.appendToPlaylist(playlist, listOf(streams[1])).test()
        val cleanup = secondManager.removeStreams(playlist, LocalPlaylistManager.Removal.DUPLICATES).test()

        assertTrue("Both accepted mutations must reach the writer", scheduler.awaitScheduled())
        scheduler.triggerActions()
        append.awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        cleanup.awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()

        assertContents(playlist, streams.map { it.uid })
        assertEquals(streams.map { it.uid }, cleanup.values().single().map { it.streamId })
    }

    @Test
    fun appendSnapshotsCallerListAndMutableStreamMetadataWhenRequestIsCreated() {
        val streams = streams(2)
        val originalTitle = streams[0].title
        val playlist = playlist(emptyList())
        val input = mutableListOf(streams[0])
        val manager = LocalPlaylistManager(database, Schedulers.trampoline())
        val operation = manager.appendToPlaylist(playlist, input)

        input.add(streams[1])
        streams[0].title = "changed after request"
        operation.test().awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()

        assertContents(playlist, listOf(streams[0].uid))
        assertEquals(originalTitle, database.streamDAO().getStream(1, streams[0].url).blockingFirst().single().title)
    }

    @Test
    fun missingPlaylistCleanupFailsInsteadOfReportingEmptySuccess() {
        val streams = streams(1)
        val existing = playlist(streams)
        val manager = LocalPlaylistManager(database, Schedulers.trampoline())

        manager.removeStreams(999_999L, LocalPlaylistManager.Removal.DUPLICATES).test()
            .awaitDone(10, TimeUnit.SECONDS).assertError { true }.assertNoValues()

        assertContents(existing, streams.map { it.uid })
        assertEquals(streams[0].uid, metadata(existing).thumbnailStreamId)
    }

    @Test
    fun unknownReplacementReferenceIsRejectedBeforeCommitAndDatabaseRemainsUsable() {
        val streams = streams(2)
        val playlist = playlist(streams)
        val original = metadata(playlist)
        val manager = LocalPlaylistManager(database, Schedulers.trampoline())

        manager.updateJoin(playlist, listOf(streams[0].uid, 999_999L)).test()
            .awaitDone(10, TimeUnit.SECONDS).assertError(IllegalArgumentException::class.java)

        assertContents(playlist, streams.map { it.uid })
        assertEquals(original, metadata(playlist))
        // Validation fails inside the transaction, so a later valid write and reactive
        // transactional read must still work on the same Room database connection.
        manager.updateJoin(playlist, streams.reversed().map { it.uid }).test()
            .awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        assertContents(playlist, streams.reversed().map { it.uid })
        assertEquals(original, metadata(playlist))
    }

    @Test
    fun staleBookmarkDisplayOrderSavePreservesFreshNameAndPermanentThumbnail() {
        val streams = streams(2)
        val playlist = playlist(streams)
        val manager = LocalPlaylistManager(database, Schedulers.trampoline())
        val staleBookmark = manager.playlists.blockingFirst().single()
        manager.renamePlaylist(playlist, "fresh name").test()
            .awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        manager.changePlaylistThumbnail(playlist, streams[1].uid, true).test()
            .awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()

        staleBookmark.displayIndex = 7L
        manager.updatePlaylists(listOf(staleBookmark), emptyList()).test()
            .awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()

        val stored = metadata(playlist)
        assertEquals("fresh name", stored.name)
        assertEquals(streams[1].uid, stored.thumbnailStreamId)
        assertTrue(stored.isThumbnailPermanent)
        assertEquals(7L, stored.displayIndex)
        assertContents(playlist, streams.map { it.uid })
    }

    @Test
    fun disposedFirstAppendAndSecondAppendAllocateConsecutiveIndexesAndKeepFirstAutomaticThumbnail() {
        val streams = streams(2)
        val playlist = playlist(emptyList())
        val scheduler = TestScheduler()
        val manager = LocalPlaylistManager(database, scheduler)
        val first = manager.appendToPlaylist(playlist, listOf(streams[0])).test()
        first.dispose()
        val second = manager.appendToPlaylist(playlist, listOf(streams[1])).test()

        scheduler.triggerActions()
        second.awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()

        assertContents(playlist, streams.map { it.uid })
        assertEquals(streams[0].uid, metadata(playlist).thumbnailStreamId)
    }

    @Test
    fun bothDisposedReplacementsPersistEverySnapshotInAcceptedRequestOrder() {
        val streams = streams(2)
        val playlist = playlist(streams)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE playlist_write_audit (sequence INTEGER PRIMARY KEY AUTOINCREMENT, stream_id INTEGER NOT NULL, join_index INTEGER NOT NULL)"
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER audit_playlist_join_insertion
            AFTER INSERT ON playlist_stream_join
            WHEN NEW.playlist_id = $playlist
            BEGIN
                INSERT INTO playlist_write_audit(stream_id, join_index)
                VALUES (NEW.stream_id, NEW.join_index);
            END
            """.trimIndent()
        )
        val scheduler = TestScheduler()
        val firstManager = LocalPlaylistManager(database, scheduler)
        val secondManager = LocalPlaylistManager(database, scheduler)
        val first = firstManager.updateJoin(playlist, streams.map { it.uid }).test()
        val second = secondManager.updateJoin(playlist, streams.reversed().map { it.uid }).test()

        first.dispose()
        second.dispose()
        scheduler.triggerActions()

        val audit = database.query(
            "SELECT stream_id, join_index FROM playlist_write_audit ORDER BY sequence",
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(0) to cursor.getInt(1))
                }
            }
        }
        assertEquals(
            listOf(streams[0].uid to 0, streams[1].uid to 1, streams[1].uid to 0, streams[0].uid to 1),
            audit
        )
        assertContents(playlist, streams.reversed().map { it.uid })
    }

    private fun assertContents(playlistId: Long, expected: List<Long>) {
        val stored = database.playlistStreamDAO().getOrderedStreamsOf(playlistId).blockingFirst()
        assertEquals(expected, stored.map { it.streamId })
        assertEquals(expected.indices.toList(), stored.map { it.joinIndex })
    }

    private fun metadata(playlistId: Long): PlaylistEntity = database.playlistDAO()
        .getPlaylist(playlistId).blockingFirst().single()

    private fun playlist(streams: List<StreamEntity>, permanent: Boolean = false): Long {
        val id = database.playlistDAO().insert(
            PlaylistEntity(
                name = "playlist",
                isThumbnailPermanent = permanent,
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

    /** Holds real writer tasks until both accepted mutations are queued, without sleeps. */
    private class CountingScheduler(expectedTasks: Int) : Scheduler() {
        private val delegate = TestScheduler()
        private val scheduled = CountDownLatch(expectedTasks)

        override fun createWorker(): Worker {
            val worker = delegate.createWorker()
            return object : Worker() {
                override fun schedule(run: Runnable, delay: Long, unit: TimeUnit): Disposable {
                    val task = worker.schedule(run, delay, unit)
                    scheduled.countDown()
                    return task
                }

                override fun dispose() = worker.dispose()

                override fun isDisposed(): Boolean = worker.isDisposed
            }
        }

        fun awaitScheduled(): Boolean = scheduled.await(10, TimeUnit.SECONDS)

        fun triggerActions() = delegate.triggerActions()
    }
}
