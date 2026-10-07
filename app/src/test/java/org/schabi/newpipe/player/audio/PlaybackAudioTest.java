package org.schabi.newpipe.player.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class PlaybackAudioTest {
    private PlaybackAudioTestEnvironment environment;
    private PlaybackAudio audio;

    @Before
    public void setUp() {
        environment = new PlaybackAudioTestEnvironment();
        environment.volume = 0.7f;
        audio = new PlaybackAudio(environment);
    }

    @Test
    public void playAndPrepareRequestFocusWithoutPerformingOrdinaryPlayback() {
        audio.onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);
        audio.onPlaybackEvent(PlaybackAudio.Event.PREPARED_PLAYING);
        assertEquals(List.of("request-focus", "request-focus"), environment.effects);
    }

    @Test
    public void pauseAbandonsFocusBeforeCallerPauses() {
        audio.onPlaybackEvent(PlaybackAudio.Event.PAUSE_REQUESTED);
        assertEquals(List.of("abandon-focus"), environment.effects);
    }

    @Test
    public void muteSuppressesFocusAndUnmuteRestoresLevelBeforeRequestingFocus() {
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        assertTrue(audio.isMuted());
        audio.onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);
        audio.onPlaybackEvent(PlaybackAudio.Event.PREPARED_PLAYING);
        assertEquals(List.of("volume:0.0", "abandon-focus"), environment.effects);
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
        assertFalse(audio.isMuted());
        assertEquals(List.of("volume:0.0", "abandon-focus", "volume:0.7", "request-focus"),
                environment.effects);
    }

    @Test
    public void gestureVolumeNeedsNoSeparateSaveBeforeFocusRestoration() {
        audio.setInternalVolume(0.6f);
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        assertEquals(0.2f, environment.volume, 0);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        environment.fades.get(0).finish();
        assertEquals(0.6f, environment.volume, 0);
    }

    @Test
    public void focusGainResumesOnlyWhenPreferenceEnabled() {
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        assertFalse(environment.effects.contains("play"));
        environment.resume = true;
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        assertEquals("play", environment.effects.get(environment.effects.size() - 1));
    }

    @Test
    public void lossesPausePlaybackWithoutAbandoningTheFocusRequest() {
        environment.focus(PlaybackAudio.FocusChange.LOSS);
        environment.focus(PlaybackAudio.FocusChange.TRANSIENT_LOSS);
        assertEquals(List.of("pause", "pause"), environment.effects);
    }

    @Test
    public void unavailableSetterLeavesActualVolumeAndRestoreLevelUnchanged() {
        environment.setAvailable = false;
        audio.setInternalVolume(0.4f);
        assertEquals(0.7f, audio.getInternalVolume(), 0);
        environment.focus(PlaybackAudio.FocusChange.DUCK);
        environment.focus(PlaybackAudio.FocusChange.GAIN);
        environment.fades.get(0).finish();
        assertEquals(0.7f, environment.volume, 0);
        assertTrue(environment.effects.contains("set-unavailable"));
    }

    @Test
    public void unavailableGetterReportsFullVolumeButMuteReadsActualOutput() {
        environment.volume = 0;
        environment.getAvailable = false;
        assertEquals(1, audio.getInternalVolume(), 0);
        assertTrue(audio.isMuted());
        assertTrue(environment.effects.contains("get-unavailable"));
    }

    @Test
    public void initializationReappliesCurrentMuteState() {
        audio.onPlaybackEvent(PlaybackAudio.Event.INITIALIZED);
        assertEquals(List.of("volume:0.7"), environment.effects);
    }

    @Test
    public void systemVolumeDoesNotChangeInternalVolumeOrFocus() {
        audio.setSystemVolume(0);
        assertEquals(0, audio.getSystemVolume());
        assertEquals(10, audio.getMaxSystemVolume());
        assertEquals(0.7f, audio.getInternalVolume(), 0);
        assertFalse(audio.isMuted());
        assertTrue(environment.effects.isEmpty());
    }

    @Test
    public void disposalAbandonsFocusBeforeClosingAndroidEffects() {
        audio.dispose();
        assertEquals(List.of("abandon-focus", "close"), environment.effects);
    }
}
