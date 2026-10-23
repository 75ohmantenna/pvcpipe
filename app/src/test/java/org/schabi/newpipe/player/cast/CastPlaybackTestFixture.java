package org.schabi.newpipe.player.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaSource;

import org.junit.After;
import org.junit.Before;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.history.PlaybackHistory;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.mediasource.FailedMediaSource;
import org.schabi.newpipe.player.mediasource.LoadedMediaSource;
import org.schabi.newpipe.player.mediasource.ManagedMediaSourcePlaylist;
import org.schabi.newpipe.player.playback.MediaSourceManager;
import org.schabi.newpipe.player.playback.PlaybackListener;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.schabi.newpipe.util.SponsorBlock;
import org.schabi.newpipe.util.image.ImageStrategy;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** Controlled engine/Android/SDK boundaries kept separate from cast behavior assertions. */
public abstract class CastPlaybackTestFixture {
    protected final AtomicLong now = new AtomicLong(1000);
    private MockedStatic<SystemClock> clock;
    private MockedConstruction<FCastSession> sessions;
    protected CastPlayback playback;
    protected Player player;
    protected MockedConstruction<CastMediaResolver> resolvers;
    protected final TestScheduler io = new TestScheduler();
    protected final CastMedia media = mock(CastMedia.class);

    @Before
    public void setUp() throws Exception {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        resolvers = mockConstruction(CastMediaResolver.class, (resolver, context) ->
                when(resolver.resolve(any(), any(), any(InetAddress.class))).thenReturn(media));
        clock = mockStatic(SystemClock.class);
        clock.when(SystemClock::elapsedRealtime).thenAnswer(invocation -> now.get());
        sessions = mockConstruction(FCastSession.class, (session, arguments) -> {
            when(session.getConnectionState()).thenReturn(2);
            when(session.getLocalAddress()).thenReturn(
                    InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1}));
        });
        final Context context = mock(Context.class);
        final PowerManager power = mock(PowerManager.class);
        when(context.getSystemService(Context.POWER_SERVICE)).thenReturn(power);
        when(power.newWakeLock(anyInt(), anyString()))
                .thenReturn(mock(PowerManager.WakeLock.class));
        player = mock(Player.class);
        when(player.getContext()).thenReturn(context);
        when(player.isCurrentSourceSynchronized()).thenReturn(true);
        final ExoPlayer exo = mock(ExoPlayer.class);
        when(exo.getCurrentPosition()).thenReturn(10000L);
        when(player.getExoPlayer()).thenReturn(exo);
        when(player.getCurrentPosition()).thenReturn(10000L);
        when(player.UIs()).thenReturn(mock(PlayerUiList.class));
        final StreamInfo info = mock(StreamInfo.class);
        when(info.getDuration()).thenReturn(120L);
        when(info.getName()).thenReturn("Video");
        when(info.getThumbnails()).thenReturn(List.of());
        when(player.getCurrentStreamInfo()).thenReturn(Optional.of(info));
        playback = new CastPlayback(player);
        when(player.getPlaybackSpeed()).thenAnswer(invocation ->
                playback.isActive() ? playback.getSpeed() : 1.5f);
        set("active", true);
        final MediaItemTag tag = newTag();
        set("loadedTag", tag);
        set("receiverTag", tag);
        when(player.getCurrentMetadata()).thenReturn(tag);
        final PlayQueueItem item = mock(PlayQueueItem.class);
        when(player.getCurrentItem()).thenReturn(item);
        set("loadedItem", item);
    }

    @After
    public void tearDown() {
        playback.close();
        sessions.close();
        resolvers.close();
        clock.close();
        RxAndroidPlugins.reset();
        RxJavaPlugins.reset();
    }

    private Object getPlayerField(final String name) {
        try {
            final Field field = Player.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(player);
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    /**
     * Real manager/playlist/source tags and Player callbacks. Android Handler delivery and
     * engine source installation/decoding are controlled; cast resolver/SDK remain mocked.
     */
    protected final class ManagedRemoval implements AutoCloseable {
        private final TestScheduler callbacks = new TestScheduler();
        private final List<Runnable> completions = new ArrayList<>();
        private final MockedStatic<AndroidSchedulers> androidSchedulers =
                mockStatic(AndroidSchedulers.class);
        private final MockedStatic<Schedulers> schedulers =
                mockStatic(Schedulers.class, CALLS_REAL_METHODS);
        private final MockedStatic<Looper> loopers = mockStatic(Looper.class);
        private final MockedStatic<SponsorBlock> sponsorBlock = mockStatic(SponsorBlock.class);
        private final MockedConstruction<Handler> handlers;
        private final MediaSourceManager manager;
        boolean holdCompletions;

        ManagedRemoval(final InitialPreparation fixture) {
            androidSchedulers.when(AndroidSchedulers::mainThread).thenReturn(callbacks);
            schedulers.when(Schedulers::single).thenReturn(callbacks);
            schedulers.when(Schedulers::computation).thenReturn(callbacks);
            schedulers.when(Schedulers::io).thenReturn(io);
            loopers.when(Looper::getMainLooper).thenReturn(mock(Looper.class));
            handlers = mockConstruction(Handler.class, (handler, context) ->
                    when(handler.post(any(Runnable.class))).thenAnswer(call -> {
                        final Runnable action = call.getArgument(0);
                        if (holdCompletions) {
                            completions.add(action);
                        } else {
                            callbacks.scheduleDirect(action);
                        }
                        return true;
                    }));
            final PlaybackListener listener = mock(PlaybackListener.class);
            doAnswer(call -> {
                final StreamInfo info = call.getArgument(1);
                final MediaSource source = mock(MediaSource.class);
                final MediaItem item = new MediaItem.Builder()
                        .setUri(info.getUrl()).setTag(StreamInfoTag.of(info)).build();
                when(source.getMediaItem()).thenReturn(item);
                return source;
            }).when(listener).sourceOf(any(), any());
            doAnswer(call -> {
                player.onPlaybackBlock();
                return null;
            }).when(listener).onPlaybackBlock();
            doAnswer(call -> {
                player.onPlaybackSynchronize(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(listener).onPlaybackSynchronize(any(), anyBoolean());
            doCallRealMethod().when(player).onPlayQueueEdited();
            doAnswer(call -> {
                player.onPlayQueueEdited();
                return null;
            }).when(listener).onPlayQueueEdited();
            doReturn(Single.just(fixture.info)).when(fixture.first).getStream();
            doReturn(Single.just(fixture.info)).when(fixture.second).getStream();
            when(player.getExoPlayer().getCurrentMediaItemIndex()).thenReturn(0);
            fixture.queue.init();
            manager = new MediaSourceManager(player.getContext(), listener, fixture.queue);
            drain();
        }

        ManagedMediaSourcePlaylist playlist() throws Exception {
            final Field field = MediaSourceManager.class.getDeclaredField("playlist");
            field.setAccessible(true);
            return (ManagedMediaSourcePlaylist) field.get(manager);
        }

        void deliver(final MediaItem item) {
            when(player.getExoPlayer().getCurrentMediaItem()).thenReturn(item);
            player.onEvents(player.getExoPlayer(),
                    mock(com.google.android.exoplayer2.Player.Events.class));
        }

        void deliverRemoval(final MediaItem previous, final MediaItem item) {
            when(player.getExoPlayer().getCurrentMediaItem()).thenReturn(item);
            doCallRealMethod().when(player).onPositionDiscontinuity(any(), any(), anyInt());
            final PlaybackHistory history = (PlaybackHistory) getPlayerField("playbackHistory");
            doCallRealMethod().when(history).onDiscontinuity(any(), any());
            player.onPositionDiscontinuity(
                    new com.google.android.exoplayer2.Player.PositionInfo(
                            null, 0, previous, null, 0, 120000, 120000, -1, -1),
                    new com.google.android.exoplayer2.Player.PositionInfo(
                            null, 0, item, null, 0, 10000, 10000, -1, -1),
                    com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_REMOVE);
            deliver(item);
        }

        void drain() {
            callbacks.triggerActions();
            callbacks.advanceTimeBy(1, TimeUnit.SECONDS);
        }

        void completeRemoval() {
            final List<Runnable> pending = new ArrayList<>(completions);
            completions.clear();
            holdCompletions = false;
            pending.forEach(Runnable::run);
            drain();
        }

        @Override
        public void close() {
            manager.dispose();
            handlers.close();
            sponsorBlock.close();
            loopers.close();
            schedulers.close();
            androidSchedulers.close();
        }
    }

    /**
     * Real Player selection/events/getters, queue occurrences and FailedMediaSource metadata.
     * Source extraction and FCast transport retain the class's construction mocks; Android
     * URI/text/logging/image selection and error-notification delivery are the extra boundaries.
     * No ExoPlayer decoding, media-source preparation or receiver/device is exercised here.
     */
    protected final class InitialPreparation implements AutoCloseable {
        private final MockedStatic<Uri> uris = mockStatic(Uri.class);
        private final MockedStatic<TextUtils> text = mockStatic(TextUtils.class);
        private final MockedStatic<Log> logs = mockStatic(Log.class);
        private final MockedStatic<ImageStrategy> images = mockStatic(ImageStrategy.class);
        private final MockedStatic<ErrorUtil> errors = mockStatic(ErrorUtil.class);
        private final MockedConstruction<ErrorInfo> errorInfos = mockConstruction(ErrorInfo.class);
        private final MockedStatic<Toast> toasts = mockStatic(Toast.class);
        private final MockedStatic<PlayerHelper> playerHelpers = mockStatic(PlayerHelper.class);
        final StreamInfo info = player.getCurrentStreamInfo().orElseThrow();
        final SinglePlayQueue queue;
        final PlayQueueItem first;
        final PlayQueueItem second;

        InitialPreparation() throws Exception {
            uris.when(() -> Uri.parse(anyString())).thenReturn(mock(Uri.class));
            text.when(() -> TextUtils.isEmpty(any())).thenAnswer(invocation -> {
                final CharSequence value = invocation.getArgument(0);
                return value == null || value.length() == 0;
            });
            toasts.when(() -> Toast.makeText(any(Context.class), anyString(), anyInt()))
                    .thenReturn(mock(Toast.class));
            when(info.getUrl()).thenReturn("https://example.invalid/video");
            first = spy(new PlayQueueItem(info));
            second = spy(new PlayQueueItem(info));
            assertNotSame(first, second);
            assertEquals(first.getUrl(), second.getUrl());
            queue = new SinglePlayQueue(first);
            queue.append(List.of(second));
            final ExoPlayer exo = player.getExoPlayer();
            final Timeline timeline = mock(Timeline.class);
            when(timeline.getWindowCount()).thenReturn(2);
            when(exo.getCurrentTimeline()).thenReturn(timeline);
            // The queue sync path need not exercise a decoder or thumbnail clearing.
            when(player.isPlaying()).thenReturn(true);
            when(exo.getCurrentMediaItemIndex()).thenReturn(1);
            setPlayer("simpleExoPlayer", exo);
            setPlayer("casting", playback);
            setPlayer("playQueue", queue);
            setPlayer("currentItem", first);
            final MediaItemTag initialTag = newTag();
            when(initialTag.getMaybeStreamInfo()).thenReturn(Optional.of(info));
            setPlayer("currentMetadata", initialTag);
            ownSource(initialTag, first);
            final MediaItem initialMedia = new MediaItem.Builder()
                    .setUri(initialTag.getStreamUrl()).setTag(initialTag).build();
            when(exo.getCurrentMediaItem()).thenReturn(initialMedia);
            doCallRealMethod().when(player).getCurrentMetadata();
            doCallRealMethod().when(player).getCurrentStreamInfo();
            doCallRealMethod().when(player).getCurrentItem();
            doCallRealMethod().when(player).isCurrentSourceSynchronized();
            doCallRealMethod().when(player).exoPlayerIsNull();
            doCallRealMethod().when(player).onPlaybackSynchronize(any(), anyBoolean());
            doCallRealMethod().when(player).onEvents(any(), any());
            doCallRealMethod().when(player).onPlaybackBlock();
            for (final String field : List.of("thumbnail", "playbackHistory", "listeners",
                    "sponsorBlock")) {
                setPlayer(field, mock(Player.class.getDeclaredField(field).getType()));
            }
            setPlayer("UIs", player.UIs());
            when(player.getPlayWhenReady()).thenReturn(true);
            set("active", false);
            set("loadedTag", null);
            set("receiverTag", null);
            set("loadedItem", null);
        }

        CastMediaResolver begin() {
            playback.onSourceChanged();
            assertTrue(playback.isPreparing());
            assertFalse(playback.isActive());
            return resolvers.constructed().get(0);
        }

        void handoff() {
            begin();
            io.triggerActions();
            clearInvocations(player, playback.getSession());
        }

        void selectSecond() {
            queue.setIndex(1);
            player.onPlaybackSynchronize(second, false);
            assertSame(second, player.getCurrentItem());
        }

        void advanceLocally(final MediaItemTag tag) {
            final MediaItem next = new MediaItem.Builder()
                    .setUri(tag.getStreamUrl()).setTag(tag).build();
            when(player.getExoPlayer().getCurrentMediaItem()).thenReturn(next);
            doCallRealMethod().when(player).onPositionDiscontinuity(any(), any(), anyInt());
            final PlaybackHistory history = (PlaybackHistory) getPlayerField("playbackHistory");
            doCallRealMethod().when(history).onDiscontinuity(any(), any());
            player.onPositionDiscontinuity(
                    new com.google.android.exoplayer2.Player.PositionInfo(
                            null, 0, null, null, 0, 120000, 120000, -1, -1),
                    new com.google.android.exoplayer2.Player.PositionInfo(
                            null, 1, next, null, 0, 10000, 10000, -1, -1),
                    com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
            assertSame(second, queue.getItem());
        }

        void deliverSuccess(final MediaItemTag tag) {
            when(tag.getMaybeStreamInfo()).thenReturn(Optional.of(info));
            if (tag.getMaybeExtras(Object.class).isEmpty()) {
                ownSource(tag, queue.getItem());
            }
            final MediaItem item = new MediaItem.Builder()
                    .setUri(tag.getStreamUrl()).setTag(tag).build();
            when(player.getExoPlayer().getCurrentMediaItem()).thenReturn(item);
            player.onEvents(player.getExoPlayer(),
                    mock(com.google.android.exoplayer2.Player.Events.class));
        }

        void deliverFailure(final PlayQueueItem item) {
            final FailedMediaSource failed = new FailedMediaSource(item,
                    new FailedMediaSource.StreamInfoLoadException(
                            new IllegalStateException("selected occurrence failed")),
                    Long.MAX_VALUE);
            final MediaItem failedItem = failed.getMediaItem();
            when(player.getExoPlayer().getCurrentMediaItem()).thenReturn(failedItem);
            player.onEvents(player.getExoPlayer(),
                    mock(com.google.android.exoplayer2.Player.Events.class));
            assertSame(MediaItemTag.from(failedItem).orElseThrow(), player.getCurrentMetadata());
            assertSame(failed, player.getCurrentMetadata()
                    .getMaybeExtras(FailedMediaSource.class).orElseThrow());
            assertTrue(player.getCurrentStreamInfo().isEmpty());
        }

        @Override
        public void close() {
            queue.dispose();
            playerHelpers.close();
            toasts.close();
            errors.close();
            errorInfos.close();
            images.close();
            logs.close();
            text.close();
            uris.close();
        }
    }

    protected void ownSource(final MediaItemTag tag, final PlayQueueItem item) {
        final LoadedMediaSource source = mock(LoadedMediaSource.class);
        when(source.getStream()).thenReturn(item);
        when(tag.getMaybeExtras(Object.class)).thenReturn(Optional.of(source));
    }

    protected void setPlayer(final String name, final Object value) throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    protected MediaItemTag newTag() {
        final MediaItemTag tag = mock(MediaItemTag.class);
        when(tag.getStreamUrl()).thenReturn("https://example.invalid/video");
        if (player != null && player.getCurrentItem() != null) {
            ownSource(tag, player.getCurrentItem());
        }
        return tag;
    }

    protected void set(final String name, final Object value) throws Exception {
        final Field field = CastPlayback.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(playback, value);
    }
}
