package org.schabi.newpipe.player.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.MediaSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.mediasource.FailedMediaSource;
import org.schabi.newpipe.player.mediasource.LoadedMediaSource;
import org.schabi.newpipe.player.mediasource.ManagedMediaSource;
import org.schabi.newpipe.player.mediasource.ManagedMediaSourcePlaylist;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.SponsorBlock;
import org.schabi.newpipe.util.image.ImageStrategy;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** Real queue, manager, loaded sources and ExoPlayer playlist; no decoder or network. */
public class MediaSourceManagerTest {
    private final TestScheduler mainThread = new TestScheduler();
    private final PlaybackListener listener = mock(PlaybackListener.class);
    private Function<Callable<Scheduler>, Scheduler> previousMainInitializer;
    private MockedStatic<AndroidSchedulers> androidSchedulers;
    private MockedStatic<Schedulers> schedulers;
    private MockedStatic<Looper> loopers;
    private MockedStatic<Uri> uris;
    private MockedStatic<Log> logs;
    private MockedStatic<TextUtils> text;
    private MockedStatic<SponsorBlock> sponsorBlock;
    private MockedStatic<ImageStrategy> images;
    private MockedConstruction<Handler> handlers;
    private PlayQueue queue;
    private MediaSourceManager manager;

    @Before
    public void setUp() {
        previousMainInitializer = RxAndroidPlugins.getInitMainThreadSchedulerHandler();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> mainThread);
        androidSchedulers = mockStatic(AndroidSchedulers.class);
        androidSchedulers.when(AndroidSchedulers::mainThread).thenReturn(mainThread);
        final Scheduler immediate = Schedulers.trampoline();
        schedulers = mockStatic(Schedulers.class, CALLS_REAL_METHODS);
        schedulers.when(Schedulers::io).thenReturn(immediate);
        schedulers.when(Schedulers::single).thenReturn(mainThread);
        schedulers.when(Schedulers::computation).thenReturn(mainThread);
        loopers = mockStatic(Looper.class);
        loopers.when(Looper::getMainLooper).thenReturn(mock(Looper.class));
        uris = mockStatic(Uri.class);
        uris.when(() -> Uri.parse(anyString())).thenReturn(mock(Uri.class));
        logs = mockStatic(Log.class);
        text = mockStatic(TextUtils.class);
        text.when(() -> TextUtils.isEmpty(any())).thenAnswer(call -> {
            final CharSequence value = call.getArgument(0);
            return value == null || value.length() == 0;
        });
        sponsorBlock = mockStatic(SponsorBlock.class);
        images = mockStatic(ImageStrategy.class);
        // Only Android delivery is substituted: real ExoPlayer completion actions are queued,
        // not executed inline. Both queue observeOn hops also use this controlled scheduler.
        handlers = mockConstruction(Handler.class, (handler, context) ->
                when(handler.post(any(Runnable.class))).thenAnswer(call -> {
                    mainThread.scheduleDirect(call.getArgument(0));
                    return true;
                }));
        doAnswer(call -> {
            final StreamInfo info = call.getArgument(1);
            final MediaSource source = mock(MediaSource.class);
            final MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(info.getUrl()).setTag(StreamInfoTag.of(info)).build();
            when(source.getMediaItem()).thenReturn(mediaItem);
            return source;
        }).when(listener).sourceOf(any(), any());
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.dispose();
        }
        if (queue != null) {
            queue.dispose();
        }
        handlers.close();
        images.close();
        sponsorBlock.close();
        logs.close();
        text.close();
        uris.close();
        loopers.close();
        schedulers.close();
        androidSchedulers.close();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(previousMainInitializer);
    }

    @Test
    public void appendThenRemoveBeforeDeliveryKeepsSurvivingSourcesAndLoadsTail() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem second = item("second");
        final PlayQueueItem tail = item("tail");
        start(first, second);
        final ManagedMediaSource secondSource = playlist().get(1);

        queue.append(List.of(tail));
        queue.remove(0);
        assertEquals(2, playlist().size()); // No delivery yet.
        assertSame(secondSource, playlist().get(1));
        drain();

        assertSame(secondSource, playlist().get(0));
        assertTailAndOrder(second, tail);
    }

    @Test
    public void appendThenRemoveSameTailDoesNotChangeExistingSources() throws Exception {
        final PlayQueueItem first = item("first");
        start(first);
        final ManagedMediaSource original = playlist().get(0);
        queue.append(List.of(item("temporary")));
        queue.remove(1);
        drain();

        assertTailAndOrder(first);
        assertSame(original, playlist().get(0));
    }

    @Test
    public void queuedAppendsMovesAndRemovalsKeepDistinctSameUrlOccurrences() throws Exception {
        final PlayQueueItem first = item("same-url");
        final PlayQueueItem second = item("same-url");
        final PlayQueueItem third = item("same-url");
        final PlayQueueItem fourth = item("same-url");
        final PlayQueueItem fifth = item("same-url");
        start(first, second);
        final ManagedMediaSource secondSource = playlist().get(1);
        queue.append(List.of(third, fourth));
        queue.move(1, 3);
        queue.remove(0);
        queue.remove(1);
        queue.append(List.of(fifth));
        drain();

        // A misplaced source must not be hidden by selecting/reloading the final tail.
        assertSame(secondSource, playlist().get(1));
        assertTailAndOrder(third, second, fifth);
    }

    @Test
    public void mutationsQueuedBehindInitAreNotIncludedTwice() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem second = item("second");
        final PlayQueueItem tail = item("tail");
        createQueue(first, second);
        subscribeManager();
        queue.append(List.of(tail));
        queue.remove(0);
        assertEquals(0, playlist().size());
        verifyNoInteractions(listener);
        drain();

        assertTailAndOrder(second, tail);
    }

    @Test
    public void appendBehindInitIsNotAlsoIncludedInTheInitialSize() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem second = item("second");
        final PlayQueueItem tail = item("tail");
        createQueue(first, second);
        subscribeManager();
        queue.append(List.of(tail));
        drain();

        assertTailAndOrder(first, second, tail);
    }

    @Test
    public void subscriptionIncludesEarlierMutationsButNotLaterAppends() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem second = item("second");
        final PlayQueueItem tail = item("tail");
        createQueue(first);
        queue.append(List.of(second));
        subscribeManager();
        queue.append(List.of(tail));
        drain();

        assertTailAndOrder(first, second, tail);
    }

    @Test
    public void replacementSubscriptionSnapshotsOnlyEarlierMutations() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem second = item("second");
        final PlayQueueItem third = item("third");
        final PlayQueueItem fourth = item("fourth");
        start(first, second);
        queue.append(List.of(third));
        queue.remove(0);
        manager.dispose();
        subscribeManager();
        queue.append(List.of(fourth));
        queue.remove(0);
        drain();

        assertTailAndOrder(third, fourth);
    }

    @Test
    public void errorResetDoesNotIncludeAnAppendStillAwaitingDelivery() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem second = item("second");
        final PlayQueueItem tail = item("tail");
        start(first, second);
        queue.error();
        queue.append(List.of(tail));
        drain();

        assertTailAndOrder(first, second, tail);
    }

    @Test
    public void blockingResetKeepsTheProcessedBaselineWhileMoreEventsArePending() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem second = item("second");
        final PlayQueueItem tail = item("tail");
        createQueue(first, second);
        queue = spy(queue);
        subscribeManager();
        drain();
        doReturn(false).when(queue).isComplete();
        queue.setRecovery(0, 0);
        queue.append(List.of(tail));
        queue.setIndex(2);
        drain();
        assertEquals(3, playlist().size());

        doReturn(true).when(queue).isComplete();
        queue.notifyChange();
        drain();
        assertTailAndOrder(first, second, tail);
    }

    @Test
    public void pendingAutomaticTailIsRemovedBeforeManualReplacement() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem automatic = item("same-url");
        automatic.setAutoQueued(true);
        final PlayQueueItem manual = item("same-url");
        final PlayQueueItem tail = item("tail");
        start(first);
        queue.append(List.of(automatic));
        queue.append(List.of(manual, tail));
        drain();

        assertTailAndOrder(first, manual, tail);
    }

    @Test
    public void replacingLoadedSelectedAutomaticTailKeepsManualOrder() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem automatic = item("same-url");
        automatic.setAutoQueued(true);
        final PlayQueueItem manual = item("same-url");
        final PlayQueueItem tail = item("tail");
        start(first, automatic);
        queue.setIndex(1);
        drain();
        queue.append(List.of(manual, tail));
        drain();

        assertTailAndOrder(first, manual, tail);
    }

    @Test
    public void invalidRemovalIndicesLeavePlaylistOccurrencesIntact() throws Exception {
        final PlayQueueItem first = item("first");
        final PlayQueueItem second = item("second");
        start(first, second);
        final ManagedMediaSourcePlaylist sources = playlist();
        sources.remove(-1);
        sources.remove(sources.size());
        sources.remove(sources.size() + 1);
        assertTailAndOrder(first, second);
        sources.remove(0);
        assertEquals(1, sources.size());
        assertTrue(sources.get(0).isStreamEqual(second));
        sources.remove(0);
        sources.remove(0);
        assertEquals(0, sources.size());
    }

    private void createQueue(final PlayQueueItem first, final PlayQueueItem... rest) {
        queue = new SinglePlayQueue(first);
        queue.append(List.of(rest));
        queue.init();
    }

    private void subscribeManager() {
        manager = new MediaSourceManager(mock(Context.class), listener, queue);
    }

    private void start(final PlayQueueItem first, final PlayQueueItem... rest) {
        createQueue(first, rest);
        subscribeManager();
        drain();
    }

    private void drain() {
        mainThread.triggerActions();
        mainThread.advanceTimeBy(1, TimeUnit.SECONDS);
    }

    private void assertTailAndOrder(final PlayQueueItem... expected) throws Exception {
        assertEquals(List.of(expected), queue.getStreams());
        assertEquals(expected.length, playlist().size());
        final PlayQueueItem tail = expected[expected.length - 1];
        queue.setIndex(expected.length - 1);
        drain();
        verify(listener, atLeastOnce()).onPlaybackSynchronize(same(tail), anyBoolean());
        for (int index = 0; index < expected.length; index++) {
            if (playlist().get(index) instanceof FailedMediaSource failed) {
                throw new AssertionError("Fixture resolution failed", failed.getError());
            }
            assertTrue("Loaded source at " + index, playlist().get(index)
                    instanceof LoadedMediaSource);
            assertSame(expected[index], ((LoadedMediaSource) playlist().get(index)).getStream());
        }
    }

    private ManagedMediaSourcePlaylist playlist() throws Exception {
        final Field field = MediaSourceManager.class.getDeclaredField("playlist");
        field.setAccessible(true);
        return (ManagedMediaSourcePlaylist) field.get(manager);
    }

    private static PlayQueueItem item(final String url) {
        final StreamInfo info = mock(StreamInfo.class);
        when(info.getUrl()).thenReturn(url);
        when(info.getName()).thenReturn(url);
        when(info.getStreamType()).thenReturn(StreamType.VIDEO_STREAM);
        final PlayQueueItem item = spy(new PlayQueueItem(info));
        doReturn(Single.just(info)).when(item).getStream();
        return item;
    }
}
