package org.schabi.newpipe.player.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyFCastCompletionTest {
    @Test
    fun `playing v2 media ending within the last progress interval completes`() {
        assertTrue(completes(positionMs = 59_500L))
        assertTrue(completes(positionMs = 60_000L))
    }

    @Test
    fun `modern receivers must use authoritative ended events`() {
        assertFalse(completes(legacyReceiver = false))
    }

    @Test
    fun `initial receiver state and cleared or explicitly stopped media do not complete`() {
        assertFalse(completes(mediaLoaded = false))
        assertFalse(completes(wasPlaying = false))
    }

    @Test
    fun `idle far from the end is not natural completion`() {
        assertFalse(completes(positionMs = 30_000L))
        assertFalse(completes(positionMs = 0L))
        assertFalse(completes(positionMs = 70_000L))
    }

    @Test
    fun `live and unknown durations do not complete`() {
        assertFalse(completes(durationMs = 0L))
        assertFalse(completes(durationMs = -1L))
    }

    @Test
    fun `short media requires reaching its final tenth rather than any idle`() {
        assertFalse(completes(positionMs = 100L, durationMs = 500L))
        assertTrue(completes(positionMs = 490L, durationMs = 500L))
    }

    private fun completes(
        legacyReceiver: Boolean = true,
        mediaLoaded: Boolean = true,
        wasPlaying: Boolean = true,
        positionMs: Long = 60_000L,
        durationMs: Long = 60_000L
    ) = isLegacyFCastCompletion(legacyReceiver, mediaLoaded, wasPlaying, positionMs, durationMs)
}
