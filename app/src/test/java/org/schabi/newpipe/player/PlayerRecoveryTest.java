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
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.cast.CastPlayback;
import org.schabi.newpipe.player.event.PlayerListeners;
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
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.schabi.newpipe.util.image.ImageStrategy;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
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
    private static final long CONTENT_POSITION = 31_000L;
    private static final long SELECTED_RECOVERY = 12_000L;

    private final TestScheduler mainThread = new TestScheduler();
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private Player player;
    private ExoPlayer exoPlayer;
    private PlayQueue queue;
    private CastPlayback casting;
    private StreamInfo stream;
    private SharedPreferences preferences;
    private boolean historyEnabled;
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
        records = mockConstruction(HistoryRecordManager.class, (recordManager, ignored) -> {
            when(recordManager.saveStreamState(any(StreamInfo.class), anyLong()))
                    .thenReturn(Completable.complete());
            when(recordManager.onViewed(any(StreamInfo.class))).thenReturn(Maybe.empty());
        });
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
            assertEquals(1, records.constructed().size());
            if (!historyEnabled) {
                // Recovery must not be manufactured through persistent history when disabled.
                verifyNoInteractions(records.constructed().get(0));
            }
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
    public void equalIndexQualityKeepsExistingRecoveryWithHistoryEnabled() throws Exception {
        equalIndexTrackChange(true, SELECTED_RECOVERY, true);
    }

    @Test
    public void equalIndexQualityKeepsUnsetRecoveryWithHistoryEnabled() throws Exception {
        equalIndexTrackChange(true, PlayQueueItem.RECOVERY_UNSET, true);
    }

    @Test
    public void equalIndexAudioKeepsExistingRecoveryWithHistoryEnabled() throws Exception {
        equalIndexTrackChange(false, SELECTED_RECOVERY, true);
    }

    @Test
    public void equalIndexAudioKeepsUnsetRecoveryWithHistoryEnabled() throws Exception {
        equalIndexTrackChange(false, PlayQueueItem.RECOVERY_UNSET, true);
    }

    @Test
    public void equalIndexQualityKeepsExistingRecoveryWithHistoryDisabled() throws Exception {
        equalIndexTrackChange(true, SELECTED_RECOVERY, false);
    }

    @Test
    public void equalIndexQualityKeepsUnsetRecoveryWithHistoryDisabled() throws Exception {
        equalIndexTrackChange(true, PlayQueueItem.RECOVERY_UNSET, false);
    }

    @Test
    public void equalIndexAudioKeepsExistingRecoveryWithHistoryDisabled() throws Exception {
        equalIndexTrackChange(false, SELECTED_RECOVERY, false);
    }

    @Test
    public void equalIndexAudioKeepsUnsetRecoveryWithHistoryDisabled() throws Exception {
        equalIndexTrackChange(false, PlayQueueItem.RECOVERY_UNSET, false);
    }

    @Test
    public void alignedProgressSavesMetadataPlaybackPositionAndConsumesContentRecovery()
            throws Exception {
        start();
        enableHistory();
        when(exoPlayer.getContentPosition()).thenReturn(CONTENT_POSITION);

        player.saveStreamProgressState();

        assertEquals(CONTENT_POSITION, queue.getItem().getRecoveryPosition());
        assertSavedProgress(PLAYING_POSITION);
        queue.setIndex(0);
        synchronizeAndAssert(0, CONTENT_POSITION);
    }

    @Test
    public void alignedQualitySavesPlaybackPositionBeforeReloadAndConsumesRecovery()
            throws Exception {
        alignedTrackHistory(true);
    }

    @Test
    public void alignedAudioSavesPlaybackPositionBeforeReloadAndConsumesRecovery()
            throws Exception {
        alignedTrackHistory(false);
    }

    @Test
    public void alignedProgressWithHistoryDisabledStillConsumesContentRecovery() throws Exception {
        start();
        when(exoPlayer.getContentPosition()).thenReturn(CONTENT_POSITION);

        player.saveStreamProgressState();

        assertEquals(CONTENT_POSITION, queue.getItem().getRecoveryPosition());
        verifyNoInteractions(records.constructed().get(0));
        queue.setIndex(0);
        synchronizeAndAssert(0, CONTENT_POSITION);
    }

    @Test
    public void pendingNavigationQualityPersistsOnlyThePreviouslyAlignedMetadata()
            throws Exception {
        start();
        enableHistory();
        final PlayQueueItem playing = queue.getItem();
        final PlayQueueItem selected = new PlayQueueItem(stream);
        queue.append(List.of(selected));
        queue.setRecovery(1, SELECTED_RECOVERY);

        // Selection accepts a coherent old-stream write before advancing the queue.
        player.selectQueueItem(selected);
        player.setPlaybackQuality("720p");

        assertEquals(PLAYING_POSITION, playing.getRecoveryPosition());
        assertEquals(SELECTED_RECOVERY, selected.getRecoveryPosition());
        assertSavedProgress(PLAYING_POSITION);
        synchronizeAndAssert(1, SELECTED_RECOVERY);
    }

    @Test
    public void alignedCastProgressSavesReceiverPositionNotLocalEnginePosition()
            throws Exception {
        start();
        enableHistory();
        when(casting.isActive()).thenReturn(true);
        when(casting.getPosition()).thenReturn(73_000L);
        when(casting.getDuration()).thenReturn(120_000L);

        player.saveStreamProgressState();

        assertEquals(73_000L, queue.getItem().getRecoveryPosition());
        assertSavedProgress(73_000L);
        queue.setIndex(0);
        synchronizeAndAssert(0, 73_000L);
    }

    @Test
    public void equalIndexCastQualityCannotSaveReceiverClockForAnotherOccurrence()
            throws Exception {
        start();
        enableHistory();
        final PlayQueueItem oldOccurrence = queue.getItem();
        final PlayQueueItem selected = removePlayingOccurrence(SELECTED_RECOVERY);
        when(casting.isActive()).thenReturn(true);
        when(casting.getPosition()).thenReturn(73_000L);
        when(casting.getDuration()).thenReturn(120_000L);

        player.setPlaybackQuality("720p");

        assertEquals(SELECTED_RECOVERY, selected.getRecoveryPosition());
        assertEquals(PlayQueueItem.RECOVERY_UNSET, oldOccurrence.getRecoveryPosition());
        verifyNoInteractions(records.constructed().get(0));
        queue.setIndex(0);
        synchronizeAndAssert(0, SELECTED_RECOVERY);
    }

    @Test
    public void selectedPreparedSourceWaitsForItsMetadataBeforeSavingItsClock()
            throws Exception {
        start();
        enableHistory();
        setField("listeners", mock(PlayerListeners.class));
        setField("UIs", mock(PlayerUiList.class));
        final StreamInfo previousInfo = stream;
        // The engine has switched to B, but cached metadata still names A. Accepting this
        // snapshot would genuinely misattribute B's position to A's persistent stream state.
        stream = distinctStream();
        final PlayQueueItem selected = new PlayQueueItem(stream);
        queue.append(List.of(selected));
        queue.remove(0);
        // Keep a following item so metadata delivery does not enter auto-queue/network policy.
        queue.append(List.of(new PlayQueueItem(previousInfo)));
        queue.setRecovery(0, SELECTED_RECOVERY);
        setField("currentItem", selected);
        final MediaItem selectedSource = loadedItem(selected);
        when(exoPlayer.getCurrentMediaItem()).thenReturn(selectedSource);
        when(exoPlayer.getContentPosition()).thenReturn(CONTENT_POSITION);
        assertSame(selected, owner(selectedSource));
        assertSame(previousInfo, player.getCurrentStreamInfo().orElse(null));

        player.saveStreamProgressState();

        assertEquals(SELECTED_RECOVERY, selected.getRecoveryPosition());
        verifyNoInteractions(records.constructed().get(0));

        // Deliver the real aggregate callback, rather than manufacturing an accepted Snapshot.
        player.onEvents(exoPlayer, mock(com.google.android.exoplayer2.Player.Events.class));
        assertSame(stream, player.getCurrentStreamInfo().orElse(null));
        verify(casting).onSourceChanged();
        // New metadata legitimately counts B as viewed; progress must also name B, never A.
        verify(records.constructed().get(0)).onViewed(stream);
        player.saveStreamProgressState();

        assertEquals(CONTENT_POSITION, selected.getRecoveryPosition());
        assertSavedProgress(PLAYING_POSITION);
        queue.setIndex(0);
        synchronizeAndAssert(0, CONTENT_POSITION);
    }

    @Test
    public void equalIndexQualityDoesNotWriteOldMetadataOrCorruptDistinctSelectedRecovery()
            throws Exception {
        equalIndexDistinctUrlTrackChange(true);
    }

    @Test
    public void equalIndexAudioDoesNotWriteOldMetadataOrCorruptDistinctSelectedRecovery()
            throws Exception {
        equalIndexDistinctUrlTrackChange(false);
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

    private void equalIndexTrackChange(final boolean quality, final long recovery,
                                      final boolean saveHistory) throws Exception {
        start();
        if (saveHistory) {
            enableHistory();
        }
        final PlayQueueItem oldOccurrence = queue.getItem();
        final PlayQueueItem selected = removePlayingOccurrence(recovery);

        // Exercise both calls in their real order: history progress first, direct recovery next.
        // A direct setRecovery() test alone misses the earlier history recovery overwrite.
        changeTrack(quality);

        assertSame(selected, queue.getItem());
        assertEquals(recovery, selected.getRecoveryPosition());
        assertEquals(PlayQueueItem.RECOVERY_UNSET, oldOccurrence.getRecoveryPosition());
        // Reject the incoherent selected-progress snapshot as a whole. A baseline write would
        // still name old metadata with its own engine clock, not the selected occurrence.
        verifyNoInteractions(records.constructed().get(0));
        queue.setIndex(0);
        synchronizeAndAssert(0, recovery);
    }

    private void equalIndexDistinctUrlTrackChange(final boolean quality) throws Exception {
        start();
        enableHistory();
        final StreamInfo metadataInfo = player.getCurrentStreamInfo()
                .orElseThrow(AssertionError::new);
        final PlayQueueItem oldOccurrence = queue.getItem();
        final PlayQueueItem selected = removePlayingOccurrence(SELECTED_RECOVERY, distinctStream());

        changeTrack(quality);

        assertSame(metadataInfo,
                player.getCurrentStreamInfo().orElseThrow(AssertionError::new));
        assertEquals(stream.getUrl(), metadataInfo.getUrl());
        assertTrue(!metadataInfo.getUrl().equals(selected.getUrl()));
        assertEquals(SELECTED_RECOVERY, selected.getRecoveryPosition());
        assertEquals(PlayQueueItem.RECOVERY_UNSET, oldOccurrence.getRecoveryPosition());
        // The old metadata names A, not selected B. Reject the incoherent capture entirely:
        // no A progress write, and no recovery derived from A's clock may be placed on B.
        verifyNoInteractions(records.constructed().get(0));
        queue.setIndex(0);
        synchronizeAndAssert(0, SELECTED_RECOVERY);
    }

    private static StreamInfo distinctStream() {
        final String url = "https://example.com/watch/distinct-selected";
        final StreamInfo info = new StreamInfo(0, url, url,
                StreamType.VIDEO_STREAM, "distinct selected", "selected", 0);
        info.setDuration(180);
        return info;
    }

    private PlayQueueItem removePlayingOccurrence(final long recovery) throws Exception {
        final PlayQueueItem oldOccurrence = queue.getItem();
        final PlayQueueItem selected = removePlayingOccurrence(recovery, stream);
        assertEquals(selected.getUrl(), oldOccurrence.getUrl());
        return selected;
    }

    private PlayQueueItem removePlayingOccurrence(final long recovery,
                                                 final StreamInfo selectedInfo) throws Exception {
        final PlayQueueItem oldOccurrence = queue.getItem();
        final MediaItem oldSource = exoPlayer.getCurrentMediaItem();
        final PlayQueueItem selected = new PlayQueueItem(selectedInfo);
        queue.append(List.of(selected));
        queue.remove(0);
        if (recovery != PlayQueueItem.RECOVERY_UNSET) {
            queue.setRecovery(0, recovery);
        }
        // A rebuilt selection may already match numerically and in Player.currentItem while
        // the prepared window still identifies the removed occurrence that owns the clock.
        setField("currentItem", selected);
        assertEquals(queue.getIndex(), exoPlayer.getCurrentMediaItemIndex());
        assertSame(selected, queue.getItem());
        assertSame(oldOccurrence, owner(oldSource));
        assertNotSame(selected, oldOccurrence);
        return selected;
    }

    private void alignedTrackHistory(final boolean quality) throws Exception {
        start();
        enableHistory();
        when(exoPlayer.getContentPosition()).thenReturn(CONTENT_POSITION);

        changeTrack(quality);

        // Progress uses content time, then setRecovery intentionally replaces it with the
        // playback clock used for quality/audio source reloads. Persistence uses playback time.
        assertEquals(PLAYING_POSITION, queue.getItem().getRecoveryPosition());
        assertSavedProgress(PLAYING_POSITION);
        queue.setIndex(0);
        synchronizeAndAssert(0, PLAYING_POSITION);
    }

    private void enableHistory() {
        historyEnabled = true;
        when(preferences.getBoolean("watch_history", true)).thenReturn(true);
    }

    private void assertSavedProgress(final long expectedPosition) {
        final ArgumentCaptor<StreamInfo> info = ArgumentCaptor.forClass(StreamInfo.class);
        final ArgumentCaptor<Long> position = ArgumentCaptor.forClass(Long.class);
        final HistoryRecordManager manager = records.constructed().get(0);
        // Capture arguments crossing the real AndroidPlaybackHistoryEnvironment, not a fake
        // environment echoing Snapshot fields. Room/SQLite is the deliberately mocked boundary.
        verify(manager).saveStreamState(info.capture(), position.capture());
        assertSame(player.getCurrentStreamInfo().orElseThrow(AssertionError::new), info.getValue());
        assertSame(stream, info.getValue());
        assertEquals(stream.getServiceId(), info.getValue().getServiceId());
        assertEquals(stream.getUrl(), info.getValue().getUrl());
        assertEquals(expectedPosition, position.getValue().longValue());
        verifyNoMoreInteractions(manager);
    }

    private void start() throws Exception {
        final String url = "https://example.com/watch/same-url";
        stream = new StreamInfo(0, url, url, StreamType.VIDEO_STREAM, "same", "same", 0);
        stream.setDuration(120);
        queue = new SinglePlayQueue(new PlayQueueItem(stream));
        queue.init();
        setField("playQueue", queue);
        setField("currentItem", queue.getItem());
        // Cached metadata must be the actual decorated prepared-window tag, as after onEvents.
        final MediaItem initialSource = loadedItem(queue.getItem());
        setField("currentMetadata",
                MediaItemTag.from(initialSource).orElseThrow(AssertionError::new));
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
