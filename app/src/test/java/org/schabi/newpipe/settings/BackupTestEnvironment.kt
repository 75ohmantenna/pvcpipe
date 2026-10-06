package org.schabi.newpipe.settings

import android.content.SharedPreferences
import android.net.Uri
import java.nio.file.Files
import java.nio.file.Path
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.settings.export.ImportExportManager
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.io.FileStream

/** Real files and codecs with controlled Android effects and preference persistence. */
internal class BackupTestEnvironment(val directory: Path) : BackupRestore.Platform {
    val values = mutableMapOf<String, Any>("original" to "settings")
    val errors = mutableListOf<Throwable>()
    val documents = mutableMapOf<Uri, Path>()
    val database = directory.resolve("newpipe.db")
    var restarts = 0
    var checkpoints = 0
    var cleanups = 0
    var failCheckpoint = false
    var failRestart = false
    var failedCommits = 0
    val preferences: SharedPreferences = mock(SharedPreferences::class.java) { call ->
        when (call.method.name) {
            "getAll" -> HashMap(values)

            "edit" -> editor()

            "getInt", "getBoolean", "getString" ->
                values[call.getArgument<String>(0)] ?: call.getArgument<Any>(1)

            else -> null
        }
    }
    val locator: BackupFileLocator = mock(BackupFileLocator::class.java).also {
        `when`(it.db).thenReturn(database)
        `when`(it.dbJournal).thenReturn(directory.resolve("newpipe.db-journal"))
        `when`(it.dbWal).thenReturn(directory.resolve("newpipe.db-wal"))
        `when`(it.dbShm).thenReturn(directory.resolve("newpipe.db-shm"))
    }
    val archives = ImportExportManager(locator)

    init {
        Files.writeString(database, "live database")
        Files.writeString(directory.resolve("newpipe.db-wal"), "live WAL")
    }

    fun source(resource: String): Uri {
        val path = directory.resolve(resource.substringAfterLast('/'))
        javaClass.classLoader!!.getResourceAsStream(resource)!!.use {
            Files.copy(it, path)
        }
        return documentUri(path)
    }

    fun documentUri(path: Path): Uri {
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn(path.toUri().toString())
        documents[uri] = path
        return uri
    }

    override fun document(uri: Uri): StoredFileHelper {
        val path = documents.getValue(uri)
        return mock(StoredFileHelper::class.java).also {
            `when`(it.stream).thenAnswer { FileStream(path.toFile()) }
            `when`(it.openAndTruncateStream()).thenAnswer {
                FileStream(path.toFile()).also { stream -> stream.setLength(0) }
            }
        }
    }

    override fun checkpoint() {
        checkpoints++
        if (failCheckpoint) throw java.io.IOException("checkpoint failed")
    }

    override fun cleanImportedPreferences() {
        cleanups++
    }

    override fun restart() {
        restarts++
        if (failRestart) throw IllegalStateException("restart failed")
    }

    override fun reportRestoreFailure(error: Throwable) {
        errors.add(error)
    }

    private fun editor(): SharedPreferences.Editor {
        val pending = HashMap(values)
        lateinit var editor: SharedPreferences.Editor
        editor = mock(SharedPreferences.Editor::class.java) { call ->
            when (call.method.name) {
                "clear" -> {
                    pending.clear()
                    editor
                }

                "remove" -> {
                    pending.remove(call.getArgument<String>(0))
                    editor
                }

                "commit", "apply" -> {
                    values.clear()
                    values.putAll(pending)
                    if (call.method.name == "apply") {
                        null
                    } else if (failedCommits > 0) {
                        failedCommits--
                        false
                    } else {
                        true
                    }
                }

                else -> {
                    if (call.method.name.startsWith("put")) {
                        pending[call.getArgument(0)] = call.getArgument(1)
                        editor
                    } else {
                        null
                    }
                }
            }
        }
        return editor
    }
}
