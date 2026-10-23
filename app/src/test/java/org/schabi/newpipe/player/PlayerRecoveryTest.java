package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.analytics.PlayerId;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.SinglePeriodTimeline;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.cast.CastPlayback;
import org.schabi.newpipe.player.history.PlaybackHistory;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.mediasource.FailedMediaSource;
import org.schabi.newpipe.player.mediasource.LoadedMediaSource;
import org.schabi.newpipe.player.mediasource.ManagedMediaSourcePlaylist;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueEvent.SelectEvent;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.resolver.PlaybackSources;
import org.schabi.newpipe.util.image.ImageStrategy;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/**
 * Exercises recovery storage and its eventual seek through real Player and queue methods.
 * Android services, decoding, source resolution and manager reload are mocked boundaries.
 * The real queue's observeOn is held on a TestScheduler; its selection callback stands in for
 * the manager's later source-ready notification, not for a test of MediaSourceManager itself.
 * LoadedMediaSource publishes real SinglePeriodTimelines to registered source callers; only the
 * Android preparation/decoder boundary is controlled. Engine current items come from those windows,
 * not the wrapper's pre-preparation getMediaItem(). PlaybackHistory and its Android environment
 * are real too: only preferences and the database-facing record manager are mocked.
 */
public class PlayerRecoveryTest {
    private static final long PLAYING_POSITION = 45_000L;
    private static final long SELECTED_RECOVERY = 12_000L;

    private final TestScheduler mainThread = new TestScheduler();
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private Player player;
    private ExoPlayer exoPlayer;
    private PlayQueue queue;
    private CastPlayback casting;
    private StreamInfo stream;
    private SharedPreferences preferences;
    private Function<Callable<Scheduler>, Scheduler> previousMainInitializer;
    private MockedStatic<AndroidSchedulers> androidSchedulers;
    private MockedStatic<PreferenceManager> preferenceManager;
    private MockedStatic<Uri> uris;
    private MockedStatic<TextUtils> text;
    private MockedStatic<Log> logs;
    private MockedStatic<ImageStrategy> images;
    private MockedConstruction<HistoryRecordManager> records;

    @Before
    public void setUp() throws Exception {
        previousMainInitializer = RxAndroidPlugins.getInitMainThreadSchedulerHandler();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> mainThread);
        androidSchedulers = mockStatic(AndroidSchedulers.class);
        androidSchedulers.when(AndroidSchedulers::mainThread).thenReturn(mainThread);
        uris = mockStatic(Uri.class);
        final Uri uri = mock(Uri.class);
        uris.when(() -> Uri.parse(anyString())).thenReturn(uri);
        text = mockStatic(TextUtils.class);
        text.when(() -> TextUtils.isEmpty(any())).thenAnswer(call -> {
            final CharSequence value = call.getArgument(0);
            return value == null || value.length() == 0;
        });
        logs = mockStatic(Log.class);
        images = mockStatic(ImageStrategy.class);
        records = mockConstruction(HistoryRecordManager.class);
        preferences = mock(SharedPreferences.class);
        final Context context = mock(Context.class);
        when(context.getString(R.string.enable_watch_history_key)).thenReturn("watch_history");
        when(preferences.getBoolean("watch_history", true)).thenReturn(false);
        preferenceManager = mockStatic(PreferenceManager.class);
        preferenceManager.when(() -> PreferenceManager.getDefaultSharedPreferences(context))
                .thenReturn(preferences);

        player = mock(Player.class, CALLS_REAL_METHODS);
        exoPlayer = mock(ExoPlayer.class);
        final Timeline timeline = mock(Timeline.class);
        when(timeline.getWindowCount()).thenReturn(2);
        when(exoPlayer.getCurrentTimeline()).thenReturn(timeline);
        when(exoPlayer.getCurrentPosition()).thenReturn(PLAYING_POSITION);
        when(exoPlayer.getContentPosition()).thenReturn(PLAYING_POSITION);
        when(exoPlayer.getDuration()).thenReturn(120_000L);
        when(exoPlayer.getCurrentMediaItemIndex()).thenReturn(0);
        setField("simpleExoPlayer", exoPlayer);
        casting = mock(CastPlayback.class);
        setField("casting", casting);
        setField("playbackSources", mock(PlaybackSources.class));
        setField("playbackHistory", new PlaybackHistory(context));
        setField("thumbnail", mock(PlayerThumbnail.class));
        // Do not construct a manager/network pipeline. Selection delivery below supplies its
        // delayed readiness callback, while the Player's quality/audio/recovery code stays real.
        doNothing().when(player).reloadPlayQueueManager();
    }

    @After
    public void tearDown() {
        subscriptions.dispose();
        if (queue != null) {
            queue.dispose();
        }
        try {
            // Disabled persistent history must not be used to manufacture a successful resume.
            assertEquals(1, records.constructed().size());
            verifyNoInteractions(records.constructed().get(0));
        } finally {
            preferenceManager.close();
            records.close();
            images.close();
            logs.close();
            text.close();
            uris.close();
            androidSchedulers.close();
            RxAndroidPlugins.setInitMainThreadSchedulerHandler(previousMainInitializer);
        }
    }

    @Test
    public void pendingNavigationThenQualityKeepsSelectedRecoveryAndSeeksIt() throws Exception {
        pendingNavigation(true, SELECTED_RECOVERY);
    }

    @Test
    public void pendingNavigationThenQualityKeepsUnsetRecoveryAndSeeksDefault() throws Exception {
        pendingNavigation(true, PlayQueueItem.RECOVERY_UNSET);
    }

    @Test
    public void pendingNavigationThenAudioTrackKeepsSelectedRecoveryAndSeeksIt() throws Exception {
        pendingNavigation(false, SELECTED_RECOVERY);
    }

    @Test
    public void pendingNavigationThenAudioTrackKeepsUnsetRecoveryAndSeeksDefault()
            throws Exception {
        pendingNavigation(false, PlayQueueItem.RECOVERY_UNSET);
    }

    @Test
    public void alignedQualityCapturesCurrentPositionAndConsumesItOnSynchronization()
            throws Exception {
        alignedCapture(true);
    }

    @Test
    public void alignedAudioTrackCapturesCurrentPositionAndConsumesItOnSynchronization()
            throws Exception {
        alignedCapture(false);
    }

    @Test
    public void directAlignedRecoveryCapturesPreparedSourceClockWithoutHistory()
            throws Exception {
        start();
        queue.setRecovery(0, SELECTED_RECOVERY);

        player.setRecovery();

        assertEquals(PLAYING_POSITION, queue.getItem().getRecoveryPosition());
        verifyNoInteractions(preferences);
        queue.setIndex(0);
        synchronizeAndAssert(0, PLAYING_POSITION);
    }

    @Test
    public void initialTimelineRetainsOwnershipAndChildPlaybackConfiguration() throws Exception {
        start();
        final SourcePublication source = sourcePublication(queue.getItem());

        assertOwnedTimeline(source.childTimeline, source.source.getInitialTimeline(),
                queue.getItem());
    }

    @Test
    public void preparedTimelineRetainsOwnershipAndChildPlaybackConfiguration() throws Exception {
        start();
        final SourcePublication source = sourcePublication(queue.getItem());

        assertOwnedTimeline(source.childTimeline, source.publish(), queue.getItem());
    }

    @Test
    public void placeholderExtrasCannotOverwriteRecoveryAndSynchronizationStillResumes()
            throws Exception {
        start();
        final ManagedMediaSourcePlaylist playlist = new ManagedMediaSourcePlaylist();
        playlist.expand(0, 1);
        final MediaSource placeholder = playlist.getParentMediaSource().getMediaSource(0);
        final MediaItem placeholderItem = placeholder.getMediaItem();
        unownedSourcePreservesRecovery(placeholderItem);
    }

    @Test
    public void failedSourceExtrasCannotOverwriteRecoveryAndSynchronizationStillResumes()
            throws Exception {
        start();
        final FailedMediaSource failed = new FailedMediaSource(queue.getItem(),
                new FailedMediaSource.MediaSourceResolutionException("unresolvable"),
                Long.MAX_VALUE);
        final MediaItem failedItem = failed.getMediaItem();
        unownedSourcePreservesRecovery(failedItem);
    }

    @Test
    public void alignedActiveCastRecoveryCapturesReceiverPositionAndSeeksIt() throws Exception {
        start();
        when(casting.isActive()).thenReturn(true);
        when(casting.getPosition()).thenReturn(73_000L);
        when(casting.getDuration()).thenReturn(120_000L);
        queue.setRecovery(0, SELECTED_RECOVERY);

        player.setRecovery();

        assertEquals(73_000L, queue.getItem().getRecoveryPosition());
        verify(casting).getPosition();
        queue.setIndex(0);
        synchronizeAndAssert(0, 73_000L);
    }

    @Test
    public void pendingSameUrlOccurrenceCannotStealActiveReceiverClock() throws Exception {
        start();
        final PlayQueueItem playing = queue.getItem();
        final PlayQueueItem selected = new PlayQueueItem(stream);
        queue.append(List.of(selected));
        queue.setRecovery(1, SELECTED_RECOVERY);
        queue.setIndex(1);
        when(casting.isActive()).thenReturn(true);
        when(casting.getPosition()).thenReturn(73_000L);
        when(casting.getDuration()).thenReturn(120_000L);
        assertNotSame(playing, selected);
        assertEquals(playing.getUrl(), selected.getUrl());

        player.setRecovery();

        assertEquals(SELECTED_RECOVERY, selected.getRecoveryPosition());
        assertEquals(PlayQueueItem.RECOVERY_UNSET, playing.getRecoveryPosition());
        verify(casting, never()).getPosition();
        synchronizeAndAssert(1, SELECTED_RECOVERY);
    }

    @Test
    public void loadedSourceOwnershipAllowsCaptureDespiteStaleCurrentItemAndNumericalIndex()
            throws Exception {
        start();
        final PlayQueueItem selected = new PlayQueueItem(stream);
        queue.append(List.of(selected));
        queue.setRecovery(1, SELECTED_RECOVERY);
        queue.setIndex(1);
        final MediaItem selectedSource = loadedItem(selected);
        when(exoPlayer.getCurrentMediaItem()).thenReturn(selectedSource);
        assertEquals(0, exoPlayer.getCurrentMediaItemIndex());
        assertEquals(1, queue.getIndex());

        player.setRecovery();

        assertEquals(PLAYING_POSITION, selected.getRecoveryPosition());
        synchronizeAndAssert(1, PLAYING_POSITION);
    }

    @Test
    public void equalIndicesAndSelectedCurrentItemCannotAuthorizeAnOldOccurrenceClock()
            throws Exception {
        equalIndexOldSource(SELECTED_RECOVERY);
    }

    @Test
    public void equalIndicesAndSelectedCurrentItemCannotCreateRecoveryFromAnOldOccurrenceClock()
            throws Exception {
        equalIndexOldSource(PlayQueueItem.RECOVERY_UNSET);
    }

    @Test
    public void absentMediaItemCannotOverwriteRecoveryAndSynchronizationStillResumes()
            throws Exception {
        start();
        queue.setRecovery(0, SELECTED_RECOVERY);
        when(exoPlayer.getCurrentMediaItem()).thenReturn(null);

        player.setRecovery();

        assertEquals(SELECTED_RECOVERY, queue.getItem().getRecoveryPosition());
        queue.setIndex(0);
        synchronizeAndAssert(0, SELECTED_RECOVERY);
    }

    @Test
    public void metadataWithoutLoadedSourceOwnershipCannotInventRecovery()
            throws Exception {
        start();
        // Correct URL/metadata is insufficient without the LoadedMediaSource occurrence owner.
        final MediaItem unownedSource = StreamInfoTag.of(stream).asMediaItem();
        when(exoPlayer.getCurrentMediaItem()).thenReturn(unownedSource);

        player.setRecovery();

        assertEquals(PlayQueueItem.RECOVERY_UNSET, queue.getItem().getRecoveryPosition());
        queue.setIndex(0);
        synchronizeAndAssert(0, PlayQueueItem.RECOVERY_UNSET);
    }

    private void unownedSourcePreservesRecovery(final MediaItem mediaItem) throws Exception {
        queue.setRecovery(0, SELECTED_RECOVERY);
        when(exoPlayer.getCurrentMediaItem()).thenReturn(mediaItem);

        player.setRecovery();

        assertEquals(SELECTED_RECOVERY, queue.getItem().getRecoveryPosition());
        queue.setIndex(0);
        synchronizeAndAssert(0, SELECTED_RECOVERY);
    }

    private void pendingNavigation(final boolean quality, final long recovery) throws Exception {
        start();
        final PlayQueueItem playing = queue.getItem();
        final PlayQueueItem selected = new PlayQueueItem(stream);
        queue.append(List.of(selected));
        if (recovery != PlayQueueItem.RECOVERY_UNSET) {
            queue.setRecovery(1, recovery);
        }
        assertNotSame(playing, selected);
        assertEquals(playing.getUrl(), selected.getUrl());

        player.selectQueueItem(selected);
        // Selection is immediate; source readiness has not been delivered by observeOn.
        assertSame(selected, queue.getItem());
        assertEquals(1, queue.getIndex());
        assertEquals(0, exoPlayer.getCurrentMediaItemIndex());
        assertSame(playing, owner(exoPlayer.getCurrentMediaItem()));
        changeTrack(quality);

        assertEquals(recovery, selected.getRecoveryPosition());
        assertEquals(PLAYING_POSITION, playing.getRecoveryPosition());
        verify(preferences).getBoolean("watch_history", true);
        synchronizeAndAssert(1, recovery);
    }

    private void alignedCapture(final boolean quality) throws Exception {
        start();
        queue.setRecovery(0, SELECTED_RECOVERY);

        changeTrack(quality);

        assertEquals(PLAYING_POSITION, queue.getItem().getRecoveryPosition());
        verify(preferences).getBoolean("watch_history", true);
        // Reloading an aligned source also causes a blocked synchronization at the same index.
        queue.setIndex(0);
        synchronizeAndAssert(0, PLAYING_POSITION);
    }

    private void equalIndexOldSource(final long recovery) throws Exception {
        start();
        final PlayQueueItem oldOccurrence = queue.getItem();
        final MediaItem oldSource = exoPlayer.getCurrentMediaItem();
        final PlayQueueItem selected = new PlayQueueItem(stream);
        queue.append(List.of(selected));
        queue.remove(0);
        if (recovery != PlayQueueItem.RECOVERY_UNSET) {
            queue.setRecovery(0, recovery);
        }
        // Model a rebuilt queue/playlist: the numerical index and even currentItem match the
        // selected occurrence, but ExoPlayer still exposes the old occurrence's loaded source.
        setField("currentItem", selected);
        assertEquals(queue.getIndex(), exoPlayer.getCurrentMediaItemIndex());
        assertSame(selected, queue.getItem());
        assertSame(oldOccurrence, owner(oldSource));
        assertNotSame(selected, oldOccurrence);
        assertEquals(selected.getUrl(), oldOccurrence.getUrl());

        player.setRecovery();

        assertEquals(recovery, selected.getRecoveryPosition());
        assertEquals(PlayQueueItem.RECOVERY_UNSET, oldOccurrence.getRecoveryPosition());
        queue.setIndex(0);
        synchronizeAndAssert(0, recovery);
    }

    private void start() throws Exception {
        final String url = "https://example.com/watch/same-url";
        stream = new StreamInfo(0, url, url, StreamType.VIDEO_STREAM, "same", "same", 0);
        stream.setDuration(120);
        queue = new SinglePlayQueue(new PlayQueueItem(stream));
        queue.init();
        setField("playQueue", queue);
        setField("currentItem", queue.getItem());
        setField("currentMetadata", StreamInfoTag.of(stream));
        final MediaItem initialSource = loadedItem(queue.getItem());
        when(exoPlayer.getCurrentMediaItem()).thenReturn(initialSource);
        subscriptions.add(queue.getBroadcastReceiver()
                .filter(event -> event instanceof SelectEvent)
                .subscribe(event -> {
                    // The replaced manager has blocked/rebuilt its source before reporting ready.
                    // Reset its callback-deduplication marker, not the recovery under test.
                    setField("currentItem", null);
                    final MediaItem readySource = loadedItem(queue.getItem());
                    when(exoPlayer.getCurrentMediaItem()).thenReturn(readySource);
                    player.onPlaybackSynchronize(queue.getItem(), true);
                }));
    }

    private MediaItem loadedItem(final PlayQueueItem item) {
        // ExoPlayer's BasePlayer reads current media metadata from the published timeline window.
        return sourcePublication(item).publish().getWindow(0, new Timeline.Window()).mediaItem;
    }

    private SourcePublication sourcePublication(final PlayQueueItem item) {
        final StreamInfoTag tag = StreamInfoTag.of(stream);
        final MediaItem childItem = tag.asMediaItem().buildUpon()
                .setMediaId("resolved-child")
                .setUri("https://example.com/resolved/manifest.mpd")
                .setMimeType("application/dash+xml")
                .setCustomCacheKey("resolved-child-cache")
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(2_000L).setEndPositionMs(100_000L).build())
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(7_000L).build())
                .build();
        final Timeline childTimeline = new SinglePeriodTimeline(
                150_000_000L, 120_000_000L, 10_000_000L, 9_000_000L,
                true, true, true, new Object(), childItem);
        final MediaSource child = mock(MediaSource.class);
        when(child.getMediaItem()).thenReturn(childItem);
        when(child.getInitialTimeline()).thenReturn(childTimeline);
        return new SourcePublication(new PublishingLoadedMediaSource(child, tag, item),
                childTimeline);
    }

    private static void assertOwnedTimeline(final Timeline child, final Timeline published,
                                           final PlayQueueItem expectedOwner) {
        assertEquals(child.getWindowCount(), published.getWindowCount());
        assertEquals(child.getPeriodCount(), published.getPeriodCount());
        final Timeline.Window expected = child.getWindow(0, new Timeline.Window(), 2_000_000L);
        final Timeline.Window actual = published.getWindow(0, new Timeline.Window(), 2_000_000L);
        assertEquals(11_000L, actual.getDefaultPositionMs());
        assertEquals(120_000L, actual.getDurationMs());
        assertEquals(expected.positionInFirstPeriodUs, actual.positionInFirstPeriodUs);
        assertEquals(expected.firstPeriodIndex, actual.firstPeriodIndex);
        assertEquals(expected.lastPeriodIndex, actual.lastPeriodIndex);
        assertEquals(expected.uid, actual.uid);
        assertSame(expected.manifest, actual.manifest);
        assertTrue(actual.isSeekable);
        assertTrue(actual.isDynamic);
        assertTrue(actual.isLive());
        assertEquals(expected.liveConfiguration, actual.liveConfiguration);
        // Removing only the tag must make the media items equal: URI, live/clipping configuration,
        // MIME type, cache key and every other child playback setting must survive decoration.
        assertEquals(expected.mediaItem.buildUpon().setTag(null).build(),
                actual.mediaItem.buildUpon().setTag(null).build());
        assertEquals(child.getUidOfPeriod(0), published.getUidOfPeriod(0));
        assertEquals(0, published.getIndexOfPeriod(child.getUidOfPeriod(0)));
        final Timeline.Period expectedPeriod = child.getPeriod(0, new Timeline.Period(), true);
        final Timeline.Period actualPeriod = published.getPeriod(0, new Timeline.Period(), true);
        assertEquals(expectedPeriod.uid, actualPeriod.uid);
        assertEquals(expectedPeriod.durationUs, actualPeriod.durationUs);
        assertEquals(expectedPeriod.positionInWindowUs, actualPeriod.positionInWindowUs);
        assertSame(expectedOwner, owner(actual.mediaItem));
    }

    private static final class SourcePublication {
        private final PublishingLoadedMediaSource source;
        private final Timeline childTimeline;

        SourcePublication(final PublishingLoadedMediaSource source, final Timeline childTimeline) {
            this.source = source;
            this.childTimeline = childTimeline;
        }

        Timeline publish() {
            final AtomicReference<Timeline> published = new AtomicReference<>();
            final AtomicInteger callbacks = new AtomicInteger();
            final MediaSource.MediaSourceCaller caller = (publisher, timeline) -> {
                assertSame(source, publisher);
                callbacks.incrementAndGet();
                published.set(timeline);
            };
            // BaseMediaSource's caller registration and refreshSourceInfo dispatch remain real.
            // Only Android Looper/Handler plumbing and child decoder preparation are controlled.
            try (MockedStatic<Looper> loopers = mockStatic(Looper.class);
                    MockedConstruction<Handler> handlers = mockConstruction(Handler.class)) {
                final Looper looper = mock(Looper.class);
                loopers.when(Looper::myLooper).thenReturn(looper);
                source.prepareSource(caller, null, PlayerId.UNSET);
                try {
                    assertEquals(0, callbacks.get());
                    source.publishChildTimeline(childTimeline);
                    assertEquals(1, callbacks.get());
                    return published.get();
                } finally {
                    source.releaseSource(caller);
                }
            }
        }
    }

    private static final class PublishingLoadedMediaSource extends LoadedMediaSource {
        PublishingLoadedMediaSource(final MediaSource child, final MediaItemTag tag,
                                    final PlayQueueItem item) {
            super(child, tag, item, Long.MAX_VALUE);
        }

        @Override
        protected void prepareSourceInternal() {
            // No Android Handler/network/decoder pipeline; publication below is the real hook.
        }

        void publishChildTimeline(final Timeline timeline) {
            onChildSourceInfoRefreshed(timeline);
        }
    }

    private static PlayQueueItem owner(final MediaItem mediaItem) {
        return MediaItemTag.from(mediaItem)
                .flatMap(tag -> tag.getMaybeExtras(LoadedMediaSource.class))
                .orElseThrow(AssertionError::new).getStream();
    }

    private void changeTrack(final boolean quality) {
        if (quality) {
            player.setPlaybackQuality("720p");
        } else {
            player.setAudioTrack("alternate-audio");
        }
    }

    private void synchronizeAndAssert(final int index, final long recovery) {
        // No seek may happen until the actual onPlaybackSynchronize callback is delivered.
        verify(exoPlayer, never()).seekTo(anyInt(), anyLong());
        verify(exoPlayer, never()).seekToDefaultPosition(anyInt());
        mainThread.triggerActions();
        if (recovery == PlayQueueItem.RECOVERY_UNSET) {
            verify(exoPlayer).seekToDefaultPosition(index);
            verify(exoPlayer, never()).seekTo(anyInt(), anyLong());
        } else {
            verify(exoPlayer).seekTo(index, recovery);
            verify(exoPlayer, never()).seekToDefaultPosition(anyInt());
        }
        assertEquals(PlayQueueItem.RECOVERY_UNSET, queue.getItem().getRecoveryPosition());
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }
}
