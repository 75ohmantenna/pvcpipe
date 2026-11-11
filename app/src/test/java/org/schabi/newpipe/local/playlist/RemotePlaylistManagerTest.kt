package org.schabi.newpipe.local.playlist

import android.app.Application
import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.playlist.PlaylistInfo

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RemotePlaylistManagerTest {
    private lateinit var database: AppDatabase
    private lateinit var manager: RemotePlaylistManager

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        manager = RemotePlaylistManager(database)
    }

    @After
    fun cleanup() = database.close()

    @Test
    fun `metadata refresh updates every remote field while preserving saved order`() {
        val uid = insert()
        val info = info()
        assertEquals(1, manager.onUpdate(uid, info).blockingGet())
        assertEquals(PlaylistRemoteEntity(info).copy(uid = uid, displayIndex = 3), stored(uid))
        assertEquals("https://example.test/thumbnail", stored(uid).thumbnailUrl)
    }

    @Test
    fun `null metadata and avatar fallback retain order before and after reorder`() {
        val uid = insert()
        val info = info()
        `when`(info.name).thenReturn(null)
        `when`(info.url).thenReturn(null)
        `when`(info.uploaderName).thenReturn(null)
        `when`(info.thumbnails).thenReturn(emptyList())
        `when`(info.uploaderAvatars).thenReturn(listOf(Image("https://example.test/avatar", 100, 100, Image.ResolutionLevel.MEDIUM)))
        database.playlistRemoteDAO().updateDisplayIndex(uid, 8)
        assertEquals(1, manager.onUpdate(uid, info).blockingGet())
        assertEquals(PlaylistRemoteEntity(info).copy(uid = uid, displayIndex = 8), stored(uid))
        assertEquals("https://example.test/avatar", stored(uid).thumbnailUrl)
        database.playlistRemoteDAO().updateDisplayIndex(uid, 2)
        `when`(info.uploaderAvatars).thenReturn(emptyList())
        assertEquals(1, manager.onUpdate(uid, info).blockingGet())
        assertEquals(PlaylistRemoteEntity(info).copy(uid = uid, displayIndex = 2), stored(uid))
        assertEquals(null, stored(uid).thumbnailUrl)
        database.playlistRemoteDAO().updateDisplayIndex(uid, 5)
        assertEquals(PlaylistRemoteEntity(info).copy(uid = uid, displayIndex = 5), stored(uid))
    }

    @Test
    fun `metadata query accepts null count and strings without changing order`() {
        val uid = insert()
        assertEquals(1, database.playlistRemoteDAO().updateMetadata(uid, 2, null, null, null, null, null))
        assertEquals(PlaylistRemoteEntity(uid, 2, null, null, null, null, 3, null), stored(uid))
    }

    @Test
    fun `deleted playlist returns zero without recreation and new bookmarks keep default order`() {
        val uid = insert()
        database.playlistRemoteDAO().deletePlaylist(uid)
        assertEquals(0, manager.onUpdate(uid, info()).blockingGet())
        assertTrue(database.playlistRemoteDAO().getAll().blockingFirst().isEmpty())
        val newId = manager.onBookmark(info()).blockingGet()
        assertEquals(-1L, stored(newId).displayIndex)
    }

    @Test
    fun `identity conflict still fails atomically without changing either playlist`() {
        val uid = insert()
        val other = manager.onBookmark(info()).blockingGet()
        val before = database.playlistRemoteDAO().getAll().blockingFirst()
        assertTrue(runCatching { manager.onUpdate(uid, info()).blockingGet() }.isFailure)
        assertEquals(before, database.playlistRemoteDAO().getAll().blockingFirst())
        assertEquals(-1L, stored(other).displayIndex)
    }

    private fun insert() = database.playlistRemoteDAO().insert(
        PlaylistRemoteEntity(
            serviceId = 0,
            orderingName = "old",
            url = "https://example.test/old",
            thumbnailUrl = "old thumbnail",
            uploader = "old uploader",
            displayIndex = 3,
            streamCount = 1
        )
    )

    private fun stored(uid: Long) = database.playlistRemoteDAO().getPlaylist(uid).blockingFirst()

    private fun info() = mock(PlaylistInfo::class.java).apply {
        `when`(serviceId).thenReturn(1)
        `when`(name).thenReturn("new name")
        `when`(url).thenReturn("https://example.test/new")
        `when`(uploaderName).thenReturn("new uploader")
        `when`(streamCount).thenReturn(43L)
        `when`(thumbnails).thenReturn(listOf(Image("https://example.test/thumbnail", 100, 100, Image.ResolutionLevel.MEDIUM)))
        `when`(uploaderAvatars).thenReturn(emptyList())
    }
}
