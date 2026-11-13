/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.ZipException

/** Bounds ZIP metadata before ZipFile allocates its central directory. Payload size is unrelated. */
internal object BackupZipMetadata {
    fun check(path: Path, maxBytes: Long, maxEntries: Long = 4096) {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val length = file.length()
            if (length < 22) throw ZipException("Truncated backup ZIP directory")
            val tailSize = minOf(length, 65535L + 22).toInt()
            val tailStart = length - tailSize
            val tail = ByteArray(tailSize)
            file.seek(tailStart)
            file.readFully(tail)
            val ends = (0..tailSize - 22).filter { position ->
                u32(tail, position) == 0x06054b50L && position + 22 + u16(tail, position + 20) == tailSize
            }
            if (ends.size != 1) throw ZipException("Missing or ambiguous backup ZIP directory")
            val end = ends.single()
            // ZipFile tolerates some trailing bytes and may prefer a later fake end record
            // even when that record's comment length does not reach EOF. Do not let it pick
            // different allocation metadata hidden inside the validated record's comment.
            if ((end + 1..tailSize - 22).any { u32(tail, it) == 0x06054b50L }) {
                throw ZipException("Ambiguous backup ZIP comment")
            }
            val endOffset = tailStart + end
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0) throw ZipException("Multi-disk backups are not supported")
            var entries = u16(tail, end + 10).toLong()
            var directorySize = u32(tail, end + 12)
            var directoryOffset = u32(tail, end + 16)
            var directoryEnd = endOffset
            val locator = if (endOffset >= 20) read(file, endOffset - 20, 20) else ByteArray(0)
            val hasZip64 = locator.isNotEmpty() && u32(locator, 0) == 0x07064b50L
            if (hasZip64) {
                if (u32(locator, 4) != 0L || u32(locator, 16) != 1L) throw ZipException("Multi-disk backups are not supported")
                val zip64Offset = i64(locator, 8)
                range(zip64Offset, 56, endOffset - 20)
                val record = read(file, zip64Offset, 56)
                if (u32(record, 0) != 0x06064b50L) throw ZipException("Invalid ZIP64 directory")
                val recordSize = i64(record, 4)
                if (recordSize < 44) throw ZipException("Truncated ZIP64 directory")
                if (recordSize > maxBytes || recordSize > Long.MAX_VALUE - 12) {
                    throw BackupResourceException("Backup ZIP metadata is too large")
                }
                range(zip64Offset, recordSize + 12, endOffset - 20)
                if (zip64Offset + recordSize + 12 != endOffset - 20) throw ZipException("Truncated ZIP64 directory")
                if (u32(record, 16) != 0L || u32(record, 20) != 0L) throw ZipException("Multi-disk backups are not supported")
                val zip64Entries = i64(record, 32)
                val zip64Size = i64(record, 40)
                val zip64DirectoryOffset = i64(record, 48)
                // Readers may use ordinary fields unless they contain ZIP64 sentinels.
                // Reject disagreement instead of validating one size and letting ZipFile use another.
                if ((entries != 65535L && entries != zip64Entries) ||
                    (u16(tail, end + 8) != 65535 && u16(tail, end + 8).toLong() != zip64Entries) ||
                    (directorySize != 0xffffffffL && directorySize != zip64Size) ||
                    (directoryOffset != 0xffffffffL && directoryOffset != zip64DirectoryOffset)
                ) {
                    throw ZipException("Inconsistent ZIP64 directory fields")
                }
                entries = zip64Entries
                if (entries != i64(record, 24)) throw ZipException("Inconsistent ZIP64 entry count")
                directorySize = zip64Size
                directoryOffset = zip64DirectoryOffset
                directoryEnd = zip64Offset
            } else if (entries == 65535L || directorySize == 0xffffffffL || directoryOffset == 0xffffffffL) {
                throw ZipException("Missing ZIP64 directory")
            } else if (entries != u16(tail, end + 8).toLong()) {
                throw ZipException("Inconsistent ZIP entry count")
            }
            if (entries < 0 || directorySize < 0) throw ZipException("Invalid backup ZIP directory size")
            if (entries > maxEntries || directorySize > maxBytes) throw BackupResourceException("Backup ZIP metadata is too large")
            range(directoryOffset, directorySize, directoryEnd)
            if (directoryOffset + directorySize != directoryEnd) throw ZipException("Invalid backup ZIP directory position")
            var position = directoryOffset
            repeat(entries.toInt()) {
                range(position, 46, directoryEnd)
                val header = read(file, position, 46)
                if (u32(header, 0) != 0x02014b50L || u16(header, 34) != 0) throw ZipException("Invalid backup ZIP entry")
                val entrySize = 46L + u16(header, 28) + u16(header, 30) + u16(header, 32)
                range(position, entrySize, directoryEnd)
                position += entrySize
            }
            if (position != directoryEnd) throw ZipException("Inconsistent backup ZIP directory size")
        }
    }

    private fun range(offset: Long, size: Long, end: Long) {
        if (offset < 0 || size < 0 || size > end || offset > end - size) throw ZipException("Truncated backup ZIP directory")
    }

    private fun read(file: RandomAccessFile, offset: Long, size: Int): ByteArray = ByteArray(size).also {
        file.seek(offset)
        file.readFully(it)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long = u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
    private fun i64(bytes: ByteArray, offset: Int): Long = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
