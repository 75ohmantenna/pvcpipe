/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export

import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupCopyBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `archive and extracted payload share one byte budget regardless of declared size`() {
        val directory = temporary.root.toPath()
        val budget = BackupCopyPolicy({ 256L * 1024 }).budget(directory)
        budget.copy(ByteArrayInputStream(ByteArray(128 * 1024)), directory.resolve("archive"))
        val payload = directory.resolve("payload")
        assertThrows(BackupResourceException::class.java) {
            budget.copy(ByteArrayInputStream(ByteArray(256 * 1024)), payload, expected = 1)
        }
        assertEquals(64L * 1024, Files.size(payload))
    }

    @Test
    fun `large payload succeeds when storage permits instead of imposing library size cap`() {
        val directory = temporary.root.toPath()
        val target = directory.resolve("large")
        val budget = BackupCopyPolicy({ 8L * 1024 * 1024 }).budget(directory)
        assertEquals(4L * 1024 * 1024, budget.copy(ByteArrayInputStream(ByteArray(4 * 1024 * 1024)), target))
    }

    @Test
    fun `settings cap checks actual bytes before exceeding permitted output`() {
        val directory = temporary.root.toPath()
        val target = directory.resolve("settings")
        val budget = BackupCopyPolicy({ 8L * 1024 * 1024 }).budget(directory)
        assertThrows(BackupResourceException::class.java) {
            budget.copy(ByteArrayInputStream(ByteArray(128 * 1024)), target, limit = 64L * 1024, expected = 1)
        }
        assertEquals(64L * 1024, Files.size(target))
    }

    @Test
    fun `free space is checked again while copying`() {
        val directory = temporary.root.toPath()
        var checks = 0
        val budget = BackupCopyPolicy({ if (++checks <= 2) 8L * 1024 * 1024 else 0 }).budget(directory)
        val target = directory.resolve("changing-space")
        assertThrows(BackupResourceException::class.java) {
            budget.copy(ByteArrayInputStream(ByteArray(4 * 1024 * 1024)), target)
        }
        assertTrue(checks >= 3)
        assertEquals(1024L * 1024, Files.size(target))
    }

    @Test
    fun `interruption before and during read is preserved and stops before writing the chunk`() {
        val directory = temporary.root.toPath()
        val budget = BackupCopyPolicy({ 8L * 1024 * 1024 }).budget(directory)
        val before = directory.resolve("before")
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) { budget.copy(ByteArrayInputStream(byteArrayOf(1)), before) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(Files.exists(before))
        } finally {
            Thread.interrupted()
        }
        val during = directory.resolve("during")
        val source = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                Thread.currentThread().interrupt()
                return super.read(buffer, offset, length)
            }
        }
        try {
            assertThrows(InterruptedIOException::class.java) { budget.copy(source, during) }
            assertEquals(0, Files.size(during))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
