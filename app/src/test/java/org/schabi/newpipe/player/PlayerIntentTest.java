package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.IntentCompat;

import com.google.android.exoplayer2.ExoPlayer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.player.ui.MainPlayerUi;
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.SerializedCache;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.MaybeSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;

public class PlayerIntentTest {
    private Player player;
    private Intent intent;
    private ExoPlayer exoPlayer;
    private SerializedCache cache;
    private PlayerUiList uis;
    private VideoPlaybackResolver videoResolver;
    private HistoryRecordManager history;
    private CompositeDisposable streamSubscriptions;
    private CompositeDisposable historySubscriptions;
    private MockedStatic<IntentCompat> intentCompat;
    private MockedStatic<SerializedCache> serializedCache;
    private MockedStatic<ExtractorHelper> extractorHelper;
    private MockedStatic<DependentPreferenceHelper> preferences;
    private MockedStatic<AndroidSchedulers> androidSchedulers;
    private MockedStatic<Schedulers> schedulers;
    private Function<Callable<Scheduler>, Scheduler> previousMainSchedulerInitializer;

    @Before
    public void setUp() throws Exception {
        player = mock(Player.class, CALLS_REAL_METHODS);
        intent = mock(Intent.class);
        exoPlayer = mock(ExoPlayer.class);
        cache = mock(SerializedCache.class);
        uis = spy(new PlayerUiList(mock(MainPlayerUi.class)));
        videoResolver = mock(VideoPlaybackResolver.class);
        history = mock(HistoryRecordManager.class);
        streamSubscriptions = new CompositeDisposable();
        historySubscriptions = new CompositeDisposable();
        setField("playerType", PlayerType.MAIN);
        setField("UIs", uis);
        setField("simpleExoPlayer", exoPlayer);
        setField("videoResolver", videoResolver);
        setField("recordManager", history);
        setField("context", mock(Context.class));
        setField("streamItemDisposable", streamSubscriptions);
        setField("databaseUpdateDisposable", historySubscriptions);
        intentCompat = mockStatic(IntentCompat.class);
        serializedCache = mockStatic(SerializedCache.class);
        extractorHelper = mockStatic(ExtractorHelper.class);
        preferences = mockStatic(DependentPreferenceHelper.class);
        // Avoid initializing Android's Looper while keeping scheduler changes scoped to each test.
        final Scheduler immediate = Schedulers.trampoline();
        previousMainSchedulerInitializer = RxAndroidPlugins.getInitMainThreadSchedulerHandler();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> immediate);
        androidSchedulers = mockStatic(AndroidSchedulers.class);
        androidSchedulers.when(AndroidSchedulers::mainThread).thenReturn(immediate);
        schedulers = mockStatic(Schedulers.class, CALLS_REAL_METHODS);
        schedulers.when(Schedulers::io).thenReturn(immediate);
        serializedCache.when(SerializedCache::getInstance).thenReturn(cache);
        intentCompat.when(() -> IntentCompat.getSerializableExtra(
                intent, Player.PLAYER_TYPE, PlayerType.class)).thenReturn(PlayerType.MAIN);
        when(intent.getBooleanExtra(Player.PLAY_WHEN_READY, true)).thenReturn(true);
        when(intent.getStringExtra(Player.PLAY_QUEUE_KEY)).thenReturn("queue");
        when(exoPlayer.getPlaybackState()).thenReturn(
                com.google.android.exoplayer2.Player.STATE_READY);
    }

    @After
    public void tearDown() {
        streamSubscriptions.dispose();
        historySubscriptions.dispose();
        schedulers.close();
        androidSchedulers.close();
        preferences.close();
        extractorHelper.close();
        serializedCache.close();
        intentCompat.close();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(previousMainSchedulerInitializer);
    }

    @Test
    public void missingIntentTypeDoesNotSetUpOrChangePlayback() {
        player.handleIntent(intent);
        verifyNoInteractions(uis, exoPlayer, cache, videoResolver, history);
        assertSame(PlayerType.MAIN, player.getPlayerType());
    }

    @Test
    public void enqueueSetsUpUiAndQualityBeforeConsumingTheQueue() throws Exception {
        type(PlayerIntentType.Enqueue);
        final PlayQueue current = queue("current", "last");
        final PlayQueue incoming = queue("incoming");
        setField("playQueue", current);
        when(cache.take("queue", PlayQueue.class)).thenReturn(incoming);
        when(intent.hasExtra(Player.PLAYBACK_QUALITY)).thenReturn(true);
        when(intent.getStringExtra(Player.PLAYBACK_QUALITY)).thenReturn("720p");

        player.handleIntent(intent);

        assertEquals(List.of("current", "last", "incoming"), urls(current));
        assertSame(current, player.getPlayQueue());
        assertEquals(0, current.getIndex());
        final InOrder order = inOrder(uis, videoResolver, cache);
        order.verify(uis).get(MainPlayerUi.class);
        order.verify(videoResolver).setPlaybackQuality("720p");
        order.verify(cache).take("queue", PlayQueue.class);
        verifyNoInteractions(exoPlayer, history);
    }

    @Test
    public void enqueueNextInsertsOnlyTheFirstIncomingItem() throws Exception {
        type(PlayerIntentType.EnqueueNext);
        final PlayQueue current = queue("current", "last");
        final PlayQueue incoming = queue("incoming", "ignored");
        setField("playQueue", current);
        when(cache.take("queue", PlayQueue.class)).thenReturn(incoming);

        player.handleIntent(intent);

        assertEquals(List.of("current", "incoming", "last"), urls(current));
        assertSame(current, player.getPlayQueue());
        assertEquals(0, current.getIndex());
        verifyNoInteractions(exoPlayer, history);
    }

    @Test
    public void missingCachedQueueLeavesTheActiveQueueAlone() throws Exception {
        type(PlayerIntentType.Enqueue);
        final PlayQueue current = queue("current");
        setField("playQueue", current);

        player.handleIntent(intent);

        assertSame(current, player.getPlayQueue());
        assertEquals(List.of("current"), urls(current));
        verifyNoInteractions(exoPlayer, history);
    }

    @Test
    public void recoveryPositionTakesPrecedenceOverQueueReuseAndHistory() throws Exception {
        type(PlayerIntentType.AllOthers);
        final PlayQueue current = queue("current");
        final PlayQueue incoming = queue("current");
        incoming.setRecovery(0, 45_000);
        setField("playQueue", current);
        when(cache.take("queue", PlayQueue.class)).thenReturn(incoming);
        when(intent.getBooleanExtra(Player.RESUME_PLAYBACK, false)).thenReturn(true);

        player.handleIntent(intent);

        assertSame(current, player.getPlayQueue());
        verify(exoPlayer).seekTo(0, 45_000);
        verify(exoPlayer).setPlayWhenReady(true);
        verifyNoInteractions(history);
    }

    @Test
    public void sameActiveQueueIsReusedWithoutReloadingHistory() throws Exception {
        type(PlayerIntentType.AllOthers);
        final PlayQueue current = queue("current");
        final PlayQueue incoming = queue("current");
        setField("playQueue", current);
        when(cache.take("queue", PlayQueue.class)).thenReturn(incoming);
        when(intent.getBooleanExtra(Player.RESUME_PLAYBACK, false)).thenReturn(true);

        player.handleIntent(intent);

        assertSame(current, player.getPlayQueue());
        verify(exoPlayer).setPlayWhenReady(true);
        verifyNoInteractions(history);
    }

    @Test
    public void timestampUsesTheQueueAtDeliveryAndKeepsThePlayerType() throws Exception {
        type(PlayerIntentType.TimestampChange);
        final SingleSubject<StreamInfo> pending = timestamp("target", 30);
        setField("playQueue", queue("original"));
        intentCompat.when(() -> IntentCompat.getSerializableExtra(
                intent, Player.PLAYER_TYPE, PlayerType.class)).thenReturn(PlayerType.AUDIO);
        when(intent.getBooleanExtra(Player.PLAY_WHEN_READY, true)).thenReturn(false);
        player.handleIntent(intent);
        final PlayQueue replacement = queue("target");
        setField("playQueue", replacement);

        pending.onSuccess(info("target"));

        assertSame(replacement, player.getPlayQueue());
        assertSame(PlayerType.MAIN, player.getPlayerType());
        verify(exoPlayer).seekTo(0, 30_000);
        verify(exoPlayer).setPlayWhenReady(false);
        verifyNoInteractions(cache, history);
    }

    @Test
    public void timestampPreparesAnIdlePlayerBeforeSeeking() throws Exception {
        type(PlayerIntentType.TimestampChange);
        final SingleSubject<StreamInfo> pending = timestamp("target", 30);
        setField("playQueue", queue("target"));
        when(exoPlayer.getPlaybackState()).thenReturn(
                com.google.android.exoplayer2.Player.STATE_IDLE);
        player.handleIntent(intent);

        pending.onSuccess(info("target"));

        final InOrder order = inOrder(exoPlayer);
        order.verify(exoPlayer).prepare();
        order.verify(exoPlayer).seekTo(0, 30_000);
        order.verify(exoPlayer).setPlayWhenReady(true);
    }

    @Test
    public void disposedTimestampSubscriptionCannotSeek() throws Exception {
        type(PlayerIntentType.TimestampChange);
        final SingleSubject<StreamInfo> pending = timestamp("target", 30);
        setField("playQueue", queue("target"));
        player.handleIntent(intent);
        assertTrue(pending.hasObservers());

        streamSubscriptions.dispose();
        pending.onSuccess(info("target"));

        assertFalse(pending.hasObservers());
        verifyNoInteractions(exoPlayer);
    }

    @Test
    public void disposedHistorySubscriptionCannotResumePlayback() throws Exception {
        type(PlayerIntentType.AllOthers);
        setField("simpleExoPlayer", null);
        final PlayQueue incoming = queue("incoming");
        final MaybeSubject<StreamStateEntity> pending = MaybeSubject.create();
        when(cache.take("queue", PlayQueue.class)).thenReturn(incoming);
        when(intent.getBooleanExtra(Player.RESUME_PLAYBACK, false)).thenReturn(true);
        preferences.when(() -> DependentPreferenceHelper.getResumePlaybackEnabled(
                player.getContext())).thenReturn(true);
        when(history.loadStreamState(incoming.getItem())).thenReturn(pending);
        player.handleIntent(intent);
        assertTrue(pending.hasObservers());

        historySubscriptions.dispose();
        pending.onSuccess(new StreamStateEntity(1, 45_000));

        assertFalse(pending.hasObservers());
        assertEquals(PlayQueueItem.RECOVERY_UNSET, incoming.getItem().getRecoveryPosition());
        assertNull(player.getPlayQueue());
    }

    private void type(final PlayerIntentType type) {
        intentCompat.when(() -> IntentCompat.getSerializableExtra(
                intent, Player.PLAYER_INTENT_TYPE, PlayerIntentType.class)).thenReturn(type);
    }

    private SingleSubject<StreamInfo> timestamp(final String url, final int seconds) {
        final TimestampChangeData data = new TimestampChangeData(0, url, seconds);
        intentCompat.when(() -> IntentCompat.getParcelableExtra(
                intent, Player.PLAYER_INTENT_DATA, TimestampChangeData.class)).thenReturn(data);
        final SingleSubject<StreamInfo> pending = SingleSubject.create();
        extractorHelper.when(() -> ExtractorHelper.getStreamInfo(0, url, false))
                .thenReturn(pending);
        return pending;
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    private static PlayQueue queue(final String... urls) {
        final PlayQueue queue = new SinglePlayQueue(new PlayQueueItem(info(urls[0])));
        for (int i = 1; i < urls.length; i++) {
            queue.append(List.of(new PlayQueueItem(info(urls[i]))));
        }
        return queue;
    }

    private static List<String> urls(final PlayQueue queue) {
        return queue.getStreams().stream().map(PlayQueueItem::getUrl).toList();
    }

    private static StreamInfo info(final String url) {
        final StreamInfo info = mock(StreamInfo.class);
        when(info.getUrl()).thenReturn(url);
        when(info.getDuration()).thenReturn(120L);
        when(info.getStreamType()).thenReturn(StreamType.VIDEO_STREAM);
        return info;
    }
}
