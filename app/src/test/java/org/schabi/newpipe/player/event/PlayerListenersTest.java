package org.schabi.newpipe.player.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;

import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.playqueue.PlayQueue;

import java.util.ArrayList;
import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlayerListenersTest {
    private final List<String> events = new ArrayList<>();
    private final PlayerListeners listeners = new PlayerListeners();
    private final RecordingFragment fragment = new RecordingFragment("fragment");
    private final RecordingActivity activity = new RecordingActivity("activity");
    private final PlayQueue queue = mock(PlayQueue.class);

    @Test
    public void updatesReachTheFragmentBeforeTheActivity() {
        listeners.setFragmentListener(fragment);
        listeners.setActivityListener(activity);

        listeners.onQueueUpdate(queue);
        listeners.onMetadataUpdate(mock(StreamInfo.class), queue);
        listeners.onPlaybackUpdate(1, 2, true, PlaybackParameters.DEFAULT);
        listeners.onProgressUpdate(3, 4, 5);
        listeners.onAudioTrackUpdate();

        assertEquals(List.of(
                "fragment queue", "activity queue",
                "fragment metadata", "activity metadata",
                "fragment playback 1 2 true", "activity playback 1 2 true",
                "fragment progress 3 4 5", "activity progress 3 4 5",
                "fragment audio track", "activity audio track"), events);
    }

    @Test
    public void nothingHappensWithoutListeners() {
        listeners.onQueueUpdate(queue);
        listeners.onMetadataUpdate(mock(StreamInfo.class), queue);
        listeners.onPlaybackUpdate(1, 2, true, PlaybackParameters.DEFAULT);
        listeners.onProgressUpdate(3, 4, 5);
        listeners.onAudioTrackUpdate();
        listeners.onPlayerError(mock(PlaybackException.class), false);
        listeners.onServiceStopped();

        assertEquals(List.of(), events);
        assertFalse(listeners.getFragmentListener().isPresent());
    }

    @Test
    public void playerErrorsOnlyReachTheFragment() {
        listeners.setFragmentListener(fragment);
        listeners.setActivityListener(activity);

        listeners.onPlayerError(mock(PlaybackException.class), true);

        assertEquals(List.of("fragment error true"), events);
    }

    @Test
    public void removingAReplacedListenerKeepsItsReplacement() {
        final RecordingFragment newFragment = new RecordingFragment("new fragment");
        final RecordingActivity newActivity = new RecordingActivity("new activity");
        listeners.setFragmentListener(fragment);
        listeners.setActivityListener(activity);
        listeners.setFragmentListener(newFragment);
        listeners.setActivityListener(newActivity);

        listeners.removeFragmentListener(fragment);
        listeners.removeActivityListener(activity);
        listeners.onAudioTrackUpdate();

        assertSame(newFragment, listeners.getFragmentListener().orElse(null));
        assertEquals(List.of("new fragment audio track", "new activity audio track"), events);
    }

    @Test
    public void removedListenersGetNothingMore() {
        listeners.setFragmentListener(fragment);
        listeners.setActivityListener(activity);

        listeners.removeFragmentListener(fragment);
        listeners.removeActivityListener(activity);
        listeners.onAudioTrackUpdate();
        listeners.onServiceStopped();

        assertEquals(List.of(), events);
        assertFalse(listeners.getFragmentListener().isPresent());
    }

    @Test
    public void serviceStopIsAnnouncedOnceAndForgetsBothListeners() {
        listeners.setFragmentListener(fragment);
        listeners.setActivityListener(activity);

        listeners.onServiceStopped();
        listeners.onServiceStopped();
        listeners.onAudioTrackUpdate();

        assertEquals(List.of("fragment stopped", "activity stopped"), events);
        assertFalse(listeners.getFragmentListener().isPresent());
    }

    private class RecordingActivity implements PlayerEventListener {
        final String name;

        RecordingActivity(final String name) {
            this.name = name;
        }

        @Override
        public void onQueueUpdate(final PlayQueue playQueue) {
            events.add(name + " queue");
        }

        @Override
        public void onPlaybackUpdate(final int state, final int repeatMode,
                                     final boolean shuffled,
                                     final PlaybackParameters parameters) {
            events.add(name + " playback " + state + " " + repeatMode + " " + shuffled);
        }

        @Override
        public void onProgressUpdate(final int currentProgress, final int duration,
                                     final int bufferPercent) {
            events.add(name + " progress " + currentProgress + " " + duration + " "
                    + bufferPercent);
        }

        @Override
        public void onMetadataUpdate(final StreamInfo info, final PlayQueue playQueue) {
            events.add(name + " metadata");
        }

        @Override
        public void onAudioTrackUpdate() {
            events.add(name + " audio track");
        }

        @Override
        public void onServiceStopped() {
            events.add(name + " stopped");
        }
    }

    private final class RecordingFragment extends RecordingActivity
            implements PlayerServiceEventListener {
        RecordingFragment(final String name) {
            super(name);
        }

        @Override
        public void onViewCreated() {
            events.add(name + " view created");
        }

        @Override
        public void onFullscreenStateChanged(final boolean fullscreen) {
            events.add(name + " fullscreen " + fullscreen);
        }

        @Override
        public void onScreenRotationButtonClicked() {
            events.add(name + " rotation button");
        }

        @Override
        public void onMoreOptionsLongClicked() {
            events.add(name + " more options");
        }

        @Override
        public void onPlayerError(final PlaybackException error,
                                  final boolean isCatchableException) {
            events.add(name + " error " + isCatchableException);
        }

        @Override
        public void hideSystemUiIfNeeded() {
            events.add(name + " hide system ui");
        }
    }
}
