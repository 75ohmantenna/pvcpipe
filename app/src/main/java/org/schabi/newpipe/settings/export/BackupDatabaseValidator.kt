/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.Migrations

/** Opens and migrates only the private candidate, never the live database singleton. */
class BackupDatabaseValidator(private val context: Context) : ImportExportManager.DatabaseValidator {
    override fun validate(database: Path) {
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val header = ByteArray(magic.size)
        DataInputStream(Files.newInputStream(database)).use { it.readFully(header) }
        if (Files.size(database) < 100 || !header.contentEquals(magic)) {
            throw IOException("Backup database is not a SQLite database")
        }
        try {
            SQLiteDatabase.openDatabase(
                database.toString(),
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { db ->
                if (db.version < Migrations.DB_VER_1) {
                    throw IOException("Backup database version is not supported: ${db.version}")
                }
                if (!db.isDatabaseIntegrityOk) throw IOException("Backup database integrity check failed")
            }
            SQLiteDatabase.openDatabase(
                database.toString(),
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { db ->
                // A claimed identity hash is not proof of schema correctness. Removing this
                // metadata on the private copy makes Room validate and recreate it on opening.
                db.execSQL("DROP TABLE IF EXISTS room_master_table")
            }
            val room = NewPipeDatabase.createDatabase(
                context,
                database.toAbsolutePath().toString(),
                RoomDatabase.JournalMode.TRUNCATE
            )
            try {
                val db = room.openHelper.writableDatabase
                if (!db.isDatabaseIntegrityOk) throw IOException("Migrated backup database integrity check failed")
                db.query("PRAGMA foreign_key_check").use { cursor ->
                    if (cursor.moveToFirst()) throw IOException("Backup database contains foreign key violations")
                }
            } finally {
                room.close()
            }
        } catch (failure: RuntimeException) {
            throw IOException("Backup database cannot be opened or migrated", failure)
        }
    }
}
