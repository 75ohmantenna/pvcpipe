package org.schabi.newpipe.settings.export

import android.content.SharedPreferences
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.grack.nanojson.JsonWriter
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ZipHelper

class ImportExportManager(private val fileLocator: BackupFileLocator) {
    companion object {
        const val TAG = "ImportExportManager"

        /** Persist a complete map; failed commit may still change Android's in-memory values. */
        @JvmStatic
        fun replacePreferences(preferences: SharedPreferences, entries: Map<String, *>) {
            val editor = preferences.edit()
            editor.clear()
            for ((key, value) in entries) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)

                    is Float -> editor.putFloat(key, value)

                    is Int -> editor.putInt(key, value)

                    is Long -> editor.putLong(key, value)

                    is String -> editor.putString(key, value)

                    is Set<*> -> {
                        if (value.any { it !is String }) throw IOException("Invalid settings set")
                        editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }

                    else -> throw IOException("Invalid settings value")
                }
            }
            if (!editor.commit()) throw IOException("Unable to persist settings")
        }
    }

    /**
     * Exports given [SharedPreferences] to the file in given outputPath.
     * It also creates the file.
     */
    @Throws(Exception::class)
    fun exportDatabase(preferences: SharedPreferences, file: StoredFileHelper) {
        // truncate the file before writing to it, otherwise if the new content is smaller than the
        // previous file size, the file will retain part of the previous content and be corrupted
        ZipOutputStream(SharpOutputStream(file.openAndTruncateStream()).buffered()).use { outZip ->
            // add the database
            val name = BackupFileLocator.FILE_NAME_DB
            ZipHelper.addFileToZip(outZip, name, fileLocator.db)

            // add the legacy vulnerable serialized preferences (will be removed in the future)
            ZipHelper.addFileToZip(
                outZip,
                BackupFileLocator.FILE_NAME_SERIALIZED_PREFS
            ) { byteOutput ->
                ObjectOutputStream(byteOutput).use { output ->
                    output.writeObject(preferences.all)
                    output.flush()
                }
            }

            // add the JSON preferences
            ZipHelper.addFileToZip(
                outZip,
                BackupFileLocator.FILE_NAME_JSON_PREFS
            ) { byteOutput ->
                JsonWriter
                    .indent("")
                    .on(byteOutput)
                    .`object`(preferences.all)
                    .done()
            }
        }
    }

    /**
     * Tries to create database directory if it does not exist.
     */
    @Throws(IOException::class)
    fun ensureDbDirectoryExists() {
        fileLocator.db.createParentDirectories()
    }

    /** Prepare from one private copy; no live settings or pending restore are modified. */
    fun prepareRestore(file: StoredFileHelper, settingsEntry: String?): PreparedRestore {
        if (hasPendingRestore()) throw IOException("A restored database is awaiting restart")
        ensureDbDirectoryExists()
        val snapshot = Files.createTempFile(fileLocator.db.parent, "backup-source-", ".zip")
        var temporary: Path? = null
        try {
            file.stream.use { stream ->
                Files.copy(SharpInputStream(stream), snapshot, StandardCopyOption.REPLACE_EXISTING)
            }
            val preferences = ZipFile(snapshot.toFile()).use { zip ->
                if (settingsEntry == null) {
                    null
                } else {
                    val offered = if (zip.getEntry(BackupFileLocator.FILE_NAME_JSON_PREFS) != null) {
                        BackupFileLocator.FILE_NAME_JSON_PREFS
                    } else if (zip.getEntry(BackupFileLocator.FILE_NAME_SERIALIZED_PREFS) != null) {
                        BackupFileLocator.FILE_NAME_SERIALIZED_PREFS
                    } else {
                        null
                    }
                    if (offered != settingsEntry) {
                        throw IOException("Backup settings changed; inspect the backup again")
                    }
                    zip.getInputStream(zip.getEntry(settingsEntry)).use {
                        if (settingsEntry == BackupFileLocator.FILE_NAME_JSON_PREFS) {
                            decodeJson(it)
                        } else {
                            decodeSerialized(it)
                        }
                    }
                }
            }
            val preparedDatabase = Files.createTempFile(fileLocator.db.parent, "backup-db-", ".tmp")
            temporary = preparedDatabase
            var found = false
            ZipInputStream(Files.newInputStream(snapshot)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == BackupFileLocator.FILE_NAME_DB) {
                        Files.copy(zip, preparedDatabase, StandardCopyOption.REPLACE_EXISTING)
                        found = true
                        break
                    }
                }
            }
            if (!found || Files.size(preparedDatabase) == 0L) {
                throw IOException("Backup does not contain a nonempty database")
            }
            Files.delete(snapshot)
            val prepared = PreparedRestore(fileLocator.db, preparedDatabase, preferences)
            temporary = null
            return prepared
        } finally {
            try {
                temporary?.deleteIfExists()
            } finally {
                snapshot.deleteIfExists()
            }
        }
    }

    fun hasPendingRestore(): Boolean = PendingDatabaseRestore.hasPending(fileLocator.db)

    /** Owns only this attempt's unpublished database; publication transfers ownership to startup. */
    class PreparedRestore internal constructor(
        private val database: Path,
        private val temporary: Path,
        val preferences: MutableMap<String, Any>?
    ) : AutoCloseable {
        private var published = false

        fun publish() {
            PendingDatabaseRestore.publish(database, temporary)
            published = true
        }

        override fun close() {
            if (!published) temporary.deleteIfExists()
        }
    }

    @Deprecated(
        "Serializing preferences with Java's ObjectOutputStream is vulnerable to injections",
        replaceWith = ReplaceWith("exportHasJsonPrefs")
    )
    fun exportHasSerializedPrefs(zipFile: StoredFileHelper): Boolean {
        return ZipHelper.zipContainsFile(zipFile, BackupFileLocator.FILE_NAME_SERIALIZED_PREFS)
    }

    fun exportHasJsonPrefs(zipFile: StoredFileHelper): Boolean {
        return ZipHelper.zipContainsFile(zipFile, BackupFileLocator.FILE_NAME_JSON_PREFS)
    }

    /**
     * Remove all shared preferences from the app and load the preferences supplied to the manager.
     */
    @Deprecated(
        "Serializing preferences with Java's ObjectOutputStream is vulnerable to injections",
        replaceWith = ReplaceWith("loadJsonPrefs")
    )
    @Throws(IOException::class, ClassNotFoundException::class)
    fun loadSerializedPrefs(zipFile: StoredFileHelper, preferences: SharedPreferences) {
        if (!ZipHelper.extractFileFromZip(zipFile, BackupFileLocator.FILE_NAME_SERIALIZED_PREFS) {
                replacePreferences(preferences, decodeSerialized(it))
            }
        ) {
            throw FileNotFoundException(BackupFileLocator.FILE_NAME_SERIALIZED_PREFS)
        }
    }

    @Throws(IOException::class, JsonParserException::class)
    fun loadJsonPrefs(zipFile: StoredFileHelper, preferences: SharedPreferences) {
        if (!ZipHelper.extractFileFromZip(zipFile, BackupFileLocator.FILE_NAME_JSON_PREFS) {
                replacePreferences(preferences, decodeJson(it))
            }
        ) {
            throw FileNotFoundException(BackupFileLocator.FILE_NAME_JSON_PREFS)
        }
    }

    private fun decodeSerialized(input: InputStream): MutableMap<String, Any> = PreferencesObjectInputStream(input).use {
        val entries = it.readObject() as? Map<*, *> ?: throw IOException("Invalid settings map")
        decodeEntries(entries, false)
    }

    private fun decodeJson(input: InputStream): MutableMap<String, Any> = decodeEntries(JsonParser.`object`().from(input), true)

    private fun decodeEntries(entries: Map<*, *>, json: Boolean): MutableMap<String, Any> {
        val decoded = mutableMapOf<String, Any>()
        for ((key, value) in entries) {
            if (key !is String) throw IOException("Invalid settings key")
            when (value) {
                is Boolean, is Float, is Int, is Long, is String -> decoded[key] = value

                is Double -> if (json) {
                    value.toFloat().takeIf { it.isFinite() }?.let { decoded[key] = it }
                }

                is Set<*> -> {
                    if (value.any { it !is String }) throw IOException("Invalid settings set")
                    decoded[key] = value.filterIsInstance<String>().toSet()
                }

                is JsonArray -> if (json) decoded[key] = value.filterIsInstance<String>().toSet()
            }
        }
        return decoded
    }
}
