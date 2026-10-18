package org.schabi.newpipe.player.cast

import org.fcast.sender_sdk.PlaybackState
import org.fcast.sender_sdk.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyFCastCompletionTest {
    @Test
    fun `playing v2 media ending within the last progress interval completes`() {
        assertEquals(1, idleAfterPlaying(positionMs = 59_500L).ended)
        assertEquals(1, idleAfterPlaying(positionMs = 60_000L).ended)
    }

    @Test
    fun `receivers without legacy completion inference require explicit ended events`() {
        assertEquals(0, idleAfterPlaying(legacyReceiver = false).ended)
        val fixture = Fixture(legacyReceiver = false)
        fixture.load()
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.playbackStateChanged(PlaybackState.ENDED)
        fixture.state.playbackStateChanged(PlaybackState.ENDED)
        assertEquals(1, fixture.ended)
    }

    @Test
    fun `initial receiver state and explicitly stopped media do not complete`() {
        val fixture = Fixture()
        fixture.state.playbackStateChanged(PlaybackState.IDLE)
        fixture.load()
        fixture.state.playbackStateChanged(PlaybackState.IDLE)
        assertEquals(0, fixture.stopped)
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.durationChanged(60.0)
        fixture.state.timeChanged(60.0)
        fixture.state.stopped()
        fixture.state.playbackStateChanged(PlaybackState.ENDED)
        assertEquals(0, fixture.ended)
        assertEquals(1, fixture.stopped)
    }

    @Test
    fun `idle far from the end relinquishes ownership without natural completion`() {
        for (positionMs in listOf(30_000L, 0L, 70_000L)) {
            val fixture = idleAfterPlaying(positionMs = positionMs)
            assertEquals(0, fixture.ended)
            assertEquals(1, fixture.stopped)
        }
    }

    @Test
    fun `live and unknown durations do not complete`() {
        assertEquals(0, idleAfterPlaying(durationMs = 0L).ended)
        assertEquals(0, idleAfterPlaying(durationMs = -1L).ended)
    }

    @Test
    fun `short media requires reaching its final tenth rather than any idle`() {
        assertEquals(0, idleAfterPlaying(positionMs = 100L, durationMs = 500L).ended)
        assertEquals(1, idleAfterPlaying(positionMs = 490L, durationMs = 500L).ended)
    }

    @Test
    fun `paused near the end is not completion until the known duration is reached`() {
        val fixture = Fixture()
        fixture.load()
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.durationChanged(60.0)
        fixture.state.timeChanged(59.5)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        assertEquals(0, fixture.ended)
        assertEquals(0, fixture.stopped)
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.timeChanged(60.0)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        assertEquals(1, fixture.ended)
    }

    @Test
    fun `a previous loads playing state cannot complete an initially paused load`() {
        val fixture = Fixture()
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.load()
        fixture.state.durationChanged(60.0)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        assertEquals(0, fixture.ended)
        assertEquals(0, fixture.stopped)
    }

    @Test
    fun `paused completion can replay after seeking inside the item`() {
        val fixture = Fixture()
        fixture.load()
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.durationChanged(60.0)
        fixture.state.timeChanged(60.0)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        fixture.state.seek(42_000L)
        fixture.state.timeChanged(42.0)
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.timeChanged(60.0)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        assertEquals(2, fixture.ended)
    }

    @Test
    fun `advancing playing time honors a paused load even without a new state callback`() {
        val fixture = Fixture()
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        val source = Source.Url("http://192.0.2.1:8080/paused", "video/mp4")
        fixture.state.load(source, 10_000L, 1.0, false)
        fixture.state.timeChanged(11.0)
        fixture.state.timeChanged(12.0)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        assertEquals(1, fixture.pauses)
        assertEquals(0, fixture.ended)
        assertEquals(0, fixture.stopped)
    }

    @Test
    fun `an unchanged duration received before load still permits natural completion`() {
        val fixture = Fixture()
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.durationChanged(60.0)
        fixture.load(positionMs = 0L)
        fixture.state.timeChanged(1.0)
        fixture.state.timeChanged(60.0)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        assertEquals(1, fixture.ended)
    }

    @Test
    fun `consecutive loads of equal duration each complete without a duplicate duration event`() {
        val fixture = Fixture()
        fixture.load(positionMs = 0L)
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.durationChanged(60.0)
        fixture.state.timeChanged(60.0)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        assertEquals(1, fixture.ended)

        fixture.load(positionMs = 0L)
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.timeChanged(60.0)
        fixture.state.playbackStateChanged(PlaybackState.PAUSED)
        assertEquals(2, fixture.ended)
    }

    private fun idleAfterPlaying(
        legacyReceiver: Boolean = true,
        positionMs: Long = 60_000L,
        durationMs: Long = 60_000L
    ): Fixture {
        val fixture = Fixture(legacyReceiver)
        fixture.load()
        fixture.state.playbackStateChanged(PlaybackState.PLAYING)
        fixture.state.durationChanged(durationMs / 1000.0)
        fixture.state.timeChanged(positionMs / 1000.0)
        fixture.state.playbackStateChanged(PlaybackState.IDLE)
        return fixture
    }

    private class Fixture(legacyReceiver: Boolean = true) : FCastSession.Listener {
        val state = FCastPlaybackState(this) { pauses++ }
            .also { it.legacyReceiver = legacyReceiver }
        var ended = 0
        var stopped = 0
        var pauses = 0

        fun load(positionMs: Long = 60_000L) {
            val source = Source.Url("http://192.0.2.1:8080/own", "video/mp4")
            state.load(source, positionMs, 1.0, true)
            state.sourceChanged(source)
        }

        override fun onConnectionChanged() = Unit
        override fun onPlaybackChanged(
            state: Int,
            positionMs: Long,
            durationMs: Long,
            speed: Double
        ) = Unit
        override fun onVolumeChanged(volume: Double) = Unit
        override fun onError(message: String) = error(message)

        override fun onEnded() {
            ended++
        }

        override fun onStopped() {
            stopped++
        }
    }
}
