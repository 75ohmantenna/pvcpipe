/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import com.grack.nanojson.JsonParser
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.schabi.newpipe.settings.export.BackupCopyPolicy
import org.schabi.newpipe.settings.export.BackupResourceException
import org.schabi.newpipe.settings.export.ImportExportManager
import org.schabi.newpipe.settings.export.PreferencesObjectInputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.io.FileStream

class BackupResourceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(database: ByteArray, settings: String? = null): Path {
        val path = temporary.root.toPath().resolve("source.zip")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("newpipe.db"))
            zip.write(database)
            zip.closeEntry()
            if (settings != null) {
                zip.putNextEntry(ZipEntry("preferences.json"))
                zip.write(settings.toByteArray())
                zip.closeEntry()
            }
        }
        return path
    }

    private fun clean(environment: BackupTestEnvironment) {
        assertEquals("live database", Files.readString(environment.database))
        assertEquals("live WAL", Files.readString(environment.database.resolveSibling("newpipe.db-wal")))
        assertEquals(mapOf("original" to "settings"), environment.values)
        Files.list(temporary.root.toPath()).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith("backup-") })
        }
    }

    @Test
    fun `resource exhaustion during inspection is not mislabeled as malformed archive`() {
        val environment = BackupTestEnvironment(temporary.root.toPath())
        val source = temporary.root.toPath().resolve("oversized.zip")
        Files.write(source, ByteArray(256 * 1024))
        val archives = ImportExportManager(environment.locator, { }, BackupCopyPolicy({ 128L * 1024 }))
        assertThrows(BackupResourceException::class.java) { archives.inspect(environment.document(environment.documentUri(source))) }
        clean(environment)
    }

    @Test
    fun `compressed database expansion stops before validation and cleans private data`() {
        val environment = BackupTestEnvironment(temporary.root.toPath())
        val source = archive(ByteArray(2 * 1024 * 1024))
        var validated = false
        val archives = ImportExportManager(environment.locator, { validated = true }, BackupCopyPolicy({ 1024L * 1024 }))
        assertThrows(BackupResourceException::class.java) { archives.prepareRestore(environment.document(environment.documentUri(source)), null) }
        assertFalse(validated)
        clean(environment)
    }

    @Test
    fun `oversized settings can still be restored as database only`() {
        val environment = BackupTestEnvironment(temporary.root.toPath())
        val source = archive("valid test database".toByteArray(), "{\"large\":\"${"a".repeat(256)}\"}")
        val archives = ImportExportManager(environment.locator, { }, BackupCopyPolicy({ 1024L * 1024 }, settingsLimit = 32))
        val document = environment.document(environment.documentUri(source))
        assertThrows(BackupResourceException::class.java) { archives.prepareRestore(document, "preferences.json") }
        archives.prepareRestore(document, null).use { assertEquals(null, it.preferences) }
        clean(environment)
    }

    @Test
    fun `interrupted preparation preserves live state and cleans working directory`() {
        val environment = BackupTestEnvironment(temporary.root.toPath())
        val source = archive(byteArrayOf(1))
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) {
                environment.archives.prepareRestore(environment.document(environment.documentUri(source)), null)
            }
        } finally {
            Thread.interrupted()
        }
        clean(environment)
    }

    @Test
    fun `snapshot failure and insufficient space preserve existing export destination`() {
        val environment = BackupTestEnvironment(temporary.root.toPath())
        val destination = temporary.root.toPath().resolve("existing.zip")
        Files.writeString(destination, "previous backup")
        val document = mock(StoredFileHelper::class.java)
        assertThrows(IOException::class.java) {
            environment.archives.exportDatabase(environment.preferences, document) { throw IOException("busy database") }
        }
        val limited = ImportExportManager(environment.locator, { }, BackupCopyPolicy({ 0 }))
        assertThrows(BackupResourceException::class.java) { limited.exportDatabase(environment.preferences, document) { it.copy() } }
        verify(document, never()).openAndTruncateStream()
        assertEquals("previous backup", Files.readString(destination))
        clean(environment)
    }

    @Test
    fun `JSON and legacy exports use one settings map and detached sets`() {
        val environment = BackupTestEnvironment(temporary.root.toPath())
        val values = hashSetOf("before")
        val preferences = mock(android.content.SharedPreferences::class.java)
        `when`(preferences.all).thenReturn(hashMapOf("set" to values), hashMapOf("changed" to true))
        val output = temporary.root.toPath().resolve("preferences.zip")
        val stream = object : FileStream(output.toFile()) {
            override fun write(buffer: ByteArray, offset: Int, count: Int) {
                values.add("after")
                super.write(buffer, offset, count)
            }
        }
        val document = mock(StoredFileHelper::class.java)
        `when`(document.openAndTruncateStream()).thenReturn(stream)
        environment.archives.exportDatabase(preferences, document) { it.copy() }
        verify(preferences).all
        ZipFile(output.toFile()).use { zip ->
            val legacy = zip.getInputStream(zip.getEntry("newpipe.settings")).use { PreferencesObjectInputStream(it).use { stream -> stream.readObject() } }
            assertEquals(hashMapOf("set" to hashSetOf("before")), legacy)
            val json = zip.getInputStream(zip.getEntry("preferences.json")).use { JsonParser.`object`().from(it) }
            assertEquals(listOf("before"), json.getArray("set"))
        }
        clean(environment)
    }
}
