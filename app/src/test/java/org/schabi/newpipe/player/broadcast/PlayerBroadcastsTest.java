package org.schabi.newpipe.player.broadcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.App;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class PlayerBroadcastsTest {
    private static final String PLAYER = App.PACKAGE_NAME + ".player.MainPlayer.";
    // The doubled segment is what the notification actions have always been called.
    private static final String NOTIFICATION = PLAYER + ".player.MainPlayer.";
    private static final String FRAGMENT = App.PACKAGE_NAME + ".VideoDetailFragment.";

    private final List<String> called = new ArrayList<>();
    private final PlayerBroadcasts broadcasts = new PlayerBroadcasts(new RecordingCommands());

    @Test
    public void registersForEveryActionThePlayerAndItsUisHandle() {
        assertEquals(Set.of(
                "android.media.AUDIO_BECOMING_NOISY",
                PLAYER + "CLOSE",
                NOTIFICATION + "PLAY_PAUSE",
                NOTIFICATION + "ACTION_PLAY_PREVIOUS",
                NOTIFICATION + "ACTION_PLAY_NEXT",
                NOTIFICATION + "ACTION_FAST_REWIND",
                NOTIFICATION + "ACTION_FAST_FORWARD",
                NOTIFICATION + "REPEAT",
                NOTIFICATION + "ACTION_SHUFFLE",
                NOTIFICATION + "ACTION_RECREATE_NOTIFICATION",
                FRAGMENT + "ACTION_VIDEO_FRAGMENT_RESUMED",
                FRAGMENT + "ACTION_VIDEO_FRAGMENT_STOPPED",
                "android.intent.action.CONFIGURATION_CHANGED",
                "android.intent.action.SCREEN_ON",
                "android.intent.action.SCREEN_OFF",
                "android.intent.action.HEADSET_PLUG"),
                Set.copyOf(PlayerBroadcasts.actions()));
        assertEquals(16, PlayerBroadcasts.actions().size());
    }

    @Test
    public void eachPlayerActionRunsItsOwnCommand() {
        assertCommand("android.media.AUDIO_BECOMING_NOISY", "pause");
        assertCommand(PLAYER + "CLOSE", "close");
        assertCommand(NOTIFICATION + "PLAY_PAUSE", "playPause");
        assertCommand(NOTIFICATION + "ACTION_PLAY_PREVIOUS", "playPrevious");
        assertCommand(NOTIFICATION + "ACTION_PLAY_NEXT", "playNext");
        assertCommand(NOTIFICATION + "ACTION_FAST_REWIND", "fastRewind");
        assertCommand(NOTIFICATION + "ACTION_FAST_FORWARD", "fastForward");
        assertCommand(NOTIFICATION + "REPEAT", "cycleNextRepeatMode");
        assertCommand(NOTIFICATION + "ACTION_SHUFFLE", "toggleShuffleModeEnabled");
    }

    @Test
    public void actionsMeantForThePlayerUisRunNoCommand() {
        broadcasts.onBroadcast(NOTIFICATION + "ACTION_RECREATE_NOTIFICATION");
        broadcasts.onBroadcast(FRAGMENT + "ACTION_VIDEO_FRAGMENT_RESUMED");
        broadcasts.onBroadcast(FRAGMENT + "ACTION_VIDEO_FRAGMENT_STOPPED");
        broadcasts.onBroadcast("android.intent.action.CONFIGURATION_CHANGED");
        broadcasts.onBroadcast("android.intent.action.HEADSET_PLUG");
        broadcasts.onBroadcast("some.other.ACTION");
        broadcasts.onBroadcast(null);

        assertEquals(List.of(), called);
        assertTrue(broadcasts.isScreenOn());
    }

    @Test
    public void screenBroadcastsAreRemembered() {
        assertTrue(broadcasts.isScreenOn());

        broadcasts.onBroadcast("android.intent.action.SCREEN_OFF");
        assertFalse(broadcasts.isScreenOn());

        broadcasts.onBroadcast("android.intent.action.SCREEN_ON");
        assertTrue(broadcasts.isScreenOn());

        assertEquals(List.of(), called);
    }

    private void assertCommand(final String action, final String command) {
        called.clear();
        broadcasts.onBroadcast(action);
        assertEquals(action, List.of(command), called);
    }

    private final class RecordingCommands implements PlayerBroadcasts.Commands {
        @Override
        public void pause() {
            called.add("pause");
        }

        @Override
        public void close() {
            called.add("close");
        }

        @Override
        public void playPause() {
            called.add("playPause");
        }

        @Override
        public void playPrevious() {
            called.add("playPrevious");
        }

        @Override
        public void playNext() {
            called.add("playNext");
        }

        @Override
        public void fastRewind() {
            called.add("fastRewind");
        }

        @Override
        public void fastForward() {
            called.add("fastForward");
        }

        @Override
        public void cycleNextRepeatMode() {
            called.add("cycleNextRepeatMode");
        }

        @Override
        public void toggleShuffleModeEnabled() {
            called.add("toggleShuffleModeEnabled");
        }
    }
}
