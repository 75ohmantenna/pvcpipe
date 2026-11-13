/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Resource failures are not evidence that the selected archive is malformed. */
internal class BackupResourceException(message: String) : IOException(message)

/** Bounds temporary bytes by available storage, not by an arbitrary maximum library size. */
internal class BackupCopyPolicy(
    private val usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
    val settingsLimit: Long = minOf(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 32),
    val zipMetadataLimit: Long = minOf(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 32)
) {
    fun budget(directory: Path): BackupCopyBudget = BackupCopyBudget(directory, usableSpace)
}

/** One budget covers the archive and all extracted files that coexist in its working directory. */
internal class BackupCopyBudget(
    private val directory: Path,
    private val usableSpace: (Path) -> Long
) {
    private val available = usableSpace(directory).coerceAtLeast(0)
    private val reserve = minOf(32L * 1024 * 1024, maxOf(64L * 1024, available / 20))
    private var remaining = (available - reserve).coerceAtLeast(0)

    fun copy(input: InputStream, destination: Path, limit: Long = Long.MAX_VALUE, expected: Long = -1): Long {
        interrupted()
        if (expected > limit) throw BackupResourceException("Backup settings are too large for settings restore; restore the database only")
        if (expected > remaining) throw BackupResourceException("Not enough free storage to prepare the backup")
        var count = 0L
        var sinceSpaceCheck = 1024 * 1024
        val buffer = ByteArray(64 * 1024)
        Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            while (true) {
                interrupted()
                var size = input.read(buffer)
                if (size < 0) break
                if (size == 0) {
                    val value = input.read()
                    if (value < 0) break
                    buffer[0] = value.toByte()
                    size = 1
                }
                interrupted()
                if (size.toLong() > limit - count) {
                    throw BackupResourceException("Backup settings are too large for settings restore; restore the database only")
                }
                if (size.toLong() > remaining) throw BackupResourceException("Not enough free storage to prepare the backup")
                if (sinceSpaceCheck >= 1024 * 1024) {
                    if (size.toLong() > usableSpace(directory).coerceAtLeast(0) - reserve) {
                        throw BackupResourceException("Not enough free storage to prepare the backup")
                    }
                    sinceSpaceCheck = 0
                }
                output.write(buffer, 0, size)
                count += size
                remaining -= size
                sinceSpaceCheck += size
            }
        }
        interrupted()
        return count
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Backup preparation was interrupted")
    }
}
