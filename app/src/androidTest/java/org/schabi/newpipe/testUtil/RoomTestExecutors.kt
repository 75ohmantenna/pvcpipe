/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.testUtil

import android.os.SystemClock
import androidx.room.RoomDatabase
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Runs a test database's background work on an executor the test can watch, so the database is
 * only closed once that work has finished.
 *
 * Room refreshes its invalidation tracker asynchronously after a write. Closing an in-memory
 * database while such a refresh is still queued makes the refresh throw on Room's own thread,
 * which takes the whole instrumentation process down and fails whichever test runs next.
 */
class RoomTestExecutors {
    private val executor = ThreadPoolExecutor(
        THREADS,
        THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>()
    )

    fun <T : RoomDatabase> applyTo(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> = builder.setQueryExecutor(executor)

    /** Waits until Room has nothing running or queued, then closes [database]. */
    fun closeWhenIdle(database: RoomDatabase) {
        val deadline = SystemClock.elapsedRealtime() + IDLE_TIMEOUT_MS
        var quietChecks = 0
        // Finished work can queue follow-up work, so require several quiet checks in a row.
        while (quietChecks < QUIET_CHECKS && SystemClock.elapsedRealtime() < deadline) {
            quietChecks = if (executor.activeCount == 0 && executor.queue.isEmpty()) quietChecks + 1 else 0
            Thread.sleep(CHECK_INTERVAL_MS)
        }
        database.close()
        executor.shutdown()
    }

    private companion object {
        // Same size as the IO pool Room uses by default.
        const val THREADS = 4
        const val QUIET_CHECKS = 3
        const val CHECK_INTERVAL_MS = 20L
        const val IDLE_TIMEOUT_MS = 5_000L
    }
}
