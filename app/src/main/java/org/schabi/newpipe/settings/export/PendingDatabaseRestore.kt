/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.io.IOException
import java.nio.file.Path

/** Coordinates queued restore intents and pre-provider startup recovery. */
object PendingDatabaseRestore {
    @JvmStatic
    fun hasPending(database: Path): Boolean = RestoreTransaction.hasPending(database)

    @JvmStatic
    fun pendingDatabase(database: Path): Path = RestoreTransaction.pendingDatabase(database)

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun publish(database: Path, prepared: Path, values: Map<String, *> = emptyMap<String, Any>(), replaceAll: Boolean = false) {
        RestoreTransaction(database, null, null, RestoreTransaction.FileSystem()).publish(prepared, values, replaceAll)
    }

    @JvmStatic
    fun preferenceStore(preferences: SharedPreferences): RestoreTransaction.Preferences = object : RestoreTransaction.Preferences {
        override fun read(): Map<String, *> = preferences.all

        override fun replace(values: Map<String, Any>) = ImportExportManager.replacePreferences(preferences, values)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun install(database: Path, preferences: RestoreTransaction.Preferences, validator: RestoreTransaction.Validator): IOException? = RestoreTransaction(database, preferences, validator, RestoreTransaction.FileSystem()).recover()

    /** The base context is usable before Application.applicationContext has been assigned. */
    @JvmStatic
    @Throws(IOException::class)
    fun install(context: Context): IOException? = install(
        BackupFileLocator(context).db,
        preferenceStore(PreferenceManager.getDefaultSharedPreferences(context)),
        BackupDatabaseValidator(context)::validate
    )
}
