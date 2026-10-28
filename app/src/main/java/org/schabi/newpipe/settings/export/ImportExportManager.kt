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
import java.util.Comparator
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createParentDirectories
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ZipHelper

class ImportExportManager(
    private val fileLocator: BackupFileLocator,
    private val databaseValidator: DatabaseValidator
) {
    fun interface DatabaseValidator {
        @Throws(IOException::class)
        fun validate(database: Path)
    }

    /** Advisory central-directory inspection; restore takes and validates a fresh snapshot. */
    data class Inspection(val readable: Boolean, val settingsEntry: String?)

    companion object {
        const val TAG = "ImportExportManager"

        private fun deleteWorkingDirectory(directory: Path) {
            if (Files.notExists(directory)) return
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }

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

    /** Write the database and preferences to the selected document, truncating existing content. */
    @Throws(Exception::class)
    fun exportDatabase(preferences: SharedPreferences, file: StoredFileHelper) {
        // truncate the file before writing to it, otherwise if the new content is smaller than the
        // previous file size, the file will retain part of the previous content and be corrupted
        ZipOutputStream(SharpOutputStream(file.openAndTruncateStream()).buffered()).use { outZip ->
            // add the database
            val name = BackupFileLocator.FILE_NAME_DB
            ZipHelper.addFileToZip(outZip, name, fileLocator.db)

            // Include the legacy format for older importers; current restores prefer JSON.
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

    fun inspect(file: StoredFileHelper): Inspection = workingDirectory().use { working ->
        try {
            ZipFile(copyArchive(file, working.path).toFile()).use { zip ->
                validateEntries(zip)
                Inspection(true, offeredSettings(zip))
            }
        } catch (_: IOException) {
            Inspection(false, null)
        }
    }

    /** Validate and migrate one private copy before changing settings or publishing anything. */
    fun prepareRestore(file: StoredFileHelper, settingsEntry: String?): PreparedRestore {
        if (hasPendingRestore()) throw IOException("A restored database is awaiting restart")
        var temporary: Path? = null
        try {
            val preferences = workingDirectory().use { working ->
                val snapshot = copyArchive(file, working.path)
                val candidate = working.path.resolve(BackupFileLocator.FILE_NAME_DB)
                val decoded = ZipFile(snapshot.toFile()).use { zip ->
                    validateEntries(zip)
                    val entries = if (settingsEntry == null) {
                        null
                    } else {
                        if (offeredSettings(zip) != settingsEntry) {
                            throw IOException("Backup settings changed; inspect the backup again")
                        }
                        val settings = working.path.resolve("settings")
                        extractVerified(zip, zip.getEntry(settingsEntry), settings)
                        Files.newInputStream(settings).use {
                            if (settingsEntry == BackupFileLocator.FILE_NAME_JSON_PREFS) decodeJson(it) else decodeSerialized(it)
                        }
                    }
                    val database = zip.getEntry(BackupFileLocator.FILE_NAME_DB)
                        ?: throw IOException("Backup does not contain a database")
                    extractVerified(zip, database, candidate)
                    if (Files.size(candidate) == 0L) throw IOException("Backup database is empty")
                    entries
                }
                databaseValidator.validate(candidate)
                val ready = Files.createTempFile(fileLocator.db.parent, "backup-db-", ".tmp")
                temporary = ready
                Files.move(candidate, ready, StandardCopyOption.REPLACE_EXISTING)
                decoded
            }
            // Finish directory/sidecar cleanup before allowing settings commit or publication.
            val prepared = PreparedRestore(fileLocator.db, checkNotNull(temporary), preferences)
            temporary = null
            return prepared
        } finally {
            temporary?.let { Files.deleteIfExists(it) }
        }
    }

    private fun offeredSettings(zip: ZipFile): String? = when {
        zip.getEntry(BackupFileLocator.FILE_NAME_JSON_PREFS) != null -> BackupFileLocator.FILE_NAME_JSON_PREFS
        zip.getEntry(BackupFileLocator.FILE_NAME_SERIALIZED_PREFS) != null -> BackupFileLocator.FILE_NAME_SERIALIZED_PREFS
        else -> null
    }

    private fun validateEntries(zip: ZipFile) {
        val critical = setOf(
            BackupFileLocator.FILE_NAME_DB,
            BackupFileLocator.FILE_NAME_JSON_PREFS,
            BackupFileLocator.FILE_NAME_SERIALIZED_PREFS
        )
        val seen = mutableSetOf<String>()
        for (entry in zip.entries()) {
            if (entry.name.removeSuffix("/") in critical && (!seen.add(entry.name) || entry.isDirectory)) {
                throw ZipException("Duplicate or invalid backup entry: ${entry.name}")
            }
        }
    }

    private fun extractVerified(zip: ZipFile, entry: ZipEntry, destination: Path) {
        CheckedInputStream(zip.getInputStream(entry), CRC32()).use { input ->
            val count = Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
            if (entry.size != count || entry.crc != input.checksum.value) {
                throw ZipException("Backup entry size or CRC mismatch: ${entry.name}")
            }
        }
    }

    private fun workingDirectory(): WorkingDirectory {
        ensureDbDirectoryExists()
        return WorkingDirectory(Files.createTempDirectory(fileLocator.db.parent, "backup-restore-"))
    }

    private fun copyArchive(file: StoredFileHelper, directory: Path): Path {
        val snapshot = directory.resolve("source.zip")
        file.stream.use { stream ->
            Files.copy(SharpInputStream(stream), snapshot)
        }
        return snapshot
    }

    private class WorkingDirectory(val path: Path) : AutoCloseable {
        override fun close() = deleteWorkingDirectory(path)
    }

    fun hasPendingRestore(): Boolean = PendingDatabaseRestore.hasPending(fileLocator.db)

    /** Owns the closed, validated database; publication transfers ownership to startup. */
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
            if (!published) Files.deleteIfExists(temporary)
        }
    }

    @Deprecated(
        "Legacy Java-serialized preferences require restricted deserialization; prefer JSON",
        replaceWith = ReplaceWith("exportHasJsonPrefs")
    )
    fun exportHasSerializedPrefs(zipFile: StoredFileHelper): Boolean {
        return ZipHelper.zipContainsFile(zipFile, BackupFileLocator.FILE_NAME_SERIALIZED_PREFS)
    }

    fun exportHasJsonPrefs(zipFile: StoredFileHelper): Boolean {
        return ZipHelper.zipContainsFile(zipFile, BackupFileLocator.FILE_NAME_JSON_PREFS)
    }

    /**
     * Replace the live preferences with the decoded legacy settings entry from the archive.
     */
    @Deprecated(
        "Legacy Java-serialized preferences require restricted deserialization; prefer JSON",
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
