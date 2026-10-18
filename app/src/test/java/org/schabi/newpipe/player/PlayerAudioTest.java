package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.util.Log;

import com.google.android.exoplayer2.ExoPlayer;

import org.junit.Test;
import org.mockito.InOrder;
import org.schabi.newpipe.player.audio.PlaybackAudio;
import org.schabi.newpipe.player.broadcast.AndroidPlayerBroadcasts;
import org.schabi.newpipe.player.cast.CastPlayback;
import org.schabi.newpipe.player.event.PlayerListeners;
import org.schabi.newpipe.player.history.PlaybackHistory;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.ui.PlayerUi;
import org.schabi.newpipe.player.ui.PlayerUiList;

import java.lang.reflect.Field;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;

public class PlayerAudioTest {
    @Test
    public void replayRequestsAudioBeforeChangingQueueAndPlaying() throws Exception {
        try (var log = mockStatic(Log.class)) {
            final Player player = mock(Player.class, CALLS_REAL_METHODS);
            set(player, "casting", mock(CastPlayback.class));
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
            set(player, "casting", mock(CastPlayback.class));
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
    public void refusedMuteReportsTheActualAudioStateToUi() throws Exception {
        try (var log = mockStatic(Log.class)) {
            final Player player = mock(Player.class, CALLS_REAL_METHODS);
            set(player, "casting", mock(CastPlayback.class));
            final PlaybackAudio audio = mock(PlaybackAudio.class);
            final PlayerUi ui = mock(PlayerUi.class);
            set(player, "simpleExoPlayer", mock(ExoPlayer.class));
            set(player, "playbackAudio", audio);
            set(player, "UIs", new PlayerUiList(ui));

            player.toggleMute();

            verify(audio).onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE);
            verify(ui).onMuteUnmuteChanged(false);
        }
    }

    @Test
    public void destructionDisposesAudioBeforeReleasingThePlayer() throws Exception {
        try (var log = mockStatic(Log.class)) {
            final Player player = mock(Player.class, CALLS_REAL_METHODS);
            set(player, "casting", mock(CastPlayback.class));
            final ExoPlayer exo = mock(ExoPlayer.class);
            final PlaybackAudio audio = mock(PlaybackAudio.class);
            doReturn(false).when(player).isProgressLoopRunning();
            set(player, "simpleExoPlayer", exo);
            set(player, "playbackAudio", audio);
            set(player, "playbackHistory", mock(PlaybackHistory.class));
            set(player, "context", mock(Context.class));
            set(player, "broadcastReceiver", mock(AndroidPlayerBroadcasts.class));
            set(player, "listeners", new PlayerListeners());
            set(player, "UIs", mock(PlayerUiList.class));
            set(player, "playbackDecision", new SerialDisposable());
            set(player, "progressUpdateDisposable",
                    new SerialDisposable());
            set(player, "streamItemDisposable",
                    new CompositeDisposable());

            player.destroy();

            final InOrder order = inOrder(audio, exo);
            order.verify(audio).dispose();
            order.verify(exo).release();
        }
    }

    @Test
    public void castPlaybackControlsNeverAcquirePhoneAudioFocus() throws Exception {
        final Player player = mock(Player.class, CALLS_REAL_METHODS);
        final CastPlayback cast = mock(CastPlayback.class);
        when(cast.isActive()).thenReturn(true);
        final ExoPlayer exo = mock(ExoPlayer.class);
        final PlaybackAudio audio = mock(PlaybackAudio.class);
        set(player, "casting", cast);
        set(player, "simpleExoPlayer", exo);
        set(player, "playbackAudio", audio);

        player.play();
        player.pause();
        player.seekTo(42000);

        verify(cast).play();
        verify(cast).pause();
        verify(cast).seek(42000);
        verifyNoInteractions(audio, exo);
    }

    @Test
    public void returningToPhoneDuringScrubbingRestoresAnOrdinaryPausedState() throws Exception {
        try (var log = mockStatic(Log.class)) {
            final Player player = mock(Player.class, CALLS_REAL_METHODS);
            final ExoPlayer exo = mock(ExoPlayer.class);
            when(exo.getPlaybackState()).thenReturn(ExoPlayer.STATE_READY);
            set(player, "casting", mock(CastPlayback.class));
            set(player, "simpleExoPlayer", exo);
            set(player, "currentState", Player.STATE_PAUSED_SEEK);
            set(player, "isPrepared", true);
            set(player, "UIs", mock(PlayerUiList.class));
            set(player, "progressUpdateDisposable", new SerialDisposable());
            doNothing().when(player).triggerProgressUpdate();

            player.restoreLocalAfterCasting(42000, false);

            assertEquals(Player.STATE_PAUSED, player.getCurrentState());
            verify(exo).seekTo(42000);
        }
    }

    private static void set(final Player player, final String name, final Object value)
            throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }
}
