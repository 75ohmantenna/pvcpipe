package org.schabi.newpipe.player.cast

import org.fcast.sender_sdk.PlaybackState
import org.fcast.sender_sdk.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FCastObservedPositionTest {
    @Test
    fun `v2 and no echo v3 and v4 accept receiver time without source acknowledgement`() {
        for (version in 2..4) {
            val fixture = Fixture(version)
            fixture.load()
            fixture.state.playbackStateChanged(PlaybackState.PLAYING)
            assertFalse(fixture.state.hasObservedPosition)
            fixture.state.timeChanged(43.0)
            assertTrue(fixture.state.hasObservedPosition)
            assertEquals(43_000L, fixture.positionMs)
        }
    }

    @Test
    fun `requested position and state duration speed and emit are not receiver clock evidence`() {
        val fixture = Fixture()
        fixture.load()
        fixture.state.emit()
        assertEquals(42_000L, fixture.positionMs)
        assertFalse(fixture.state.hasObservedPosition)
        fixture.state.durationChanged(120.0)
        fixture.state.speedChanged(1.5)
        fixture.state.playbackStateChanged(PlaybackState.BUFFERING)
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        fixture.state.seek(17_000L)
        fixture.state.setPlayWhenReady(true)
        fixture.state.emit()
        assertFalse(fixture.state.hasObservedPosition)
        fixture.state.timeChanged(0.0)
        assertTrue(fixture.state.hasObservedPosition)
        assertEquals(0L, fixture.positionMs)
    }

    @Test
    fun `finite receiver time while buffering is evidence without playing readiness`() {
        val fixture = Fixture()
        fixture.load()
        fixture.state.playbackStateChanged(PlaybackState.BUFFERING)
        fixture.state.timeChanged(42.0)
        assertTrue(fixture.state.hasObservedPosition)
        assertEquals(3, fixture.playbackState)
        assertEquals(42_000L, fixture.positionMs)
    }

    @Test
    fun `nonfinite receiver values cannot authorize or overwrite the clock`() {
        val fixture = Fixture()
        fixture.load()
        for (time in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            fixture.state.timeChanged(time)
            assertFalse(fixture.state.hasObservedPosition)
        }
        fixture.state.timeChanged(7.0)
        for (time in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            fixture.state.timeChanged(time)
            assertTrue(fixture.state.hasObservedPosition)
            assertEquals(7_000L, fixture.positionMs)
        }
    }

    @Test
    fun `finite negative time preserves existing clamping policy and counts as observed zero`() {
        val fixture = Fixture()
        fixture.load()
        fixture.state.timeChanged(-1.0)
        assertTrue(fixture.state.hasObservedPosition)
        assertEquals(0L, fixture.positionMs)
    }

    @Test
    fun `learned echo gate rejects old callbacks until current source acknowledgement`() {
        for (version in 3..4) {
            val fixture = Fixture(version)
            fixture.load()
            fixture.state.sourceChanged(fixture.first)
            assertFalse(fixture.state.hasObservedPosition)
            fixture.state.timeChanged(43.0)
            assertTrue(fixture.state.hasObservedPosition)

            fixture.load(fixture.second)
            assertFalse(fixture.state.hasObservedPosition)
            fixture.state.sourceChanged(fixture.first)
            fixture.state.timeChanged(44.0)
            fixture.state.playbackStateChanged(PlaybackState.PLAYING)
            fixture.state.durationChanged(120.0)
            fixture.state.speedChanged(1.5)
            fixture.state.emit()
            assertFalse(fixture.state.hasObservedPosition)
            assertEquals(42_000L, fixture.positionMs)
            fixture.state.sourceChanged(fixture.second)
            assertFalse(fixture.state.hasObservedPosition)
            fixture.state.timeChanged(0.0)
            assertTrue(fixture.state.hasObservedPosition)
            assertEquals(0L, fixture.positionMs)
        }
    }

    @Test
    fun `reload without echoes clears evidence but cannot distinguish late progress`() {
        val fixture = Fixture()
        fixture.load()
        fixture.state.timeChanged(43.0)
        fixture.load(fixture.second)
        assertFalse(fixture.state.hasObservedPosition)
        // Without learned source echoes the protocol cannot attribute this callback to a load.
        fixture.state.timeChanged(44.0)
        assertTrue(fixture.state.hasObservedPosition)
    }

    @Test
    fun `reset clears evidence and rejects late callbacks until another load`() {
        val fixture = Fixture()
        fixture.load()
        fixture.state.sourceChanged(fixture.first)
        fixture.state.timeChanged(43.0)
        fixture.state.reset()
        assertFalse(fixture.state.hasObservedPosition)
        fixture.state.sourceChanged(fixture.first)
        fixture.state.timeChanged(44.0)
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        assertFalse(fixture.state.hasObservedPosition)
        fixture.load(fixture.second)
        fixture.state.timeChanged(0.0)
        assertTrue(fixture.state.hasObservedPosition)
    }

    @Test
    fun `source takeover stop and idle relinquishment clear evidence and reject late time`() {
        for (reason in 0..2) {
            val fixture = Fixture()
            fixture.load()
            fixture.state.sourceChanged(fixture.first)
            fixture.state.playbackStateChanged(PlaybackState.PLAYING)
            fixture.state.timeChanged(43.0)
            when (reason) {
                0 -> fixture.state.sourceChanged(fixture.second)
                1 -> fixture.state.stopped()
                else -> fixture.state.playbackStateChanged(PlaybackState.IDLE)
            }
            assertFalse(fixture.state.hasObservedPosition)
            fixture.state.timeChanged(44.0)
            fixture.state.sourceChanged(fixture.first)
            fixture.state.timeChanged(45.0)
            assertFalse(fixture.state.hasObservedPosition)
            assertEquals(1, fixture.stops)
        }
    }

    private class Fixture(version: Int = 4) : FCastSession.Listener {
        val first = Source.Url("http://192.0.2.1/first", "video/mp4")
        val second = Source.Url("http://192.0.2.1/second", "video/mp4")
        val state = FCastPlaybackState(this) {}.also {
            it.legacyReceiver = version == 2
            it.legacyCompletionWithoutEcho = version == 3
        }
        var positionMs = -1L
        var playbackState = -1
        var stops = 0

        fun load(source: Source = first) = state.load(source, 42_000L, 1.0, true)

        override fun onPlaybackChanged(state: Int, positionMs: Long, durationMs: Long, speed: Double) {
            this.playbackState = state
            this.positionMs = positionMs
        }

        override fun onConnectionChanged() = Unit
        override fun onVolumeChanged(volume: Double) = Unit
        override fun onError(message: String) = error(message)
        override fun onEnded() = Unit
        override fun onStopped() {
            stops++
        }
    }
}
