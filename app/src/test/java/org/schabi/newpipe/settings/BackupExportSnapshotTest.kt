/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.settings.export.BackupDatabaseSnapshot
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.settings.export.ImportExportManager
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.io.FileStream

/** Native host SQLite, real concurrent-generation writes and actual ZIP/file bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupExportSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `writer and checkpoint during ZIP output cannot mix database generations`() {
        val context = RuntimeEnvironment.getApplication()
        val source = temporary.root.toPath().resolve("live.db")
        val room = NewPipeDatabase.createDatabase(context, source.toString(), RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        try {
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE snapshot_probe(id INTEGER PRIMARY KEY, generation INTEGER NOT NULL, payload BLOB NOT NULL)")
            val random = Random(173)
            db.beginTransaction()
            try {
                for (id in 1..64) {
                    val payload = ByteArray(16384).also { random.nextBytes(it) }
                    db.execSQL("INSERT INTO snapshot_probe VALUES(?, 0, ?)", arrayOf(id, payload))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(cursor.getInt(1), cursor.getInt(2))
            }
            val archive = temporary.root.toPath().resolve("export.zip")
            val changed = AtomicBoolean()
            val output = object : FileStream(archive.toFile()) {
                private var written = 0
                override fun write(buffer: ByteArray, offset: Int, count: Int) {
                    super.write(buffer, offset, count)
                    written += count
                    if (written >= 256 * 1024 && changed.compareAndSet(false, true)) {
                        db.execSQL("UPDATE snapshot_probe SET generation=1")
                        db.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals(cursor.getInt(1), cursor.getInt(2))
                        }
                    }
                }
            }
            val document = mock(StoredFileHelper::class.java)
            `when`(document.openAndTruncateStream()).thenReturn(output)
            val locator = mock(BackupFileLocator::class.java)
            `when`(locator.db).thenReturn(source)
            ImportExportManager(locator) { }.exportDatabase(context.getSharedPreferences("export", Context.MODE_PRIVATE), document) { copy ->
                val started = System.nanoTime()
                BackupDatabaseSnapshot.copy(db, copy)
                println("Controlled writer-gated local snapshot: ${Files.size(source)} bytes in ${(System.nanoTime() - started) / 1_000_000}ms; excludes ZIP/provider output")
            }
            assertTrue("Controlled writer must actually overlap archive output", changed.get())
            db.query("SELECT MIN(generation), MAX(generation) FROM snapshot_probe").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
            }
            val restored = extractDatabase(archive)
            SQLiteDatabase.openDatabase(restored.toString(), null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
                assertTrue(snapshot.isDatabaseIntegrityOk)
                snapshot.rawQuery("SELECT COUNT(DISTINCT generation), COUNT(*), MIN(generation) FROM snapshot_probe", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Backup must contain one coherent generation", 1, cursor.getInt(0))
                    assertEquals(64, cursor.getInt(1))
                    assertEquals("Snapshot must precede destination output", 0, cursor.getInt(2))
                }
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `snapshot includes uncheckpointed committed WAL and releases writer gate before output`() {
        val context = RuntimeEnvironment.getApplication()
        val source = temporary.root.toPath().resolve("wal-live.db")
        val room = NewPipeDatabase.createDatabase(context, source.toString(), RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        try {
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE snapshot_probe(generation INTEGER)")
            db.execSQL("INSERT INTO snapshot_probe VALUES(0)")
            db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            db.execSQL("UPDATE snapshot_probe SET generation=1")
            val before = temporary.root.toPath().resolve("before.db")
            Files.copy(source, before)
            assertEquals("Fixture must leave its committed update only in WAL", 0, generation(before))
            val snapshot = temporary.root.toPath().resolve("wal-snapshot.db")
            val attempted = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val error = AtomicReference<Throwable>()
            val writer = Thread {
                attempted.countDown()
                try {
                    db.execSQL("UPDATE snapshot_probe SET generation=2")
                } catch (failure: Throwable) {
                    error.set(failure)
                } finally {
                    completed.countDown()
                }
            }
            val started = System.nanoTime()
            BackupDatabaseSnapshot.copy(db) {
                writer.start()
                assertTrue(attempted.await(5, TimeUnit.SECONDS))
                assertFalse("Concurrent writer must wait for local snapshot", completed.await(100, TimeUnit.MILLISECONDS))
                Files.copy(source, snapshot)
            }
            println("Controlled native SQLite snapshot including 100ms writer barrier: ${(System.nanoTime() - started) / 1_000_000}ms")
            assertTrue("Writer resumes after local snapshot", completed.await(5, TimeUnit.SECONDS))
            writer.join(5000)
            error.get()?.let { throw AssertionError("Controlled writer failed", it) }
            assertEquals(1, generation(snapshot))
            db.query("SELECT generation FROM snapshot_probe").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        } finally {
            room.close()
        }
    }

    @Test
    @Config(sdk = [35])
    fun `old reader preventing complete WAL backfill rejects snapshot without copying`() {
        val context = RuntimeEnvironment.getApplication()
        val source = temporary.root.toPath().resolve("reader-live.db")
        val room = NewPipeDatabase.createDatabase(context, source.toString(), RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        try {
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE snapshot_probe(generation INTEGER)")
            db.execSQL("INSERT INTO snapshot_probe VALUES(0)")
            db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            SQLiteDatabase.openDatabase(source.toString(), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { reader ->
                // Android 15 exposes a read-only transaction without taking writer admission.
                reader.beginTransactionReadOnly()
                try {
                    reader.rawQuery("SELECT generation FROM snapshot_probe", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                    db.execSQL("UPDATE snapshot_probe SET generation=1")
                    val copied = AtomicBoolean()
                    assertThrows(IOException::class.java) { BackupDatabaseSnapshot.copy(db) { copied.set(true) } }
                    assertFalse(copied.get())
                } finally {
                    reader.endTransaction()
                }
            }
            val retried = temporary.root.toPath().resolve("reader-retry.db")
            BackupDatabaseSnapshot.copy(db) { Files.copy(source, retried) }
            assertEquals(1, generation(retried))
        } finally {
            room.close()
        }
    }

    @Test
    fun `rollback journal snapshot leaves journal mode and data unchanged`() {
        val context = RuntimeEnvironment.getApplication()
        val source = temporary.root.toPath().resolve("rollback-live.db")
        val room = NewPipeDatabase.createDatabase(context, source.toString(), RoomDatabase.JournalMode.TRUNCATE)
        try {
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE snapshot_probe(generation INTEGER)")
            db.execSQL("INSERT INTO snapshot_probe VALUES(7)")
            assertFalse(db.isWriteAheadLoggingEnabled)
            val mode = db.query("PRAGMA journal_mode").use {
                it.moveToFirst()
                it.getString(0)
            }
            val snapshot = temporary.root.toPath().resolve("rollback-snapshot.db")
            BackupDatabaseSnapshot.copy(db) { Files.copy(source, snapshot) }
            assertEquals(7, generation(snapshot))
            db.query("PRAGMA journal_mode").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(mode, cursor.getString(0))
            }
            assertFalse(Files.exists(source.resolveSibling("rollback-live.db-wal")))
        } finally {
            room.close()
        }
    }

    private fun generation(path: Path): Int = SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
        assertTrue(db.isDatabaseIntegrityOk)
        db.rawQuery("SELECT generation FROM snapshot_probe", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun extractDatabase(archive: Path): Path {
        val output = temporary.root.toPath().resolve("snapshot.db")
        ZipFile(archive.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry(BackupFileLocator.FILE_NAME_DB)).use { Files.copy(it, output) }
        }
        return output
    }
}
