package org.schabi.newpipe.pvc.feature.savesearchpresets.data

import android.content.SharedPreferences
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.schabi.newpipe.pvc.feature.savesearchpresets.domain.SortDirection
import org.schabi.newpipe.pvc.feature.savesearchpresets.domain.SortType

class EntryDatabaseTest {
    private val prefs = mock(SharedPreferences::class.java)
    private val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
    private val database = EntryDatabase(prefs)
    private val empty = DBState(1, SortType.NAME, SortDirection.DESC, emptyList(), emptyList())

    @Test
    fun `missing database loads defaults without writing preferences`() {
        assertEquals(empty, database.load())
        verify(prefs, never()).edit()
    }

    @Test
    fun `unreadable databases load defaults without discarding original data`() {
        mockStatic(Log::class.java).use {
            for (original in listOf("invalid", "{\"version\":2}", "{\"version\":1,\"entries\":[null]}")) {
                `when`(prefs.getString(EntryDbKeys.PREF_KEY_JSON_DB, null)).thenReturn(original)
                assertEquals(empty, database.load())
            }
        }
        verify(prefs, never()).edit()
    }

    @Test
    fun `save archives exact unreadable JSON and retains earlier recoveries atomically`() {
        val original = "broken JSON \n with unicode 音楽"
        val previous = mutableSetOf("earlier snapshot")
        `when`(prefs.getString(EntryDbKeys.PREF_KEY_JSON_DB, null)).thenReturn(original)
        `when`(prefs.getStringSet(EntryDbKeys.PREF_KEY_JSON_DB_RECOVERY, emptySet()))
            .thenReturn(previous)
        `when`(prefs.edit()).thenReturn(editor)
        mockStatic(Log::class.java).use { database.load() }

        database.save(empty)

        assertEquals(setOf("earlier snapshot"), previous)
        val writes = inOrder(editor)
        writes.verify(editor).putStringSet(
            EntryDbKeys.PREF_KEY_JSON_DB_RECOVERY,
            setOf("earlier snapshot", original)
        )
        writes.verify(editor).putString(EntryDbKeys.PREF_KEY_JSON_DB, EntryDatabaseJson.encode(empty))
        writes.verify(editor).apply()
        verify(prefs).edit()
    }

    @Test
    fun `successful save clears pending recovery`() {
        val original = "invalid"
        `when`(prefs.getString(EntryDbKeys.PREF_KEY_JSON_DB, null)).thenReturn(original)
        `when`(prefs.edit()).thenReturn(editor)
        mockStatic(Log::class.java).use { database.load() }

        database.save(empty)
        database.save(empty.copy(sorting = SortType.CREATED))

        verify(editor).putStringSet(EntryDbKeys.PREF_KEY_JSON_DB_RECOVERY, setOf(original))
    }

    @Test
    fun `loading a repaired database clears pending recovery`() {
        `when`(prefs.getString(EntryDbKeys.PREF_KEY_JSON_DB, null))
            .thenReturn("invalid", EntryDatabaseJson.encode(empty))
        `when`(prefs.edit()).thenReturn(editor)
        mockStatic(Log::class.java).use { database.load() }
        assertEquals(empty, database.load())

        database.save(empty)

        verify(prefs, never()).getStringSet(EntryDbKeys.PREF_KEY_JSON_DB_RECOVERY, emptySet())
    }

    @Test
    fun `failed save leaves recovery available for retry`() {
        val original = "invalid"
        `when`(prefs.getString(EntryDbKeys.PREF_KEY_JSON_DB, null)).thenReturn(original)
        `when`(prefs.edit()).thenThrow(IllegalStateException("write failed")).thenReturn(editor)
        mockStatic(Log::class.java).use { database.load() }

        assertThrows(IllegalStateException::class.java) { database.save(empty) }
        database.save(empty)

        verify(editor).putStringSet(EntryDbKeys.PREF_KEY_JSON_DB_RECOVERY, setOf(original))
    }
}
