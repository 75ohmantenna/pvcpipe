/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupZipMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun zip(entries: Int = 1, name: String = "newpipe.db", comment: String? = null): Path {
        val path = temporary.newFile().toPath()
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            if (comment != null) output.setComment(comment)
            repeat(entries) { index ->
                output.putNextEntry(ZipEntry(if (entries == 1) name else "$index-$name"))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `ordinary directory and normal comments are supported`() {
        val path = zip(comment = "backup metadata")
        BackupZipMetadata.check(path, 4096)
        ZipFile(path.toFile()).use { assertEquals(1, it.size()) }
    }

    @Test
    fun `entry and directory byte caps are independent of payload storage`() {
        assertThrows(BackupResourceException::class.java) { BackupZipMetadata.check(zip(5), 4096, maxEntries = 4) }
        assertThrows(BackupResourceException::class.java) { BackupZipMetadata.check(zip(name = "n".repeat(1024)), 256) }
    }

    private fun zip64(path: Path): Path {
        val original = Files.readAllBytes(path)
        val end = original.size - 22
        val old = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val count = old.getShort(end + 10).toLong() and 0xffff
        val directorySize = old.getInt(end + 12).toLong() and 0xffffffffL
        val directoryOffset = old.getInt(end + 16).toLong() and 0xffffffffL
        val result = ByteBuffer.allocate(original.size + 76).order(ByteOrder.LITTLE_ENDIAN)
        result.put(original, 0, end)
        result.putInt(0x06064b50).putLong(44).putShort(45).putShort(45)
        result.putInt(0).putInt(0).putLong(count).putLong(count).putLong(directorySize).putLong(directoryOffset)
        result.putInt(0x07064b50).putInt(0).putLong(end.toLong()).putInt(1)
        result.putInt(0x06054b50).putShort(0).putShort(0).putShort(-1).putShort(-1).putInt(-1).putInt(-1).putShort(0)
        Files.write(path, result.array())
        return path
    }

    @Test
    fun `valid ZIP64 directory is accepted and ZipFile reads its payload`() {
        val path = zip64(zip())
        BackupZipMetadata.check(path, 4096)
        ZipFile(path.toFile()).use { file ->
            assertEquals(1, file.size())
            file.getInputStream(file.getEntry("newpipe.db")).use { assertEquals(listOf<Byte>(1, 2, 3), it.readBytes().toList()) }
        }
    }

    @Test
    fun `large payload with small ZIP64 metadata is not rejected by metadata cap`() {
        val path = temporary.newFile().toPath()
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            output.putNextEntry(ZipEntry("newpipe.db"))
            val block = ByteArray(64 * 1024)
            repeat(64) { output.write(block) }
            output.closeEntry()
        }
        zip64(path)
        BackupZipMetadata.check(path, 512)
        ZipFile(path.toFile()).use { assertEquals(4L * 1024 * 1024, it.getEntry("newpipe.db").size) }
    }

    @Test
    fun `forged negative ZIP64 offsets and oversized counts fail before ZipFile`() {
        val path = zip64(zip())
        val bytes = Files.readAllBytes(path)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(bytes.size - 22 - 20 + 8, -1)
        Files.write(path, bytes)
        assertThrows(ZipException::class.java) { BackupZipMetadata.check(path, 4096) }
        val counted = zip64(zip())
        val oversized = Files.readAllBytes(counted)
        val record = oversized.size - 22 - 20 - 56
        ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putLong(record + 24, 1_000_000).putLong(record + 32, 1_000_000)
        Files.write(counted, oversized)
        assertThrows(BackupResourceException::class.java) { BackupZipMetadata.check(counted, 4096) }
    }

    @Test
    fun `ordinary and ZIP64 metadata cannot disagree about allocation size`() {
        val path = zip64(zip())
        val bytes = Files.readAllBytes(path)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 22 + 12, Int.MAX_VALUE)
        Files.write(path, bytes)
        assertThrows(ZipException::class.java) { BackupZipMetadata.check(path, 4096) }
    }

    @Test
    fun `truncation and overflowing directory ranges are rejected`() {
        val path = zip()
        val bytes = Files.readAllBytes(path)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 22 + 16, Int.MAX_VALUE)
        Files.write(path, bytes)
        assertThrows(ZipException::class.java) { BackupZipMetadata.check(path, 4096) }
        Files.write(path, bytes.copyOf(bytes.size - 1))
        assertThrows(ZipException::class.java) { BackupZipMetadata.check(path, 4096) }
    }

    @Test
    fun `fake end record with wrong comment length cannot override validated metadata`() {
        val path = zip()
        val bytes = Files.readAllBytes(path)
        val result = ByteBuffer.allocate(bytes.size + 23).order(ByteOrder.LITTLE_ENDIAN)
        result.put(bytes)
        result.putShort(bytes.size - 2, 23)
        result.putInt(0x06054b50).putShort(0).putShort(0).putShort(1).putShort(1).putInt(Int.MAX_VALUE).putInt(0).putShort(0).put(0)
        Files.write(path, result.array())
        assertThrows(ZipException::class.java) { BackupZipMetadata.check(path, 4096) }
    }

    @Test
    fun `ambiguous end records hidden in a ZIP comment are rejected`() {
        val path = zip()
        val bytes = Files.readAllBytes(path)
        val result = ByteBuffer.allocate(bytes.size + 22).order(ByteOrder.LITTLE_ENDIAN)
        result.put(bytes)
        result.putShort(bytes.size - 2, 22)
        result.putInt(0x06054b50).putShort(0).putShort(0).putShort(0).putShort(0).putInt(0).putInt(bytes.size).putShort(0)
        Files.write(path, result.array())
        assertThrows(ZipException::class.java) { BackupZipMetadata.check(path, 4096) }
    }
}
