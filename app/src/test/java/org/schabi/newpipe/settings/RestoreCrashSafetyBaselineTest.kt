/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import io.reactivex.rxjava3.schedulers.TestScheduler
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.settings.export.PendingDatabaseRestore

class RestoreCrashSafetyBaselineTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `queued restore leaves current settings paired with the current database`() {
        val environment = BackupTestEnvironment(temporary.root.toPath())
        val io = TestScheduler()
        val main = TestScheduler()
        val backups = BackupRestore(
            BackupOperations(io),
            environment.archives,
            environment.preferences,
            "location",
            environment,
            main
        )
        val inspected = backups.inspect(environment.source("settings/db_ser_json.zip")).test()
        io.triggerActions()
        val restore = backups.restore(inspected.values().single(), BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS).test()
        io.triggerActions()
        main.triggerActions()
        restore.assertComplete()
        assertEquals(mapOf("original" to "settings"), environment.values)
        assertEquals("live database", Files.readString(environment.database))
    }

    @Test
    fun `failed database replacement preserves the live WAL`() {
        val database = temporary.root.toPath().resolve("newpipe.db")
        Files.createDirectory(database)
        Files.writeString(database.resolve("blocker"), "keep directory")
        val wal = database.resolveSibling("newpipe.db-wal")
        Files.writeString(wal, "committed live WAL")
        val prepared = temporary.root.toPath().resolve("prepared.db")
        Files.writeString(prepared, "candidate")
        PendingDatabaseRestore.publish(database, prepared)
        org.junit.Assert.assertNotNull(PendingDatabaseRestore.install(database, emptyRestorePreferences()) { })
        assertEquals("committed live WAL", Files.readString(wal))
    }
}
