package org.schabi.newpipe.player.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.schabi.newpipe.player.history.PlaybackHistoryTestEnvironment.info;
import static org.schabi.newpipe.player.history.PlaybackHistoryTestEnvironment.queue;

import android.content.Context;
import android.util.Log;

import com.google.android.exoplayer2.ExoPlayer;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.ui.PlayerUiList;

import java.lang.reflect.Field;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** Checks accepted history writes through the actual player shutdown path. */
public class PlaybackHistoryPlayerLifecycleTest {
    @Test
    public void destroyPersistsTheFinalCheckpointAfterPlaybackResourcesAreReleased()
            throws Exception {
        final TestScheduler scheduler = new TestScheduler();
        final PlaybackHistoryTestEnvironment environment = new PlaybackHistoryTestEnvironment();
        environment.saves.add(Completable.complete().subscribeOn(scheduler));
        final PlaybackHistory history = new PlaybackHistory(environment);
        final StreamInfo stream = info("shutdown");
        final PlayQueue queue = queue(stream);
        final ExoPlayer exoPlayer = mock(ExoPlayer.class);
        when(exoPlayer.getCurrentMediaItemIndex()).thenReturn(0);
        when(exoPlayer.getCurrentPosition()).thenReturn(8_000L);
        when(exoPlayer.getContentPosition()).thenReturn(8_000L);
        when(exoPlayer.getDuration()).thenReturn(120_000L);
        final Player player = mock(Player.class, CALLS_REAL_METHODS);
        doReturn(false).when(player).isProgressLoopRunning();
        setField(player, "playbackHistory", history);
        setField(player, "currentMetadata", StreamInfoTag.of(stream));
        setField(player, "playQueue", queue);
        setField(player, "simpleExoPlayer", exoPlayer);
        setField(player, "context", mock(Context.class));
        setField(player, "UIs", mock(PlayerUiList.class));
        setField(player, "playbackDecision", new SerialDisposable());
        setField(player, "progressUpdateDisposable", new SerialDisposable());
        setField(player, "streamItemDisposable", new CompositeDisposable());

        try (MockedStatic<Log> logs = mockStatic(Log.class)) {
            player.destroy();
        }

        verify(exoPlayer).stop();
        verify(exoPlayer).release();
        assertTrue(queue.isDisposed());
        assertNull(environment.persistedPosition);
        scheduler.triggerActions();
        assertEquals(Long.valueOf(8_000L), environment.persistedPosition);
    }

    private static void setField(final Player player, final String name, final Object value)
            throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }
}
