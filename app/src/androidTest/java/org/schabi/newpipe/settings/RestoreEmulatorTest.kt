/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.grack.nanojson.JsonWriter
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.App
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.settings.export.BackupDatabaseValidator
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.settings.export.ImportExportManager
import org.schabi.newpipe.settings.export.PendingDatabaseRestore
import org.schabi.newpipe.settings.export.RestoreTransaction
import org.schabi.newpipe.streams.io.StoredFileHelper

/**
 * Host-orchestrated phases on an explicitly marked disposable emulator installation.
 * Nonterminal phases wait for SIGKILL; no teardown/connection close simulates process death.
 * See tools/restore_emulator_validation.py. Ordinary test discovery skips this driver.
 */
class RestoreEmulatorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val preferences get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val database get() = context.getDatabasePath("newpipe.db").toPath()
    private lateinit var directory: Path
    private lateinit var scenario: String
    private lateinit var nonce: String
    private lateinit var phase: String
    private val archive get() = directory.resolve("source.zip")
    private val oldTag = "restore_test_old_only"
    private val tag = "restore_test_version"
    private val longKey = "restore_test_long"
    private val setKey = "restore_test_set"
    private val lateKey = "restore_test_after_stage"

    @Test
    fun runPhase() {
        assumeTrue("Requires disposable emulator orchestrator", arguments.getString("restoreDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(context.packageName == "org.seventyfiveohmantenna.pvcpipe.debug")
        nonce = requireNotNull(arguments.getString("restoreNonce"))
        check(nonce.matches(Regex("[a-f0-9]{32}")))
        check(context.filesDir.resolve("restore-emulator-allow").readText() == nonce)
        scenario = requireNotNull(arguments.getString("restoreCase"))
        check(scenario.matches(Regex("[a-z0-9-]{1,64}")))
        phase = requireNotNull(arguments.getString("restorePhase"))
        directory = context.filesDir.toPath().resolve("restore-emulator-$nonce").resolve(scenario)
        when (phase) {
            "prepare" -> prepare()
            "activate", "rollback" -> interruptedRecovery()
            "later" -> laterEdits()
            "verify" -> verifyProductionStartup()
            else -> error("Unknown restore phase")
        }
    }

    private fun create(path: Path, row: String) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, path.toString())
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            room.openHelper.writableDatabase.execSQL("INSERT INTO search_history(service_id,search) VALUES(0, ?)", arrayOf(row))
        } finally {
            room.close()
        }
    }

    private fun rows(path: Path): List<String> = SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT search FROM search_history ORDER BY id", null).use { cursor ->
            val result = mutableListOf<String>()
            while (cursor.moveToNext()) result.add(cursor.getString(0))
            result
        }
    }

    private fun copy(from: Path, to: Path) = Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)

    private fun prepare() {
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        assertFalse("Refusing to replace an earlier case", Files.exists(directory))
        Files.createDirectories(directory)
        assertFalse("Earlier restore must have been handled", PendingDatabaseRestore.hasPending(database))
        val baseline = context.getSharedPreferences("restore-emulator-baseline", Context.MODE_PRIVATE)
        if (baseline.all.isEmpty()) {
            assertTrue("Launch production App once before fixtures", preferences.contains(context.getString(R.string.last_used_preferences_version)))
            ImportExportManager.replacePreferences(baseline, preferences.all)
        }
        val imported = baseline.all.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
        imported[context.getString(R.string.saved_tabs_key)] = "{\"tabs\":[{\"tab_id\":0}]}"
        ImportExportManager.replacePreferences(preferences, imported)
        assertTrue(
            preferences.edit().putString(tag, "old").putBoolean(oldTag, true)
                .putLong(longKey, Long.MAX_VALUE).putStringSet(setKey, setOf("alpha", "beta")).commit()
        )
        imported[tag] = "new"
        imported["restore_test_new_only"] = true
        NewPipeDatabase.close()
        context.deleteDatabase("newpipe.db")
        create(database, "old")
        SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL("INSERT INTO search_history(service_id,search) VALUES(0, 'wal-only')")
            for (suffix in listOf("", "-wal", "-shm")) copy(live(suffix), saved(suffix))
        }
        val mainOnly = directory.resolve("main-only.db")
        copy(saved(""), mainOnly)
        assertEquals("WAL-only control must be absent from main file", listOf("old"), rows(mainOnly))
        val control = Files.createDirectory(directory.resolve("wal-control"))
        for (suffix in listOf("", "-wal", "-shm")) {
            copy(saved(suffix), live(suffix))
            copy(saved(suffix), control.resolve("newpipe.db$suffix"))
        }
        assertEquals(listOf("old", "wal-only"), rows(control.resolve("newpipe.db")))
        val candidate = directory.resolve("candidate.db")
        create(candidate, "restored")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("newpipe.db"))
            Files.copy(candidate, zip)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("preferences.json"))
            JsonWriter.indent("").on(zip).`object`(imported).done()
            zip.closeEntry()
        }
        if (scenario == "legacy-reject") {
            Files.write(live(".restore"), "not a SQLite database".toByteArray())
        } else if (!scenario.startsWith("choice-")) {
            stageWithoutRestart()
            if (scenario == "reject") Files.write(PendingDatabaseRestore.pendingDatabase(database), "modified".toByteArray())
            assertTrue(preferences.edit().putString(lateKey, "edited-after-staging").commit())
        }
        assertArrayEquals(Files.readAllBytes(saved("")), Files.readAllBytes(database))
        assertArrayEquals(Files.readAllBytes(saved("-wal")), Files.readAllBytes(live("-wal")))
        assertEquals("old", preferences.getString(tag, null))
        gate()
    }

    private fun stageWithoutRestart() {
        // Keep the production preference normalization/document handling, replacing only restart.
        val constructor = Class.forName("org.schabi.newpipe.settings.BackupRestore\$AndroidPlatform")
            .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val delegate = constructor.newInstance(context) as BackupRestore.Platform
        var restarts = 0
        val platform = object : BackupRestore.Platform {
            override fun document(uri: Uri): StoredFileHelper = delegate.document(uri)
            override fun checkpoint() = Unit
            override fun normalizeImportedPreferences(values: MutableMap<String, Any>) = delegate.normalizeImportedPreferences(values)
            override fun restart() {
                restarts++
            }
            override fun reportRestoreFailure(error: Throwable) = throw AssertionError("Restore failed", error)
        }
        val backups = BackupRestore(
            BackupOperations(Schedulers.trampoline()),
            ImportExportManager(BackupFileLocator(context), BackupDatabaseValidator(context)),
            preferences,
            context.getString(R.string.import_export_data_path),
            platform,
            Schedulers.trampoline()
        )
        val inspection = backups.inspect(Uri.fromFile(archive.toFile())).blockingGet()
        backups.restore(inspection, if (scenario == "staged-db") BackupRestore.RestoreChoice.DATABASE_ONLY else BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS).blockingAwait()
        assertEquals(1, restarts)
        assertTrue(PendingDatabaseRestore.hasPending(database))
    }

    private fun interruptedRecovery() {
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        val wanted = RestoreTransaction.Step.valueOf(requireNotNull(arguments.getString("restoreStep")))
        val occurrence = arguments.getString("restoreOccurrence", "1").toInt()
        var seen = 0
        val io = object : RestoreTransaction.FileSystem() {
            override fun checkpoint(step: RestoreTransaction.Step, path: Path) {
                if (step == wanted && ++seen == occurrence) gate()
            }
        }
        RestoreTransaction(database, PendingDatabaseRestore.preferenceStore(preferences), BackupDatabaseValidator(context)::validate, io).recover()
        error("Recovery checkpoint was not reached")
    }

    private fun laterEdits() {
        assertTrue(Files.exists(RestoreTransaction.transactionPath(database).resolve("complete")))
        assertEquals(listOf("restored"), rows(database))
        SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO search_history(service_id,search) VALUES(0, 'later')")
        }
        assertTrue(preferences.edit().putString(tag, "later").commit())
        gate()
    }

    private fun verifyProductionStartup() {
        assertTrue("Production App subclass required", context.applicationContext is App)
        assertFalse(PendingDatabaseRestore.hasPending(database))
        val old = scenario in setOf("reject", "legacy-reject") || scenario.startsWith("activation-") || scenario.startsWith("rollback-")
        if (old) {
            assertArrayEquals("Original main bytes", Files.readAllBytes(saved("")), Files.readAllBytes(database))
            assertArrayEquals("Original WAL bytes", Files.readAllBytes(saved("-wal")), Files.readAllBytes(live("-wal")))
            assertEquals("old", preferences.getString(tag, null))
            assertTrue(preferences.getBoolean(oldTag, false))
            assertEquals(Long.MAX_VALUE, preferences.getLong(longKey, 0))
            assertEquals(setOf("alpha", "beta"), preferences.getStringSet(setKey, null))
            assertEquals(listOf("old", "wal-only"), rows(database))
        } else {
            assertEquals(if (scenario == "committed-later") listOf("restored", "later") else listOf("restored"), rows(database))
            val databaseOnly = scenario in setOf("choice-db", "staged-db")
            assertEquals(
                if (scenario == "committed-later") {
                    "later"
                } else if (databaseOnly) {
                    "old"
                } else {
                    "new"
                },
                preferences.getString(tag, null)
            )
            assertEquals(databaseOnly, preferences.getBoolean(oldTag, false))
            assertEquals(!databaseOnly, preferences.getBoolean("restore_test_new_only", false))
            val remembered = requireNotNull(preferences.getString(context.getString(R.string.import_export_data_path), null))
            if (scenario.startsWith("choice-")) {
                val uri = Uri.parse(remembered)
                assertEquals("content", uri.scheme)
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    assertArrayEquals("Remembered SAF document must be the selected archive", Files.readAllBytes(archive), input.readBytes())
                }
            } else {
                assertEquals(Uri.fromFile(archive.toFile()).toString(), remembered)
            }
            if (scenario == "staged-db") assertEquals("edited-after-staging", preferences.getString(lateKey, null))
        }
        publish("passed")
    }

    private fun live(suffix: String) = database.resolveSibling("newpipe.db$suffix")
    private fun saved(suffix: String) = directory.resolve("old-main$suffix")

    private fun digest(path: Path): String? = if (Files.isRegularFile(path)) {
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it.toInt() and 255) }
    } else {
        null
    }

    private fun publish(state: String) {
        val pending = RestoreTransaction.transactionPath(database)
        val result = JSONObject().put("state", state).put("phase", phase).put("case", scenario)
            .put("nonce", nonce).put("pid", Process.myPid()).put("application", context.applicationContext.javaClass.name)
            .put("productionApp", context.applicationContext is App)
            .put("mainSha256", digest(database)).put("walSha256", digest(live("-wal")))
            .put("savedMainSha256", digest(saved(""))).put("savedWalSha256", digest(saved("-wal")))
            .put("preferenceTag", preferences.getString(tag, null)).put("activation", Files.exists(pending.resolve("activation")))
            .put("complete", Files.exists(pending.resolve("complete")))
        FileOutputStream(directory.resolve("$phase.json").toFile()).use { out ->
            out.write(result.toString(2).toByteArray())
            out.fd.sync()
        }
    }

    private fun gate(): Nothing {
        publish("ready-for-kill")
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
        throw IOException("Host did not kill the checkpoint process")
    }
}
