package org.schabi.newpipe.player.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.schabi.newpipe.player.history.PlaybackHistoryTestEnvironment.info;
import static org.schabi.newpipe.player.history.PlaybackHistoryTestEnvironment.queue;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.source.MediaSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.broadcast.AndroidPlayerBroadcasts;
import org.schabi.newpipe.player.cast.CastPlayback;
import org.schabi.newpipe.player.event.PlayerListeners;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.mediasource.LoadedMediaSource;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.ui.PlayerUiList;

import java.lang.reflect.Field;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** Checks accepted history writes through the actual player shutdown path. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackHistoryPlayerLifecycleTest {
    private MockedStatic<Uri> uris;
    private MockedStatic<TextUtils> text;

    @Before
    public void setUpAndroidMediaItems() {
        uris = mockStatic(Uri.class);
        final Uri uri = mock(Uri.class);
        uris.when(() -> Uri.parse(anyString())).thenReturn(uri);
        text = mockStatic(TextUtils.class);
        text.when(() -> TextUtils.isEmpty(any())).thenAnswer(call -> {
            final CharSequence value = call.getArgument(0);
            return value == null || value.length() == 0;
        });
    }

    @After
    public void closeAndroidMediaItems() {
        if (text != null) {
            text.close();
        }
        if (uris != null) {
            uris.close();
        }
    }

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
        final StreamInfoTag tag = StreamInfoTag.of(stream);
        final MediaSource child = mock(MediaSource.class);
        final MediaItem childItem = tag.asMediaItem();
        when(child.getMediaItem()).thenReturn(childItem);
        final LoadedMediaSource source = new LoadedMediaSource(
                child, tag, queue.getItem(), Long.MAX_VALUE);
        when(exoPlayer.getCurrentMediaItem()).thenReturn(source.getMediaItem());
        when(exoPlayer.getCurrentMediaItemIndex()).thenReturn(0);
        when(exoPlayer.getCurrentPosition()).thenReturn(8_000L);
        when(exoPlayer.getContentPosition()).thenReturn(7_000L);
        when(exoPlayer.getDuration()).thenReturn(120_000L);
        final Player player = mock(Player.class, CALLS_REAL_METHODS);
        setField(player, "casting", mock(CastPlayback.class));
        doReturn(false).when(player).isProgressLoopRunning();
        setField(player, "playbackHistory", history);
        setField(player, "currentMetadata", MediaItemTag.from(source.getMediaItem()).orElseThrow());
        setField(player, "playQueue", queue);
        setField(player, "simpleExoPlayer", exoPlayer);
        setField(player, "context", mock(Context.class));
        setField(player, "broadcastReceiver", mock(AndroidPlayerBroadcasts.class));
        setField(player, "listeners", new PlayerListeners());
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
        assertEquals(List.of(stream), environment.saved);
        assertEquals(List.of(8_000L), environment.positions);
        // Shutdown's explicit setRecovery captures the playback clock after the history save.
        assertEquals(8_000L, queue.getItem().getRecoveryPosition());
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
