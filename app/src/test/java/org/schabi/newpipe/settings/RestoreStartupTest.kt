/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.preference.PreferenceManager
import androidx.room.RoomDatabase
import com.jakewharton.processphoenix.ProcessPhoenix
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.acra.ACRA
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.App
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.settings.export.BackupDatabaseValidator
import org.schabi.newpipe.settings.export.PendingDatabaseRestore
import org.schabi.newpipe.settings.export.RestoreTransaction

/** Production attach/recovery with host SQLite and Android preferences; no real providers/device. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class RestoreStartupTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var database: Path
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var acra: MockedStatic<ACRA>

    class Probe : App() {
        var creations = 0
        fun attach(context: Context) = attachBaseContext(context)
        fun initializeHelper() = super.onCreate()
        override fun onCreate() {
            creations++
        }
    }

    @Before
    fun setup() {
        acra = Mockito.mockStatic(ACRA::class.java)
        val base = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context? = null
            override fun getDatabasePath(name: String): File = temporary.root.toPath().resolve(name).toFile()
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("restore-${temporary.root.name}", mode)
        }
        database = context.getDatabasePath("newpipe.db").toPath()
        create(database, "old")
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        assertTrue(preferences.edit().clear().putString("version", "old").putLong("long", Long.MAX_VALUE).commit())
    }

    @After
    fun cleanup() = acra.close()

    private fun create(path: Path, search: String) {
        val room = NewPipeDatabase.createDatabase(context, path.toString(), RoomDatabase.JournalMode.TRUNCATE)
        try {
            room.openHelper.writableDatabase.execSQL("INSERT INTO search_history(service_id,search) VALUES(0, ?)", arrayOf(search))
        } finally {
            room.close()
        }
    }

    private fun queue() {
        val candidate = temporary.root.toPath().resolve("candidate.db")
        create(candidate, "new")
        BackupDatabaseValidator(context).validate(candidate)
        PendingDatabaseRestore.publish(database, candidate, mapOf("version" to "new"), true)
    }

    private fun search(): String = SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT search FROM search_history", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
    }

    @Test
    fun `attach activates the pair before onCreate without an application context`() {
        queue()
        assertNull(context.applicationContext)
        assertEquals("old", preferences.getString("version", null))
        assertEquals("old", search())
        val app = Probe()
        app.attach(context)
        assertEquals(0, app.creations)
        assertEquals("new", search())
        assertEquals(mapOf("version" to "new"), preferences.all)
        assertFalse(PendingDatabaseRestore.hasPending(database))
    }

    @Test
    fun `invalid legacy pending file preserves real database sidecars and preferences`() {
        val before = Files.readAllBytes(database)
        val wal = database.resolveSibling("newpipe.db-wal")
        Files.writeString(wal, "old WAL bytes")
        Files.writeString(database.resolveSibling("newpipe.db.restore"), "not SQLite")
        val app = Probe()
        app.attach(context)
        org.junit.Assert.assertArrayEquals(before, Files.readAllBytes(database))
        assertEquals("old WAL bytes", Files.readString(wal))
        assertEquals("old", preferences.getString("version", null))
        assertEquals(Long.MAX_VALUE, preferences.getLong("long", 0))
        assertFalse(PendingDatabaseRestore.hasPending(database))
        val field = App::class.java.getDeclaredField("restoreStartupFailure").also { it.isAccessible = true }
        assertNotNull(field.get(app))
    }

    @Test
    fun `modified staged database is rejected during attach`() {
        queue()
        Files.writeString(PendingDatabaseRestore.pendingDatabase(database), "changed")
        Probe().attach(context)
        assertEquals("old", search())
        assertEquals("old", preferences.getString("version", null))
        assertFalse(PendingDatabaseRestore.hasPending(database))
    }

    private class Interrupted : Error()

    @Test
    fun `new startup rolls back an interrupted Android preference commit`() {
        queue()
        val io = object : RestoreTransaction.FileSystem() {
            override fun checkpoint(step: RestoreTransaction.Step, path: Path) {
                if (step == RestoreTransaction.Step.AFTER_PREFERENCES) throw Interrupted()
            }
        }
        assertThrows(Interrupted::class.java) {
            RestoreTransaction(database, PendingDatabaseRestore.preferenceStore(preferences), BackupDatabaseValidator(context)::validate, io).recover()
        }
        assertEquals("new", search())
        assertEquals("new", preferences.getString("version", null))
        Probe().attach(context)
        assertEquals("old", search())
        assertEquals("old", preferences.getString("version", null))
        assertEquals(Long.MAX_VALUE, preferences.getLong("long", 0))
    }

    @Test
    fun `completed record does not replay over later Android preference edits`() {
        queue()
        val io = object : RestoreTransaction.FileSystem() {
            override fun checkpoint(step: RestoreTransaction.Step, path: Path) {
                if (step == RestoreTransaction.Step.BEFORE_RETIRE) throw IOException("cleanup blocked")
            }
        }
        assertNotNull(RestoreTransaction(database, PendingDatabaseRestore.preferenceStore(preferences), BackupDatabaseValidator(context)::validate, io).recover())
        assertTrue(preferences.edit().putString("version", "later").commit())
        Probe().attach(context)
        assertEquals("later", preferences.getString("version", null))
        assertEquals("new", search())
    }

    @Test
    fun `helper processes leave queued restores untouched`() {
        queue()
        Mockito.mockStatic(ProcessPhoenix::class.java).use { phoenix ->
            phoenix.`when`<Boolean> { ProcessPhoenix.isPhoenixProcess(Mockito.any(Context::class.java)) }.thenReturn(true)
            Probe().attach(context)
        }
        assertTrue(PendingDatabaseRestore.hasPending(database))
        assertEquals("old", search())
        acra.`when`<Boolean> { ACRA.isACRASenderServiceProcess() }.thenReturn(true)
        Probe().attach(context)
        acra.`when`<Boolean> { ACRA.isACRASenderServiceProcess() }.thenReturn(false)
        assertTrue(PendingDatabaseRestore.hasPending(database))
        assertEquals("old", preferences.getString("version", null))
        Probe().attach(context)
        assertEquals("new", search())
    }

    @Test
    fun `legacy pending database is validated before activation and keeps current settings`() {
        val legacy = database.resolveSibling("newpipe.db.restore")
        create(legacy, "new")
        Probe().attach(context)
        assertEquals("new", search())
        assertEquals("old", preferences.getString("version", null))
        assertEquals(Long.MAX_VALUE, preferences.getLong("long", 0))
        assertFalse(Files.exists(legacy))
    }

    @Test
    fun `damaged undo state prevents application attach from exposing a mixed pair`() {
        queue()
        val io = object : RestoreTransaction.FileSystem() {
            override fun checkpoint(step: RestoreTransaction.Step, path: Path) {
                if (step == RestoreTransaction.Step.AFTER_PREFERENCES) throw Interrupted()
            }
        }
        assertThrows(Interrupted::class.java) {
            RestoreTransaction(database, PendingDatabaseRestore.preferenceStore(preferences), BackupDatabaseValidator(context)::validate, io).recover()
        }
        val previous = RestoreTransaction.transactionPath(database).resolve("previous")
        val saved = Files.readAllBytes(previous)
        Files.writeString(previous, "corrupt undo snapshot")
        val app = Probe()
        assertThrows(RestoreTransaction.RecoveryException::class.java) { app.attach(context) }
        assertEquals(0, app.creations)
        assertEquals("new", search())
        assertEquals("new", preferences.getString("version", null))
        Files.write(previous, saved)
        Probe().attach(context)
        assertEquals("old", search())
        assertEquals("old", preferences.getString("version", null))
    }

    @Test
    fun `helper onCreate skips extractor settings and preference listeners`() {
        val app = Probe()
        acra.`when`<Boolean> { ACRA.isACRASenderServiceProcess() }.thenReturn(true)
        app.attach(context)
        app.initializeHelper()
        assertThrows(UninitializedPropertyAccessException::class.java) { app.extractorSettings }
        assertEquals(mapOf("version" to "old", "long" to Long.MAX_VALUE), preferences.all)
    }

    @Test
    fun `interrupted activation recovers committed rows stored in a real SQLite WAL`() {
        val snapshot = temporary.newFolder("wal-snapshot").toPath()
        val suffixes = listOf("", "-wal", "-shm")
        SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL("INSERT INTO search_history(service_id,search) VALUES(0, 'wal-only')")
            suffixes.forEach { suffix ->
                Files.copy(database.resolveSibling("newpipe.db$suffix"), snapshot.resolve("database$suffix"))
            }
        }
        val mainOnly = temporary.root.toPath().resolve("main-only.db")
        Files.copy(snapshot.resolve("database"), mainOnly)
        SQLiteDatabase.openDatabase(mainOnly.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM search_history WHERE search='wal-only'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        suffixes.forEach { suffix ->
            Files.copy(snapshot.resolve("database$suffix"), database.resolveSibling("newpipe.db$suffix"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        queue()
        val io = object : RestoreTransaction.FileSystem() {
            override fun checkpoint(step: RestoreTransaction.Step, path: Path) {
                if (step == RestoreTransaction.Step.AFTER_PREFERENCES) throw Interrupted()
            }
        }
        assertThrows(Interrupted::class.java) {
            RestoreTransaction(database, PendingDatabaseRestore.preferenceStore(preferences), BackupDatabaseValidator(context)::validate, io).recover()
        }
        assertEquals("new", search())
        Probe().attach(context)
        suffixes.forEach { suffix ->
            org.junit.Assert.assertArrayEquals(Files.readAllBytes(snapshot.resolve("database$suffix")), Files.readAllBytes(database.resolveSibling("newpipe.db$suffix")))
        }
        SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT search FROM search_history ORDER BY id", null).use { cursor ->
                val rows = mutableListOf<String>()
                while (cursor.moveToNext()) rows.add(cursor.getString(0))
                assertEquals(listOf("old", "wal-only"), rows)
            }
        }
        assertEquals("old", preferences.getString("version", null))
    }
}
