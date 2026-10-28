/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.bookmark

import android.app.Application
import android.util.Pair
import androidx.room.Room
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.LocalItem.LocalItemType
import org.schabi.newpipe.database.playlist.PlaylistLocalItem
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.local.playlist.LocalPlaylistManager

/** Real Room/native host SQLite; deterministic admission/execution, no device or services. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class BookmarkSaveManagerTest {
    private lateinit var database: AppDatabase
    private lateinit var scheduler: TestScheduler
    private lateinit var writer: BookmarkSaveManager

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        scheduler = TestScheduler()
        writer = BookmarkSaveManager(database, scheduler)
        database.playlistDAO().insert(PlaylistEntity(1, "local-one", false, -1, 0))
        database.playlistDAO().insert(PlaylistEntity(2, "local-two", false, -1, 2))
        database.playlistRemoteDAO().insert(remote())
    }

    @After
    fun cleanup() = database.close()

    private fun local(id: Long = 1, index: Long = 0) = PlaylistMetadataEntry(id, "stale-name", null, index, false, -1, 0)
    private fun remote() = PlaylistRemoteEntity(1, 0, "remote-one", "https://example.org/playlist", "old-image", "uploader", 1, 5)
    private fun localRow(id: Long = 1) = database.playlistDAO().getPlaylistSync(id)
    private fun remoteRow() = database.playlistRemoteDAO().getAll().blockingFirst().singleOrNull()

    @Test
    fun `accepted snapshots freeze order ids and deletions without mutating entities`() {
        val remote = remote()
        val local = local()
        val order = arrayListOf<PlaylistLocalItem>(remote, local)
        val deletions = arrayListOf(Pair(2L, LocalItemType.PLAYLIST_LOCAL_ITEM))
        val request = writer.save(order, deletions)
        order.reverse()
        order.clear()
        deletions.clear()
        remote.uid = 999
        remote.displayIndex = 99
        local.displayIndex = 99
        val result = request.test()
        assertNotNull(localRow(2))
        scheduler.triggerActions()
        result.assertComplete().assertNoErrors()
        assertNull(localRow(2))
        assertEquals(0L, remoteRow()!!.displayIndex)
        assertEquals(1L, localRow()!!.displayIndex)
        assertEquals(99L, local.displayIndex)
        assertEquals(99L, remote.displayIndex)
    }

    @Test
    fun `first subscription admits once and replay does not restore an older order`() {
        val request = writer.save(listOf(remote(), local()), emptyList())
        assertEquals(0L, localRow()!!.displayIndex)
        val first = request.test()
        scheduler.triggerActions()
        first.assertComplete().assertNoErrors()
        database.playlistDAO().updateDisplayIndex(1, 42)
        request.test().assertComplete().assertNoErrors()
        scheduler.triggerActions()
        assertEquals(42L, localRow()!!.displayIndex)
    }

    @Test
    fun `newer accepted snapshot from another manager wins after observer disposal`() {
        val first = writer.save(listOf(remote(), local()), emptyList()).test()
        first.dispose()
        val newer = BookmarkSaveManager(database, scheduler).save(listOf(local(), remote()), emptyList()).test()
        scheduler.triggerActions()
        newer.assertComplete().assertNoErrors()
        assertEquals(0L, localRow()!!.displayIndex)
        assertEquals(1L, remoteRow()!!.displayIndex)
    }

    @Test
    fun `local and remote deletions with equal ids stay distinct and atomic`() {
        val result = writer.save(
            listOf(local(2)),
            listOf(
                Pair(1L, LocalItemType.PLAYLIST_LOCAL_ITEM),
                Pair(1L, LocalItemType.PLAYLIST_REMOTE_ITEM)
            )
        ).test()
        scheduler.triggerActions()
        result.assertComplete().assertNoErrors()
        assertNull(localRow())
        assertNull(remoteRow())
        assertEquals(0L, localRow(2)!!.displayIndex)
    }

    @Test
    fun `remote failure rolls back local deletion and retry starts a new transaction`() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_bookmark BEFORE UPDATE OF display_index ON remote_playlists " +
                "BEGIN SELECT RAISE(ABORT, 'controlled failure'); END"
        )
        val order = listOf(remote(), local())
        val deletes = listOf(Pair(2L, LocalItemType.PLAYLIST_LOCAL_ITEM))
        val request = writer.save(order, deletes)
        val failed = request.test()
        scheduler.triggerActions()
        failed.assertError { true }
        assertNotNull(localRow(2))
        assertEquals(0L, localRow()!!.displayIndex)
        assertEquals(1L, remoteRow()!!.displayIndex)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_bookmark")
        request.test().assertError { true }
        val retry = writer.save(order, deletes).test()
        scheduler.triggerActions()
        retry.assertComplete().assertNoErrors()
        assertNull(localRow(2))
        assertEquals(1L, localRow()!!.displayIndex)
        assertEquals(0L, remoteRow()!!.displayIndex)
    }

    @Test
    fun `saving order preserves newer metadata and does not resurrect removed remote rows`() {
        val stale = remote()
        val request = writer.save(listOf(stale, local()), emptyList())
        database.playlistDAO().update(localRow()!!.copy(name = "renamed", isThumbnailPermanent = true, thumbnailStreamId = 123))
        database.playlistRemoteDAO().update(remote().copy(orderingName = "new-name", thumbnailUrl = "new-image", streamCount = 91))
        val first = request.test()
        scheduler.triggerActions()
        first.assertComplete().assertNoErrors()
        assertEquals("renamed", localRow()!!.name)
        assertEquals(true, localRow()!!.isThumbnailPermanent)
        assertEquals(123L, localRow()!!.thumbnailStreamId)
        assertEquals("new-name", remoteRow()!!.orderingName)
        assertEquals("new-image", remoteRow()!!.thumbnailUrl)
        assertEquals(91L, remoteRow()!!.streamCount)
        database.playlistRemoteDAO().deletePlaylist(1)
        val next = writer.save(listOf(stale, local()), emptyList()).test()
        scheduler.triggerActions()
        next.assertComplete().assertNoErrors()
        assertNull(remoteRow())
        assertFalse(database.playlistRemoteDAO().getAll().blockingFirst().isNotEmpty())
    }

    @Test
    fun `local failure rolls back earlier remote order update and deletion`() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_local BEFORE UPDATE OF display_index ON playlists " +
                "BEGIN SELECT RAISE(ABORT, 'controlled failure'); END"
        )
        val result = writer.save(listOf(remote(), local()), listOf(Pair(2L, LocalItemType.PLAYLIST_LOCAL_ITEM))).test()
        scheduler.triggerActions()
        result.assertError { true }
        assertEquals(1L, remoteRow()!!.displayIndex)
        assertEquals(0L, localRow()!!.displayIndex)
        assertNotNull(localRow(2))
    }

    @Test
    fun `default manager shares accepted local playlist write queue`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        LocalPlaylistManager.WRITES.scheduleDirect {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val saved = BookmarkSaveManager(database).save(listOf(remote(), local()), emptyList()).test()
            val renamed = LocalPlaylistManager(database).renamePlaylist(1, "new-name").test()
            saved.assertNotComplete()
            renamed.assertNotComplete()
            release.countDown()
            saved.awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()
            renamed.awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors()
            assertEquals("new-name", localRow()!!.name)
            assertEquals(1L, localRow()!!.displayIndex)
            assertEquals(0L, remoteRow()!!.displayIndex)
        } finally {
            release.countDown()
        }
    }
}
