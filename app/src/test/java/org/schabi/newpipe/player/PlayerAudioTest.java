package org.schabi.newpipe.player;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.util.Log;

import com.google.android.exoplayer2.ExoPlayer;

import org.junit.Test;
import org.mockito.InOrder;
import org.schabi.newpipe.player.audio.PlaybackAudio;
import org.schabi.newpipe.player.history.PlaybackHistory;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.ui.PlayerUi;
import org.schabi.newpipe.player.ui.PlayerUiList;

import java.lang.reflect.Field;

public class PlayerAudioTest {
    @Test
    public void replayRequestsAudioBeforeChangingQueueAndPlaying() throws Exception {
        try (var log = mockStatic(Log.class)) {
            final Player player = mock(Player.class, CALLS_REAL_METHODS);
            final ExoPlayer exo = mock(ExoPlayer.class);
            final PlaybackAudio audio = mock(PlaybackAudio.class);
            final PlayQueue queue = mock(PlayQueue.class);
            set(player, "simpleExoPlayer", exo);
            set(player, "playbackAudio", audio);
            set(player, "playQueue", queue);
            set(player, "currentState", Player.STATE_COMPLETED);
            set(player, "playbackHistory", mock(PlaybackHistory.class));
            when(queue.getIndex()).thenReturn(2);

            player.play();

            final InOrder order = inOrder(audio, queue, exo);
            order.verify(audio).onPlaybackEvent(PlaybackAudio.Event.PLAY_REQUESTED);
            order.verify(queue).setIndex(0);
            order.verify(exo).play();
        }
    }

    @Test
    public void pauseAbandonsAudioBeforePausingThePlayer() throws Exception {
        try (var log = mockStatic(Log.class)) {
            final Player player = mock(Player.class, CALLS_REAL_METHODS);
            final ExoPlayer exo = mock(ExoPlayer.class);
            final PlaybackAudio audio = mock(PlaybackAudio.class);
            set(player, "simpleExoPlayer", exo);
            set(player, "playbackAudio", audio);
            set(player, "playbackHistory", mock(PlaybackHistory.class));

            player.pause();

            final InOrder order = inOrder(audio, exo);
            order.verify(audio).onPlaybackEvent(PlaybackAudio.Event.PAUSE_REQUESTED);
            order.verify(exo).pause();
        }
    }

    @Test
    public void muteRoutesIntentAndReportsRequestedMuteToUi() throws Exception {
        try (var log = mockStatic(Log.class)) {
            final Player player = mock(Player.class, CALLS_REAL_METHODS);
            final PlaybackAudio audio = mock(PlaybackAudio.class);
            final PlayerUi ui = mock(PlayerUi.class);
            set(player, "simpleExoPlayer", mock(ExoPlayer.class));
            set(player, "playbackAudio", audio);
            set(player, "UIs", new PlayerUiList(ui));

            player.toggleMute();

            verify(audio).onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
            verify(ui).onMuteUnmuteChanged(true);
        }
    }

    private static void set(final Player player, final String name, final Object value)
            throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }
}
