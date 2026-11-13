/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.IOException
import java.io.InterruptedIOException

/** Holds database writers only while making a private local snapshot, never during ZIP/provider IO. */
object BackupDatabaseSnapshot {
    @JvmStatic
    @Throws(IOException::class)
    fun copy(database: SupportSQLiteDatabase, copy: ImportExportManager.SnapshotCopy) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Backup preparation was interrupted")
        database.beginTransaction()
        try {
            if (database.isWriteAheadLoggingEnabled) {
                // PASSIVE can backfill while a different connection owns the writer lock.
                // Explicitly retain WAL mode; do not let opening this connection change it.
                SQLiteDatabase.openDatabase(
                    database.path ?: throw IOException("Database path is unavailable"),
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                ).use { checkpoint ->
                    checkpoint.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { cursor ->
                        if (!cursor.moveToFirst() || cursor.getInt(0) != 0 || cursor.getLong(1) != cursor.getLong(2)) {
                            throw IOException("Database is busy; retry the backup after other library work finishes")
                        }
                    }
                }
            }
            // No new writes can commit. Any concurrent checkpoint can only backfill the same
            // already-copied frames, so the main file represents one stable database state.
            copy.copy()
        } finally {
            // This transaction changes no data. Release the gate before compression/output.
            database.endTransaction()
        }
    }
}
