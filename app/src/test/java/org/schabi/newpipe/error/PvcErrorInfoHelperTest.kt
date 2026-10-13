package org.schabi.newpipe.error

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.error.PvcErrorInfoHelper.TRUNCATED
import org.schabi.newpipe.error.PvcErrorInfoHelper.truncate

class PvcErrorInfoHelperTest {
    @Test
    fun `traces within the limits are left alone`() {
        val traces = arrayOf("first", "second; with a semicolon", "")
        assertArrayEquals(traces, truncate(traces, perTrace = 100, total = 1000))
        assertArrayEquals(emptyArray<String>(), truncate(emptyArray()))
    }

    @Test
    fun `one oversized trace is cut to the limit, marker included`() {
        val cut = truncate(arrayOf("x".repeat(1_000_000)), perTrace = 500, total = 10_000)
        assertEquals(1, cut.size)
        assertEquals(500, cut[0].length)
        assertTrue(cut[0].endsWith(TRUNCATED))
        assertTrue(cut[0].startsWith("xxx"))
    }

    @Test
    fun `a trace at the limit is whole and one past it is cut`() {
        val atLimit = "y".repeat(200)
        assertArrayEquals(arrayOf(atLimit), truncate(arrayOf(atLimit), perTrace = 200, total = 200))

        val cut = truncate(arrayOf(atLimit + "y"), perTrace = 200, total = 10_000)
        assertEquals(200, cut[0].length)
        assertTrue(cut[0].endsWith(TRUNCATED))
    }

    @Test
    fun `all traces together stay within the total`() {
        val traces = Array(10) { "z".repeat(300) }
        val cut = truncate(traces, perTrace = 300, total = 1000)
        // three whole traces, a fourth cut to what is left, and the rest dropped
        assertEquals(4, cut.size)
        assertEquals(1000, cut.sumOf { it.length })
        assertEquals(300, cut[2].length)
        assertEquals(100, cut[3].length)
        assertTrue(cut[3].endsWith(TRUNCATED))
    }

    @Test
    fun `no room at all gives no traces`() {
        assertEquals(0, truncate(arrayOf("a", "b"), perTrace = 10, total = 0).size)
        // too little room for the marker itself
        val tiny = truncate(arrayOf("a".repeat(100)), perTrace = 5, total = 5)
        assertEquals(5, tiny[0].length)
    }

    @Test
    fun `truncating twice changes nothing`() {
        val once = truncate(arrayOf("q".repeat(5000), "r".repeat(5000)), perTrace = 700, total = 900)
        assertArrayEquals(once, truncate(once, perTrace = 700, total = 900))
    }

    @Test
    fun `a character of two code units is not cut in half`() {
        // U+1F600 takes two chars; put one so that the cut falls between them
        val limit = 100
        val prefix = "a".repeat(limit - TRUNCATED.length - 1)
        val cut = truncate(arrayOf(prefix + "\uD83D\uDE00" + "b".repeat(500)), limit, 10_000)[0]
        assertTrue(cut.length <= limit)
        assertTrue(cut.none { it.isSurrogate() })
    }

    @Test
    fun `the default limits keep an error small enough for an intent`() {
        // text that does not compress: every character differs from its neighbours
        val random = java.util.Random(1)
        val noise = String(CharArray(2_000_000) { (random.nextInt(0x7e - 0x20) + 0x20).toChar() })
        val bounded = truncate(arrayOf(noise, noise, noise))
        assertTrue(bounded.sumOf { it.length } <= PvcErrorInfoHelper.MAX_TOTAL_LENGTH)
        // Android fails a transaction of about 1 MB; stay far below it
        assertTrue(PvcErrorInfoTracesParceler.encode(bounded).size < 100_000)
    }
}
