package org.schabi.newpipe.error

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper.Companion.logcatDump
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper.PvcLogFileNameErrorInfo

/** Looks up only the existing capture associated with this report; never starts a capture. */
internal object ReportingLogFile {
    fun find(context: Context, errorInfo: ErrorInfo): File? {
        if (errorInfo.pvcErrorInfoCreationTimestamp <= 0) return null
        val name = PvcLogFileNameErrorInfo().apply {
            setTimestamp(errorInfo.pvcErrorInfoCreationTimestamp)
        }.getLogFileName()
        val file = File(logcatDump.getLogFolder(context), name)
        return try {
            requireComplete(file)
            file
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * The toolkit writes directly to the final filename, without an atomic rename or completion API.
     * An existing or nonempty file can still be unfinished. Accept only the complete wrapper written
     * by PvcLogFileFormat, including this file's name, and actually open it for reading.
     */
    fun requireComplete(file: File) {
        if (!file.isFile || !file.canRead()) {
            throw IOException("Diagnostic log is unavailable or unreadable")
        }
        val header = "<details><summary><b>Logcat: ${file.name}</b></summary><p>\n\n```\n"
            .toByteArray(Charsets.UTF_8)
        val footer = "\n```\n</details>\n<hr>\n".toByteArray(Charsets.UTF_8)
        RandomAccessFile(file, "r").use { reader ->
            val length = reader.length()
            if (length < header.size + footer.size) {
                throw IOException("Diagnostic log is unfinished")
            }
            val actualHeader = ByteArray(header.size)
            reader.readFully(actualHeader)
            val actualFooter = ByteArray(footer.size)
            reader.seek(length - footer.size)
            reader.readFully(actualFooter)
            if (!actualHeader.contentEquals(header) || !actualFooter.contentEquals(footer) ||
                reader.length() != length
            ) {
                throw IOException("Diagnostic log is unfinished or does not match its filename")
            }
        }
    }
}
