package org.schabi.newpipe.settings

import io.reactivex.rxjava3.schedulers.TestScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.settings.export.PendingDatabaseRestore

class BackupRestoreFailureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val io = TestScheduler()
    private val main = TestScheduler()
    private lateinit var environment: BackupTestEnvironment
    private lateinit var backups: BackupRestore

    @Before
    fun setup() {
        environment = BackupTestEnvironment(temporary.root.toPath())
        backups = BackupRestore(
            BackupOperations(io),
            environment.archives,
            environment.preferences,
            "location",
            environment,
            main
        )
    }

    private fun inspect(uri: android.net.Uri): BackupRestore.Inspection {
        val result = backups.inspect(uri).test()
        io.triggerActions()
        result.assertComplete()
        return result.values().single()
    }

    private fun restore(inspection: BackupRestore.Inspection) = backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS).test().also {
        io.triggerActions()
        main.triggerActions()
    }

    private fun assertLiveDataUnchanged() {
        assertEquals("live database", Files.readString(environment.database))
        assertEquals("live WAL", Files.readString(temporary.root.toPath().resolve("newpipe.db-wal")))
        assertEquals("settings", environment.values["original"])
        assertFalse(environment.values.containsKey("location"))
    }

    private fun assertPreparationCleaned() {
        Files.list(temporary.root.toPath()).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith("backup-") })
        }
    }

    private fun archive(path: Path, json: String, database: ByteArray? = null) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val bytes = database ?: javaClass.classLoader!!
                .getResourceAsStream("settings/newpipe.db")!!.use { it.readBytes() }
            zip.putNextEntry(
                ZipEntry("newpipe.db").also {
                    it.method = ZipEntry.STORED
                    it.size = bytes.size.toLong()
                    it.crc = java.util.zip.CRC32().also { crc -> crc.update(bytes) }.value
                }
            )
            zip.write(bytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("preferences.json"))
            zip.write(json.toByteArray())
            zip.closeEntry()
        }
    }

    @Test
    fun `malformed preferences fail preparation without changing live data`() {
        val path = temporary.root.toPath().resolve("malformed.zip")
        archive(path, "{broken json")
        restore(inspect(environment.documentUri(path))).assertError { true }
        assertLiveDataUnchanged()
        assertPreparationCleaned()
        assertEquals(0, environment.restarts)
    }

    @Test
    fun `failed preference commit rolls back including in-memory values`() {
        val inspection = inspect(environment.source("settings/db_ser_json.zip"))
        environment.failedCommits = 1
        restore(inspection).assertError(java.io.IOException::class.java)
        assertLiveDataUnchanged()
        assertFalse(PendingDatabaseRestore.hasPending(environment.database))
        assertPreparationCleaned()
    }

    @Test
    fun `failed rollback is explicit and later work retries recovery first`() {
        val uri = environment.source("settings/db_ser_json.zip")
        val inspection = inspect(uri)
        environment.failedCommits = 2
        restore(inspection).assertError(BackupRestore.RestoreRecoveryRequiredException::class.java)
        assertEquals(0, environment.restarts)
        environment.values["original"] = "unrecovered"
        environment.failedCommits = 1
        backups.inspect(uri).test().also {
            io.triggerActions()
            it.assertError(BackupRestore.RestoreRecoveryRequiredException::class.java)
        }
        backups.inspect(uri).test().also {
            io.triggerActions()
            it.assertComplete()
        }
        assertLiveDataUnchanged()
        assertPreparationCleaned()
    }

    @Test
    fun `publication failure preserves competing pending restore and rolls back copied sets`() {
        val originalSet = mutableSetOf("before")
        environment.values["set"] = originalSet
        val inspection = inspect(environment.source("settings/db_ser_json.zip"))
        val pending = temporary.root.toPath().resolve("newpipe.db.restore")
        environment.afterPreferenceCommit = {
            originalSet.add("later mutation")
            Files.createDirectory(pending)
        }
        restore(inspection).assertError(java.nio.file.FileAlreadyExistsException::class.java)
        assertLiveDataUnchanged()
        assertEquals(setOf("before"), environment.values["set"])
        assertTrue(Files.isDirectory(pending))
        assertPreparationCleaned()
    }

    @Test
    fun `existing pending restore is never replaced and preferences stay unchanged`() {
        val inspection = inspect(environment.source("settings/db_ser_json.zip"))
        val pending = temporary.root.toPath().resolve("newpipe.db.restore")
        Files.writeString(pending, "earlier restore")
        restore(inspection).assertError(java.io.IOException::class.java)
        assertEquals("earlier restore", Files.readString(pending))
        assertLiveDataUnchanged()
        assertPreparationCleaned()
    }

    @Test
    fun `document changing from JSON to legacy cannot bypass warning`() {
        val uri = environment.source("settings/db_noser_json.zip")
        val inspection = inspect(uri)
        javaClass.classLoader!!.getResourceAsStream("settings/db_ser_nojson.zip")!!.use {
            Files.copy(
                it,
                environment.documents.getValue(uri),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
        restore(inspection).assertError(java.io.IOException::class.java)
        assertLiveDataUnchanged()
        assertPreparationCleaned()
    }

    @Test
    fun `accepted restore reads source once and cleans private snapshot`() {
        val inspection = inspect(environment.source("settings/db_ser_json.zip"))
        val reads = environment.reads
        restore(inspection).assertComplete()
        assertEquals(reads + 1, environment.reads)
        assertPreparationCleaned()
        assertTrue(PendingDatabaseRestore.hasPending(environment.database))
    }

    @Test
    fun `pending activation blocks export before truncation or checkpoint`() {
        val uri = environment.source("settings/db_ser_json.zip")
        val inspection = inspect(uri)
        environment.failRestart = true
        restore(inspection).assertError(BackupRestore.RestoreCommittedException::class.java)
        val path = temporary.root.toPath().resolve("export.zip")
        Files.writeString(path, "existing backup")
        val destination = environment.documentUri(path)
        backups.exportTo(destination).test().also {
            io.triggerActions()
            it.assertError(java.io.IOException::class.java)
        }
        assertEquals("existing backup", Files.readString(path))
        assertEquals(0, environment.checkpoints)
        assertEquals(uri.toString(), environment.values["location"])
        assertTrue(PendingDatabaseRestore.hasPending(environment.database))
    }

    @Test
    fun `database CRC corruption after inspection leaves live data unchanged`() {
        val path = temporary.root.toPath().resolve("corrupt.zip")
        archive(path, "{}", "candidate database".toByteArray())
        val inspection = inspect(environment.documentUri(path))
        val bytes = Files.readAllBytes(path)
        val offset = 30 + "newpipe.db".length
        assertEquals('c'.code.toByte(), bytes[offset])
        bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
        Files.write(path, bytes)
        restore(inspection).assertError(java.util.zip.ZipException::class.java)
        assertLiveDataUnchanged()
        assertPreparationCleaned()
        assertFalse(PendingDatabaseRestore.hasPending(environment.database))
    }

    @Test
    fun `empty database fails preparation without changing live data`() {
        val path = temporary.root.toPath().resolve("empty.zip")
        archive(path, "{}", byteArrayOf())
        restore(inspect(environment.documentUri(path))).assertError(java.io.IOException::class.java)
        assertLiveDataUnchanged()
        assertPreparationCleaned()
    }

    @Test
    fun `unreadable provider stream is closed and preparation is cleaned`() {
        val inspection = inspect(environment.source("settings/db_ser_json.zip"))
        val stream = org.mockito.Mockito.mock(org.schabi.newpipe.streams.io.SharpStream::class.java)
        environment.sourceStream = stream
        restore(inspection).assertError(java.io.IOException::class.java)
        org.mockito.Mockito.verify(stream).close()
        assertLiveDataUnchanged()
        assertPreparationCleaned()
    }

    @Test
    fun `restart failure reports committed restore and preserves pending database`() {
        val inspection = inspect(environment.source("settings/db_ser_json.zip"))
        environment.failRestart = true
        restore(inspection).assertError(BackupRestore.RestoreCommittedException::class.java)
        assertTrue(PendingDatabaseRestore.hasPending(environment.database))
        assertFalse(environment.values.containsKey("original"))
        assertEquals(1, environment.errors.size)
        assertPreparationCleaned()
    }
}
