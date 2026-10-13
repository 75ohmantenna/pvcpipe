package org.schabi.newpipe.error

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.error.PvcErrorInfoTracesParceler.UNREADABLE
import org.schabi.newpipe.error.PvcErrorInfoTracesParceler.decode
import org.schabi.newpipe.error.PvcErrorInfoTracesParceler.encode

class PvcErrorInfoTracesParcelerTest {
    private fun roundTrip(vararg traces: String) = decode(encode(arrayOf(*traces)))

    @Test
    fun `a trace with semicolons comes back in one piece`() {
        // the old format joined with "; " and split on ";", which cut this into three
        val trace = "java.lang.ClassCastException: [Ljava.lang.String; cannot be cast\n" +
            "\tat Foo.bar([Ljava/lang/Object;)V; and more"
        assertArrayEquals(arrayOf(trace), roundTrip(trace))
    }

    @Test
    fun `several traces keep their number, order and text`() {
        val traces = arrayOf("first", "", " second; ", "third\nline", "\uD83D\uDE00 \u00e9")
        assertArrayEquals(traces, roundTrip(*traces))
    }

    @Test
    fun `the reader accepts everything the writer writes`() {
        // more traces than one error may hold, each of them short
        val many = Array(PvcErrorInfoHelper.MAX_TRACES + 1) { "trace $it" }
        val back = decode(encode(many))
        assertEquals(PvcErrorInfoHelper.MAX_TRACES, back.size)
        assertArrayEquals(many.copyOfRange(0, PvcErrorInfoHelper.MAX_TRACES), back)

        // exactly as many as allowed
        val full = Array(PvcErrorInfoHelper.MAX_TRACES) { "t$it" }
        assertArrayEquals(full, decode(encode(full)))

        // one trace of the longest kind, in characters that take three bytes each
        val wide = "\u20ac".repeat(PvcErrorInfoHelper.MAX_TRACE_LENGTH)
        assertArrayEquals(arrayOf(wide), decode(encode(arrayOf(wide))))

        // and one that is too long comes back cut, not refused
        val tooLong = decode(encode(arrayOf("\u20ac".repeat(PvcErrorInfoHelper.MAX_TRACE_LENGTH + 5))))
        assertEquals(PvcErrorInfoHelper.MAX_TRACE_LENGTH, tooLong[0].length)
    }

    @Test
    fun `no traces is not an error`() {
        // reduce() on an empty array threw in the old format
        assertArrayEquals(emptyArray<String>(), roundTrip())
    }

    @Test
    fun `bytes of another format do not crash the reader`() {
        assertArrayEquals(arrayOf(UNREADABLE), decode(ByteArray(0)))
        assertArrayEquals(arrayOf(UNREADABLE), decode(byteArrayOf(1, 2, 3, 4, 5)))
        // gzip of plain text, which is what the old format wrote
        val old = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.GZIPOutputStream(bytes).use { it.write("first; second".toByteArray()) }
        }.toByteArray()
        assertArrayEquals(arrayOf(UNREADABLE), decode(old))
    }
}
