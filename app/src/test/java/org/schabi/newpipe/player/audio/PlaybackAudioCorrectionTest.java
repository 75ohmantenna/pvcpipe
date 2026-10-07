package org.schabi.newpipe.player.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PlaybackAudioCorrectionTest {
    private PlaybackAudioTestEnvironment environment;
    private PlaybackAudio audio;

    @Before
    public void setUp() {
        environment = new PlaybackAudioTestEnvironment();
        environment.volume = 0.7f;
        audio = new PlaybackAudio(environment);
    }

    @Test
    public void repeatedDuckingPreservesTheUserLevel() {
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        finishFades();
        assertEquals(0.7f, environment.volume, 0);
    }

    @Test
    public void lossAfterDuckingPreservesTheUserLevel() {
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.focus(PlaybackAudio.FocusChange.TRANSIENT_LOSS);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        finishFades();
        assertEquals(0.7f, environment.volume, 0);
    }

    @Test
    public void gainCannotMakeMutedPlaybackAudible() {
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        assertEquals(0, environment.volume, 0);
        finishFades();
        assertEquals(0, environment.volume, 0);
        assertTrue(audio.isMuted());
    }

    @Test
    public void muteSupersedesAllOlderAnimationCallbacks() {
        startRestore();
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        environment.fades.get(0).frame(0.5f);
        finishFades();
        assertEquals(0, environment.volume, 0);
        assertTrue(audio.isMuted());
        assertTrue(environment.fades.get(0).canceled);
    }

    @Test
    public void gestureSupersedesTheOlderRestoreTarget() {
        startRestore();
        audio.setInternalVolume(0.4f);
        environment.fades.get(0).frame(0.6f);
        finishFades();
        assertEquals(0.4f, environment.volume, 0);
        assertTrue(environment.fades.get(0).canceled);
    }

    @Test
    public void disposalRejectsLateFocusAndAnimationEffects() {
        startRestore();
        audio.dispose();
        final List<String> atDisposal = new ArrayList<>(environment.effects);
        environment.focus(PlaybackAudio.FocusChange.LOSS);
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        environment.fades.get(0).frame(0.6f);
        finishFades();
        audio.onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);
        audio.setInternalVolume(0.9f);
        audio.setSystemVolume(9);
        assertEquals(atDisposal, environment.effects);
        assertEquals(5, environment.systemVolume);
        assertTrue(environment.fades.get(0).canceled);
    }

    @Test
    public void disposalIsIdempotent() {
        audio.dispose();
        audio.dispose();
        assertEquals(List.of("abandon-focus", "close"), environment.effects);
    }

    @Test
    public void newFocusLossSupersedesTheOlderRestoration() {
        startRestore();
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.fades.get(0).frame(0.5f);
        finishFades();
        assertEquals(0.2f, environment.volume, 0);
        assertTrue(environment.fades.get(0).canceled);
    }

    @Test
    public void duckingDoesNotRaiseQuietUserVolume() {
        audio.setInternalVolume(0.1f);
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        assertEquals(0.1f, environment.volume, 0);
    }

    @Test
    public void gestureDuringDuckingChangesTheTargetWithoutRemovingAttenuation() {
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        audio.setInternalVolume(0.4f);
        assertEquals(0.2f, environment.volume, 0);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        finishFades();
        assertEquals(0.4f, environment.volume, 0);
    }

    @Test
    public void internalVolumeReportsTheUserLevelRatherThanTemporaryDucking() {
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        assertEquals(0.7f, audio.getInternalVolume(), 0);
        assertFalse(audio.isMuted());
    }

    @Test
    public void settingZeroThenUnmutingRestoresTheLastAudibleUserLevel() {
        audio.setInternalVolume(0);
        assertTrue(audio.isMuted());
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        assertFalse(audio.isMuted());
        assertEquals(0.7f, environment.volume, 0);
    }

    @Test
    public void focusGainDoesNotStartPlaybackThatWasAlreadyPaused() {
        environment.resume = true;
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        assertFalse(environment.effects.contains("play"));
    }

    @Test
    public void manualPauseAfterInterruptionPreventsAutomaticResume() {
        environment.resume = true;
        environment.playing = true;
        environment.focus(PlaybackAudio.FocusChange.TRANSIENT_LOSS);
        audio.onPlaybackEvent(PlaybackAudio.Event.PAUSE_REQUESTED);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        assertFalse(environment.effects.contains("play"));
    }

    @Test
    public void muteAfterInterruptionPreventsAutomaticResume() {
        environment.resume = true;
        environment.playing = true;
        environment.focus(PlaybackAudio.FocusChange.TRANSIENT_LOSS);
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        assertFalse(environment.effects.contains("play"));
        assertEquals(0, environment.volume, 0);
    }

    @Test
    public void refusedMuteDoesNotAbandonFocusForStillAudiblePlayback() {
        environment.setAvailable = false;
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        assertFalse(audio.isMuted());
        assertEquals(0.7f, environment.volume, 0);
        assertFalse(environment.effects.contains("abandon-focus"));
    }

    @Test
    public void positiveGestureDuringMutedPlaybackReacquiresFocus() {
        environment.playing = true;
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        environment.effects.clear();
        audio.setInternalVolume(0.4f);
        assertFalse(audio.isMuted());
        assertTrue(environment.effects.contains("request-focus"));
    }

    @Test
    public void zeroGestureDuringPlaybackReleasesFocus() {
        environment.playing = true;
        audio.setInternalVolume(0);
        assertTrue(audio.isMuted());
        assertTrue(environment.effects.contains("abandon-focus"));
    }

    @Test
    public void unmutingPausedPlaybackDoesNotTakeFocus() {
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        environment.effects.clear();
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        assertFalse(audio.isMuted());
        assertFalse(environment.effects.contains("request-focus"));
    }

    private void startRestore() {
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
    }

    private void finishFades() {
        // Deliver even canceled callbacks to verify the ownership guard, not just cancellation.
        new ArrayList<>(environment.fades).forEach(PlaybackAudioTestEnvironment.Fade::finish);
    }
}
