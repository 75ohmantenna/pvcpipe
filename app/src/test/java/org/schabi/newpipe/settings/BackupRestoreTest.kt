package org.schabi.newpipe.settings

import io.reactivex.rxjava3.schedulers.TestScheduler
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.settings.export.PendingDatabaseRestore

class BackupRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val io = TestScheduler()
    private val main = TestScheduler()
    private lateinit var environment: BackupTestEnvironment
    private lateinit var backups: BackupRestore
    private lateinit var operations: BackupOperations

    @Before
    fun setup() {
        environment = BackupTestEnvironment(temporary.root.toPath())
        operations = BackupOperations(io)
        backups = module()
    }

    private fun module() = BackupRestore(
        operations,
        environment.archives,
        environment.preferences,
        "location",
        environment,
        main
    )

    private fun inspection(resource: String): BackupRestore.Inspection {
        val request = backups.inspect(environment.source(resource)).test()
        io.triggerActions()
        request.assertComplete()
        return request.values().single()
    }

    @Test
    fun `accepted restore survives detach and reobservation without repeating restart`() {
        val inspection = inspection("settings/db_ser_json.zip")
        assertEquals(BackupRestore.PreferenceFormat.JSON, inspection.preferenceFormat())
        val restore = backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
        val observer = restore.test()
        observer.dispose()
        io.triggerActions()
        assertEquals("live database", Files.readString(environment.database))
        assertTrue(Files.exists(temporary.root.toPath().resolve("newpipe.db-wal")))
        assertEquals(0, environment.restarts)
        main.triggerActions()
        restore.test().assertComplete()
        assertEquals(1, environment.restarts)
        assertEquals(1, environment.cleanups)
        assertFalse(environment.values.containsKey("original"))
        PendingDatabaseRestore.install(environment.database)
        assertTrue(Files.size(environment.database) > 0)
        assertFalse(Files.exists(temporary.root.toPath().resolve("newpipe.db-wal")))
    }

    @Test
    fun `unsubscribed requests do nothing and requests across modules share admission`() {
        val inspection = inspection("settings/db_ser_json.zip")
        backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_ONLY)
        assertEquals(0, environment.restarts)
        val accepted = backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_ONLY).test()
        val rejected = module().inspect(environment.documents.keys.single()).test()
        rejected.assertError(IllegalStateException::class.java)
        accepted.dispose()
        io.triggerActions()
        main.triggerActions()
        assertEquals(1, environment.restarts)
        assertEquals("settings", environment.values["original"])
    }

    @Test
    fun `inspection completion can immediately submit restore`() {
        val request = backups.inspect(environment.source("settings/db_noser_nojson.zip"))
            .flatMapCompletable {
                backups.restore(it, BackupRestore.RestoreChoice.DATABASE_ONLY)
            }.test()
        io.triggerActions()
        main.triggerActions()
        request.assertComplete()
        assertEquals(1, environment.restarts)
    }

    @Test
    fun `preparation failure preserves preferences and reports once`() {
        val inspection = inspection("settings/nodb_noser_json.zip")
        val restore = backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
        restore.test().dispose()
        io.triggerActions()
        main.triggerActions()
        restore.test().assertError(java.io.IOException::class.java)
        assertEquals("settings", environment.values["original"])
        assertEquals(1, environment.errors.size)
        assertEquals(0, environment.restarts)
        assertEquals("live database", Files.readString(environment.database))
    }

    @Test
    fun `export truncates output and only successful export remembers location`() {
        val path = temporary.root.toPath().resolve("export.zip")
        Files.writeString(path, "stale trailing data".repeat(10000))
        val destination = environment.documentUri(path)
        val export = backups.exportTo(destination)
        export.test().dispose()
        io.triggerActions()
        export.test().assertComplete()
        assertEquals(1, environment.checkpoints)
        assertEquals(destination.toString(), environment.values["location"])
        ZipFile(path.toFile()).use { assertEquals(3, it.size()) }
        assertTrue(Files.size(path) < 10000)
        environment.failCheckpoint = true
        backups.exportTo(destination).test().also {
            io.triggerActions()
            it.assertError(java.io.IOException::class.java)
        }
    }

    @Test
    fun `legacy preferences are offered but disallowed objects fail without restarting`() {
        val inspection = inspection("settings/db_vulnser_nojson.zip")
        assertEquals(BackupRestore.PreferenceFormat.LEGACY_SERIALIZED, inspection.preferenceFormat())
        backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS).test().also {
            io.triggerActions()
            main.triggerActions()
            it.assertError(ClassNotFoundException::class.java)
        }
        assertEquals("settings", environment.values["original"])
        assertEquals(0, environment.restarts)
    }

    @Test
    fun `rejected restore replays one notification and new requests can retry`() {
        val inspection = inspection("settings/db_ser_json.zip")
        val destination = environment.documentUri(temporary.root.toPath().resolve("busy.zip"))
        val export = backups.exportTo(destination).test()
        val rejected = backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_ONLY)
        rejected.test().dispose()
        main.triggerActions()
        assertEquals(1, environment.errors.size)
        io.triggerActions()
        export.assertComplete()
        rejected.test().assertError(IllegalStateException::class.java)
        assertEquals(1, environment.errors.size)
        val retry = backups.inspect(environment.documents.keys.first()).test()
        io.triggerActions()
        retry.assertComplete()
        assertEquals(0, environment.restarts)
    }

    @Test
    fun `valid JSON takes precedence over unsafe serialized preferences`() {
        val inspection = inspection("settings/db_vulnser_json.zip")
        backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS).test().also {
            io.triggerActions()
            main.triggerActions()
            it.assertComplete()
        }
        assertEquals(1, environment.restarts)
        assertTrue(environment.errors.isEmpty())
        assertFalse(environment.values.containsKey("original"))
    }
}
