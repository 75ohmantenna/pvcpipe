/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.Migrations
import org.schabi.newpipe.settings.export.BackupDatabaseValidator
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.settings.export.ImportExportManager
import org.schabi.newpipe.settings.export.PendingDatabaseRestore
import org.schabi.newpipe.streams.io.StoredFileHelper

/** Native host SQLite and real Room/preference/file paths; no device or service access. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
class BackupValidationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var directory: Path
    private lateinit var database: Path
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var archives: ImportExportManager
    private lateinit var backups: BackupRestore
    private lateinit var originalDatabase: ByteArray
    private var restarts = 0
    private val errors = mutableListOf<Throwable>()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        directory = temporary.root.toPath()
        database = schema(9, "live")
        originalDatabase = Files.readAllBytes(database)
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            Files.writeString(database.resolveSibling("${database.fileName}$suffix"), "live $suffix")
        }
        preferences = context.getSharedPreferences("validation", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().putString("original", "settings").commit())
        val locator = org.mockito.Mockito.mock(BackupFileLocator::class.java)
        org.mockito.Mockito.`when`(locator.db).thenReturn(database)
        val platform = object : BackupRestore.Platform {
            override fun document(uri: Uri) = StoredFileHelper(context, uri, "application/zip")
            override fun checkpoint() = Unit
            override fun normalizeImportedPreferences(values: MutableMap<String, Any>) = Unit
            override fun restart() {
                restarts++
            }
            override fun reportRestoreFailure(error: Throwable) {
                errors.add(error)
            }
        }
        archives = ImportExportManager(locator, BackupDatabaseValidator(context))
        backups = BackupRestore(
            BackupOperations(Schedulers.trampoline()),
            archives,
            preferences,
            "location",
            platform,
            Schedulers.trampoline()
        )
    }

    private fun schema(version: Int, search: String = "restored"): Path {
        val path = directory.resolve("candidate-$version-$search.db")
        Files.deleteIfExists(path)
        SQLiteDatabase.openOrCreateDatabase(path.toFile(), null).use { db ->
            val resourceVersion = if (version == 1) 2 else version
            val schema = javaClass.classLoader!!.getResourceAsStream(
                "org.schabi.newpipe.database.AppDatabase/$resourceVersion.json"
            )!!.use { JsonParser.`object`().from(it).getObject("database") }
            for (value in schema.getArray("entities")) {
                val table = value as JsonObject
                val name = table.getString("tableName")
                if (version == 1 && name !in listOf("subscriptions", "search_history")) continue
                db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
                for (indexValue in table.getArray("indices")) {
                    val index = indexValue as JsonObject
                    if (version == 1 && index.getString("name") == "index_search_history_search") continue
                    db.execSQL(index.getString("createSql").replace("\${TABLE_NAME}", name))
                }
            }
            if (version == 1) {
                // Schema 1 is not exported; these are the columns consumed by migration 1 -> 2.
                db.execSQL(
                    "CREATE TABLE watch_history(service_id INTEGER, url TEXT, title TEXT, " +
                        "duration INTEGER, uploader TEXT, thumbnail_url TEXT, creation_date INTEGER)"
                )
            } else {
                for (query in schema.getArray("setupQueries")) db.execSQL(query as String)
            }
            db.execSQL("INSERT INTO search_history(service_id, search) VALUES(0, ?)", arrayOf(search))
            db.version = version
        }
        return path
    }

    private fun archive(bytes: ByteArray, settings: String = "{\"restored\":true}"): Path {
        val path = directory.resolve("source.zip")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entry(zip, "newpipe.db", bytes)
            entry(zip, "preferences.json", settings.toByteArray())
        }
        return path
    }

    private fun entry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(
            ZipEntry(name).also {
                it.method = ZipEntry.STORED
                it.size = bytes.size.toLong()
                it.crc = CRC32().also { crc -> crc.update(bytes) }.value
            }
        )
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun inspect(path: Path) = backups.inspect(Uri.fromFile(path.toFile())).blockingGet()

    private fun rejected(inspection: BackupRestore.Inspection, choice: BackupRestore.RestoreChoice) {
        val errorCount = errors.size
        backups.restore(inspection, choice).test().assertError(IOException::class.java)
        assertArrayEquals(originalDatabase, Files.readAllBytes(database))
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            assertEquals("live $suffix", Files.readString(database.resolveSibling("${database.fileName}$suffix")))
        }
        assertEquals(mapOf("original" to "settings"), preferences.all)
        assertFalse(PendingDatabaseRestore.hasPending(database))
        assertEquals(0, restarts)
        assertEquals(errorCount + 1, errors.size)
        assertCleaned()
    }

    private fun assertCleaned() {
        Files.list(directory).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith("backup-") })
        }
    }

    @Test
    fun `inspection rejects plain text and truncated ZIP directory`() {
        val path = directory.resolve("plain.zip")
        Files.writeString(path, "not a ZIP archive")
        assertFalse(inspect(path).readable())
        archive(Files.readAllBytes(schema(9))).also {
            val bytes = Files.readAllBytes(it)
            Files.write(it, bytes.copyOf(bytes.size - 22))
            assertFalse(inspect(it).readable())
        }
        assertCleaned()
    }

    @Test
    fun `non SQLite database is rejected for either restore choice`() {
        val inspection = inspect(archive("not a SQLite database".toByteArray()))
        for (choice in BackupRestore.RestoreChoice.values()) rejected(inspection, choice)
    }

    @Test
    fun `header alone is not an openable database`() {
        rejected(inspect(archive("SQLite format 3\u0000".toByteArray() + ByteArray(84))), BackupRestore.RestoreChoice.DATABASE_ONLY)
    }

    @Test
    fun `unversioned SQLite database cannot become a new empty app database`() {
        val path = directory.resolve("unversioned.db")
        SQLiteDatabase.openOrCreateDatabase(path.toFile(), null).use { it.execSQL("CREATE TABLE unrelated(value TEXT)") }
        rejected(inspect(archive(Files.readAllBytes(path))), BackupRestore.RestoreChoice.DATABASE_ONLY)
    }

    @Test
    fun `future schema is rejected even with a current Room identity hash`() {
        val path = schema(9)
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = Migrations.DB_VER_9 + 1
        }
        rejected(inspect(archive(Files.readAllBytes(path))), BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
    }

    @Test
    fun `matching Room identity hash cannot hide a missing table`() {
        val path = schema(9)
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE subscriptions")
        }
        rejected(inspect(archive(Files.readAllBytes(path))), BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
    }

    @Test
    fun `foreign key violations are rejected before changing settings`() {
        val path = schema(9)
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO stream_state(stream_id, progress_time) VALUES(999, 1000)")
        }
        rejected(inspect(archive(Files.readAllBytes(path))), BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
    }

    @Test
    fun `changed database after inspection is validated again`() {
        val path = archive(Files.readAllBytes(schema(9)))
        val inspection = inspect(path)
        archive("replacement garbage".toByteArray())
        rejected(inspection, BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
    }

    @Test
    fun `corrupt settings CRC is rejected even when its JSON still parses`() {
        val path = archive(Files.readAllBytes(schema(9)))
        val bytes = Files.readAllBytes(path)
        val marker = "{\"restored\":true}".toByteArray()
        val offset = bytes.indices.first { index -> index + marker.size <= bytes.size && bytes.copyOfRange(index, index + marker.size).contentEquals(marker) }
        bytes[offset + 3] = 'X'.code.toByte()
        Files.write(path, bytes)
        rejected(inspect(path), BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
    }

    @Test
    fun `duplicate critical entries are rejected`() {
        for ((name, alias) in listOf("newpipe.db" to "newpipe.dx", "preferences.json" to "preferences.js0n")) {
            val path = archive(Files.readAllBytes(schema(9)))
            val bytes = Files.readAllBytes(path)
            val rewritten = directory.resolve("duplicates.zip")
            ZipOutputStream(Files.newOutputStream(rewritten)).use { zip ->
                java.util.zip.ZipInputStream(bytes.inputStream()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        entry(zip, entry.name, input.readBytes())
                    }
                }
                entry(zip, alias, "{}".toByteArray())
            }
            val duplicateBytes = Files.readAllBytes(rewritten)
            val old = alias.toByteArray()
            for (i in 0..duplicateBytes.size - old.size) {
                if (duplicateBytes.copyOfRange(i, i + old.size).contentEquals(old)) name.toByteArray().copyInto(duplicateBytes, i)
            }
            Files.write(rewritten, duplicateBytes)
            rejected(inspect(rewritten), BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
        }
    }

    @Test
    fun `supported older schemas migrate on the private copy before publication`() {
        for (version in 1..8) {
            val candidate = schema(version)
            val sourceBytes = Files.readAllBytes(candidate)
            val path = archive(sourceBytes)
            val archiveBytes = Files.readAllBytes(path)
            val inspection = inspect(path)
            backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_ONLY).test().assertComplete()
            assertArrayEquals(originalDatabase, Files.readAllBytes(database))
            assertArrayEquals(archiveBytes, Files.readAllBytes(path))
            val pending = PendingDatabaseRestore.pendingDatabase(database)
            assertDatabase(pending, Migrations.DB_VER_9, "restored")
            assertEquals(null, activate())
            originalDatabase = Files.readAllBytes(database)
            assertCleaned()
        }
    }

    @Test
    fun `current backups retain data and honor both preference choices`() {
        for (choice in BackupRestore.RestoreChoice.values()) {
            assertTrue(preferences.edit().clear().putString("original", "settings").commit())
            val path = archive(Files.readAllBytes(schema(9)))
            backups.restore(inspect(path), choice).test().assertComplete()
            assertArrayEquals(originalDatabase, Files.readAllBytes(database))
            assertEquals(mapOf("original" to "settings"), preferences.all)
            assertEquals(null, activate())
            assertEquals(Uri.fromFile(path.toFile()).toString(), preferences.getString("location", null))
            assertEquals(choice == BackupRestore.RestoreChoice.DATABASE_ONLY, preferences.contains("original"))
            assertEquals(choice == BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS, preferences.getBoolean("restored", false))
            assertDatabase(database, 9, "restored")
            originalDatabase = Files.readAllBytes(database)
            assertCleaned()
        }
    }

    @Test
    fun `directories cannot stand in for critical backup entries`() {
        for (name in listOf("newpipe.db", "preferences.json", "newpipe.settings")) {
            val path = directory.resolve("directory.zip")
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                entry(zip, "$name/", byteArrayOf())
            }
            val inspection = inspect(path)
            assertFalse("Directory alias was offered: $name", inspection.readable())
            rejected(inspection, BackupRestore.RestoreChoice.DATABASE_ONLY)
        }
    }

    @Test
    fun `database CRC in central directory must agree with the extracted bytes`() {
        val path = archive(Files.readAllBytes(schema(9)))
        val bytes = Files.readAllBytes(path)
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val offset = bytes.indices.first { i -> i + 4 <= bytes.size && bytes.copyOfRange(i, i + 4).contentEquals(signature) }
        bytes[offset + 16] = (bytes[offset + 16].toInt() xor 1).toByte()
        Files.write(path, bytes)
        rejected(inspect(path), BackupRestore.RestoreChoice.DATABASE_ONLY)
    }

    @Test
    fun `truncated SQLite pages are rejected without touching live files`() {
        val bytes = Files.readAllBytes(schema(9))
        rejected(inspect(archive(bytes.copyOf(bytes.size - 512))), BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
    }

    @Test
    fun `partial migration failure cleans journals and leaves preferences unchanged`() {
        val path = schema(8)
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE remote_playlists")
        }
        rejected(inspect(archive(Files.readAllBytes(path))), BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
    }

    @Test
    fun `database only restore ignores malformed settings`() {
        val path = archive(Files.readAllBytes(schema(9)), "{broken JSON")
        backups.restore(inspect(path), BackupRestore.RestoreChoice.DATABASE_ONLY).test().assertComplete()
        assertEquals("settings", preferences.getString("original", null))
        assertDatabase(PendingDatabaseRestore.pendingDatabase(database), 9, "restored")
        assertCleaned()
    }

    @Test
    fun `valid legacy preferences still restore with a real database`() {
        val path = directory.resolve("legacy.zip")
        val settings = ByteArrayOutputStream().also { buffer ->
            ObjectOutputStream(buffer).use { it.writeObject(hashMapOf("restored" to true)) }
        }.toByteArray()
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entry(zip, "newpipe.db", Files.readAllBytes(schema(9)))
            entry(zip, "newpipe.settings", settings)
        }
        val inspection = inspect(path)
        assertEquals(BackupRestore.PreferenceFormat.LEGACY_SERIALIZED, inspection.preferenceFormat())
        backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS).test().assertComplete()
        assertEquals("settings", preferences.getString("original", null))
        assertDatabase(PendingDatabaseRestore.pendingDatabase(database), 9, "restored")
        assertEquals(null, activate())
        assertTrue(preferences.getBoolean("restored", false))
        assertFalse(preferences.contains("original"))
        assertCleaned()
    }

    @Test
    fun `prepared restore can be closed repeatedly before and after publication`() {
        val path = archive(Files.readAllBytes(schema(9)))
        val file = StoredFileHelper(context, Uri.fromFile(path.toFile()), "application/zip")
        val abandoned = archives.prepareRestore(file, null)
        abandoned.close()
        abandoned.close()
        assertFalse(PendingDatabaseRestore.hasPending(database))
        assertCleaned()
        val published = archives.prepareRestore(file, null)
        published.publish()
        published.close()
        published.close()
        assertTrue(PendingDatabaseRestore.hasPending(database))
        assertDatabase(PendingDatabaseRestore.pendingDatabase(database), 9, "restored")
        assertCleaned()
    }

    private fun activate() = PendingDatabaseRestore.install(
        database,
        PendingDatabaseRestore.preferenceStore(preferences),
        BackupDatabaseValidator(context)
    )

    private fun assertDatabase(path: Path, version: Int, search: String) {
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(version, db.version)
            assertTrue(db.isDatabaseIntegrityOk)
            db.rawQuery("SELECT search FROM search_history", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(search, it.getString(0))
                assertFalse(it.moveToNext())
            }
        }
    }
}
