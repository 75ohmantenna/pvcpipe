/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.playlist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.observers.TestObserver
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Subscribes from the main thread against a database that rejects main-thread queries, as the
 * application database does. The other playlist suites allow them, so they cannot catch a lookup
 * that runs on the caller's thread.
 */
class LocalPlaylistThumbnailQueriesTest {
    private lateinit var database: AppDatabase
    private lateinit var manager: LocalPlaylistManager

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        manager = LocalPlaylistManager(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun databaseRejectsQueriesOnTheMainThread() {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            failure = runCatching { database.playlistDAO().getPlaylistSync(1) }.exceptionOrNull()
        }

        assertTrue("expected Room to refuse the query, got $failure", failure is IllegalStateException)
    }

    @Test
    fun thumbnailPermanenceIsReadFromTheMainThread() {
        val permanent = insertPlaylist(isThumbnailPermanent = true)
        val automatic = insertPlaylist(isThumbnailPermanent = false)

        fromMainThread { manager.getIsPlaylistThumbnailPermanent(permanent) }.assertValue(true)
        fromMainThread { manager.getIsPlaylistThumbnailPermanent(automatic) }.assertValue(false)
    }

    @Test
    fun thumbnailPermanenceOfMissingPlaylistIsAnError() {
        fromMainThread { manager.getIsPlaylistThumbnailPermanent(404) }
            .assertError(NoSuchElementException::class.java)
    }

    @Test
    fun automaticThumbnailIsReadFromTheMainThread() {
        val streamId = database.streamDAO().insert(
            StreamEntity(
                serviceId = 0,
                url = "https://example.com/watch?v=1",
                title = "stream",
                streamType = StreamType.VIDEO_STREAM,
                duration = 600,
                uploader = "uploader"
            )
        )
        val playlistId = insertPlaylist(isThumbnailPermanent = true)
        database.playlistStreamDAO().insert(PlaylistStreamEntity(playlistId, streamId, 0))

        fromMainThread { manager.getAutomaticPlaylistThumbnailStreamId(playlistId) }.assertValue(streamId)
    }

    @Test
    fun automaticThumbnailOfEmptyPlaylistIsTheDefault() {
        val playlistId = insertPlaylist(isThumbnailPermanent = true)

        fromMainThread { manager.getAutomaticPlaylistThumbnailStreamId(playlistId) }
            .assertValue(PlaylistEntity.DEFAULT_THUMBNAIL_ID)
    }

    @Test
    fun unsettingPermanentThumbnailFromTheMainThreadStoresTheAutomaticOne() {
        val streamId = database.streamDAO().insert(
            StreamEntity(
                serviceId = 0,
                url = "https://example.com/watch?v=2",
                title = "stream",
                streamType = StreamType.VIDEO_STREAM,
                duration = 600,
                uploader = "uploader"
            )
        )
        val playlistId = insertPlaylist(isThumbnailPermanent = true)
        database.playlistStreamDAO().insert(PlaylistStreamEntity(playlistId, streamId, 0))

        val changed = TestObserver<Int>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.getAutomaticPlaylistThumbnailStreamId(playlistId)
                .flatMapMaybe { manager.changePlaylistThumbnail(playlistId, it, false) }
                .subscribe(changed)
        }
        assertTrue(changed.await(10, TimeUnit.SECONDS))
        changed.assertNoErrors()

        val stored = database.playlistDAO().getPlaylistSync(playlistId)!!
        assertTrue(!stored.isThumbnailPermanent)
        assertTrue(stored.thumbnailStreamId == streamId)
    }

    private fun insertPlaylist(isThumbnailPermanent: Boolean): Long = database.playlistDAO().insert(
        PlaylistEntity(
            name = "playlist",
            isThumbnailPermanent = isThumbnailPermanent,
            thumbnailStreamId = PlaylistEntity.DEFAULT_THUMBNAIL_ID,
            displayIndex = 0
        )
    )

    private fun <T : Any> fromMainThread(source: () -> Single<T>): TestObserver<T> {
        lateinit var observer: TestObserver<T>
        InstrumentationRegistry.getInstrumentation().runOnMainSync { observer = source().test() }
        assertTrue("lookup did not finish", observer.await(10, TimeUnit.SECONDS))
        return observer
    }
}
