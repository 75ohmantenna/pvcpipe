package org.schabi.newpipe.error

import android.os.Parcel
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.parcelize.Parceler

/**
 * The binder can not handle too much data and throws TransactionTooLargeException.
 *
 * This Parceler tries to skip this fact with temporary gzip the data. Seems to
 * work -- but sending over eMail still needs some truncating
 * see {@link PvcErrorActivityHelper}.
 */
object PvcErrorInfoTracesParceler : Parceler<Array<String>> {
    override fun create(parcel: Parcel): Array<String> {
        val byteArray = ByteArray(parcel.readInt())
        parcel.readByteArray(byteArray)
        return decode(byteArray)
    }

    override fun Array<String>.write(parcel: Parcel, flags: Int) {
        val zipped = encode(this)
        parcel.writeInt(zipped.size)
        parcel.writeByteArray(zipped)
    }

    /**
     * Packs traces as a count followed by each trace with its length. A separator character
     * cannot be used: any character can be part of a trace, and a semicolon is part of most.
     *
     * The traces are bounded first, with the limits [decode] checks, so that the reader never
     * refuses what the writer wrote.
     */
    internal fun encode(traces: Array<String>): ByteArray {
        val bounded = PvcErrorInfoHelper.truncate(traces)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { data ->
            data.writeInt(bounded.size)
            for (trace in bounded) {
                val encoded = trace.toByteArray(StandardCharsets.UTF_8)
                data.writeInt(encoded.size)
                data.write(encoded)
            }
        }
        return bytes.toByteArray()
    }

    /**
     * @return the traces [encode] packed. Bytes this cannot read give one line that says so:
     * a notification can still hold an error that a build before this format wrote.
     */
    internal fun decode(content: ByteArray): Array<String> = try {
        DataInputStream(GZIPInputStream(content.inputStream())).use { data ->
            val count = data.readInt()
            if (count < 0 || count > PvcErrorInfoHelper.MAX_TRACES) {
                throw IOException("Not a list of traces")
            }
            Array(count) {
                val length = data.readInt()
                if (length < 0 || length > MAX_ENCODED_TRACE) {
                    throw IOException("Not a trace")
                }
                val encoded = ByteArray(length)
                data.readFully(encoded)
                String(encoded, StandardCharsets.UTF_8)
            }
        }
    } catch (e: IOException) {
        arrayOf(UNREADABLE)
    }

    internal const val UNREADABLE = "The details of this error could not be read."

    // a character takes at most three bytes in UTF-8, and four for a pair of two
    private const val MAX_ENCODED_TRACE = 3 * PvcErrorInfoHelper.MAX_TRACE_LENGTH
}
