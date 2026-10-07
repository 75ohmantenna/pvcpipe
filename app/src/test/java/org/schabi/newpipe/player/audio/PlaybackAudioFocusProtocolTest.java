package org.schabi.newpipe.player.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

public class PlaybackAudioFocusProtocolTest {
    private PlaybackAudioTestEnvironment environment;
    private PlaybackAudio audio;

    @Before
    public void setUp() {
        environment = new PlaybackAudioTestEnvironment();
        environment.volume = 0.7f;
        audio = new PlaybackAudio(environment);
    }

    @Test
    public void immediateGrantAfterPauseRestoresDuckedAudioWithoutGainCallback() {
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        audio.onPlaybackEvent(PlaybackAudio.Event.PAUSE_REQUESTED);
        audio.onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);
        finishFades();
        assertEquals(0.7f, environment.volume, 0);
    }

    @Test
    public void immediateGrantRestartsAnInterruptedRestoration() {
        restore();
        environment.fades.get(0).frame(0.4f);
        audio.onPlaybackEvent(PlaybackAudio.Event.PAUSE_REQUESTED);
        audio.onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);
        finishFades();
        assertEquals(0.7f, environment.volume, 0);
    }

    @Test
    public void immediateGrantAfterUnmutingClearsTemporaryDucking() {
        environment.playing = true;
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        finishFades();
        assertEquals(0.7f, environment.volume, 0);
    }

    @Test
    public void deniedRequestCannotClearDucking() {
        environment.focusGranted = false;
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        audio.onPlaybackEvent(PlaybackAudio.Event.PAUSE_REQUESTED);
        audio.onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);
        finishFades();
        assertEquals(0.2f, environment.volume, 0);
        assertEquals(0.7f, audio.getInternalVolume(), 0);
    }

    @Test
    public void newerFocusLossDuringRequestWinsOverAnImmediateGrant() {
        environment.onRequest = () -> environment.focus(PlaybackAudio.FocusChange.DUCK);
        audio.onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);
        finishFades();
        assertEquals(0.2f, environment.volume, 0);
    }

    @Test
    public void disposalDuringRequestWinsOverItsGrantResult() {
        environment.onRequest = audio::dispose;
        audio.onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);
        final int count = environment.effects.size();
        finishFades();
        assertEquals(count, environment.effects.size());
        assertTrue(environment.fades.isEmpty());
    }

    @Test
    public void synchronousNewFocusEventRetiresTheAnimationReturnedAfterIt() {
        environment.resume = true;
        environment.playing = true;
        environment.focus(PlaybackAudio.FocusChange.TRANSIENT_LOSS);
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.onAnimate = () -> environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        finishFades();
        assertFalse(environment.effects.contains("play"));
        assertEquals(0.2f, environment.volume, 0);
        assertTrue(environment.fades.get(0).canceled);
    }

    @Test
    public void refusedMutePreservesTheAcceptedRestoration() {
        restore();
        environment.setAvailable = false;
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        assertFalse(environment.fades.get(0).canceled);
        finishFades();
        assertEquals(0.7f, environment.volume, 0);
        assertFalse(environment.effects.contains("abandon-focus"));
    }

    @Test
    public void repeatedLossDoesNotForgetPreviouslyPlayingPlayback() {
        environment.resume = true;
        environment.playing = true;
        environment.focus(PlaybackAudio.FocusChange.TRANSIENT_LOSS);
        environment.focus(PlaybackAudio.FocusChange.TRANSIENT_LOSS);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        assertTrue(environment.playing);
    }

    @Test
    public void synchronouslyCompletedRestorationCannotUndoLaterMute() {
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.onAnimate = () -> environment.fades.get(0).finish();
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        assertEquals(0.7f, environment.volume, 0);
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        finishFades();
        assertEquals(0, environment.volume, 0);
    }

    private void restore() {
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
    }

    private void finishFades() {
        new ArrayList<>(environment.fades).forEach(PlaybackAudioTestEnvironment.Fade::finish);
    }
}
