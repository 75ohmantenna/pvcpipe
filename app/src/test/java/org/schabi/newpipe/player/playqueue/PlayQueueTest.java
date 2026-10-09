package org.schabi.newpipe.player.playqueue;

import android.content.Context;
import android.database.Observable;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueueEvent.AppendEvent;
import org.schabi.newpipe.player.playqueue.PlayQueueEvent.RemoveEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.schedulers.TestScheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@RunWith(Enclosed.class)
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
public class PlayQueueTest {
    static PlayQueue makePlayQueue(final int index, final List<PlayQueueItem> streams) {
        // I tried using Mockito, but it didn't work for some reason
        return new PlayQueue(index, streams) {
            @Override
            public boolean isComplete() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void fetch() {
                throw new UnsupportedOperationException();
            }
        };
    }

    static PlayQueueItem makeItemWithUrl(final String url) {
        final StreamInfoItem infoItem = new StreamInfoItem(
                0, url, "", StreamType.VIDEO_STREAM
        );
        return new PlayQueueItem(infoItem);
    }

    public static class SetIndexTests {
        private static final int SIZE = 5;
        private PlayQueue nonEmptyQueue;
        private PlayQueue emptyQueue;

        @Before
        public void setup() {
            final List<PlayQueueItem> streams = new ArrayList<>(5);
            for (int i = 0; i < 5; ++i) {
                streams.add(makeItemWithUrl("URL_" + i));
            }
            nonEmptyQueue = spy(makePlayQueue(0, streams));
            emptyQueue = spy(makePlayQueue(0, new ArrayList<>()));
        }

        @Test
        public void negative() {
            nonEmptyQueue.setIndex(-5);
            assertEquals(0, nonEmptyQueue.getIndex());

            emptyQueue.setIndex(-5);
            assertEquals(0, emptyQueue.getIndex());
        }

        @Test
        public void inBounds() {
            nonEmptyQueue.setIndex(2);
            assertEquals(2, nonEmptyQueue.getIndex());

            // emptyQueue not tested because 0 isn't technically inBounds
        }

        @Test
        public void outOfBoundIsComplete() {
            doReturn(true).when(nonEmptyQueue).isComplete();
            nonEmptyQueue.setIndex(7);
            assertEquals(2, nonEmptyQueue.getIndex());

            doReturn(true).when(emptyQueue).isComplete();
            emptyQueue.setIndex(2);
            assertEquals(0, emptyQueue.getIndex());
        }

        @Test
        public void outOfBoundsNotComplete() {
            doReturn(false).when(nonEmptyQueue).isComplete();
            nonEmptyQueue.setIndex(7);
            assertEquals(SIZE - 1, nonEmptyQueue.getIndex());

            doReturn(false).when(emptyQueue).isComplete();
            emptyQueue.setIndex(2);
            assertEquals(0, emptyQueue.getIndex());
        }

        @Test
        public void indexZero() {
            nonEmptyQueue.setIndex(0);
            assertEquals(0, nonEmptyQueue.getIndex());

            doReturn(true).when(emptyQueue).isComplete();
            emptyQueue.setIndex(0);
            assertEquals(0, emptyQueue.getIndex());

            doReturn(false).when(emptyQueue).isComplete();
            emptyQueue.setIndex(0);
            assertEquals(0, emptyQueue.getIndex());
        }

        @Test
        public void addToHistory() {
            nonEmptyQueue.setIndex(0);
            assertFalse(nonEmptyQueue.previous());

            nonEmptyQueue.setIndex(3);
            assertTrue(nonEmptyQueue.previous());
            assertEquals("URL_0", Objects.requireNonNull(nonEmptyQueue.getItem()).getUrl());
        }
    }

    public static class GetItemTests {
        private static List<PlayQueueItem> streams;
        private PlayQueue queue;

        @BeforeClass
        public static void init() {
            streams = new ArrayList<>(Collections.nCopies(5, makeItemWithUrl("OTHER_URL")));
            streams.set(3, makeItemWithUrl("TARGET_URL"));
        }

        @Before
        public void setup() {
            queue = makePlayQueue(0, streams);
        }

        @Test
        public void inBounds() {
            assertEquals("TARGET_URL", Objects.requireNonNull(queue.getItem(3)).getUrl());
            assertEquals("OTHER_URL", Objects.requireNonNull(queue.getItem(1)).getUrl());
        }

        @Test
        public void outOfBounds() {
            assertNull(queue.getItem(-1));
            assertNull(queue.getItem(5));
        }

        @Test
        public void itemsAreNotCloned() {
            final PlayQueueItem item = makeItemWithUrl("A url");
            final PlayQueue playQueue = makePlayQueue(0, List.of(item));

            // make sure that items are not cloned when added to the queue
            assertSame(playQueue.getItem(), item);
        }
    }

    public static class EqualsTests {
        private final PlayQueueItem item1 = makeItemWithUrl("URL_1");
        private final PlayQueueItem item2 = makeItemWithUrl("URL_2");

        @Test
        public void sameStreams() {
            final List<PlayQueueItem> streams = Collections.nCopies(5, item1);
            final PlayQueue queue1 = makePlayQueue(0, streams);
            final PlayQueue queue2 = makePlayQueue(0, streams);
            assertTrue(queue1.equalStreams(queue2));
            assertTrue(queue1.equalStreamsAndIndex(queue2));
        }

        @Test
        public void sameStreamsDifferentIndex() {
            final List<PlayQueueItem> streams = Collections.nCopies(5, item1);
            final PlayQueue queue1 = makePlayQueue(1, streams);
            final PlayQueue queue2 = makePlayQueue(4, streams);
            assertTrue(queue1.equalStreams(queue2));
            assertFalse(queue1.equalStreamsAndIndex(queue2));
        }

        @Test
        public void sameSizeDifferentItems() {
            final List<PlayQueueItem> streams1 = Collections.nCopies(5, item1);
            final List<PlayQueueItem> streams2 = Collections.nCopies(5, item2);
            final PlayQueue queue1 = makePlayQueue(0, streams1);
            final PlayQueue queue2 = makePlayQueue(0, streams2);
            assertFalse(queue1.equalStreams(queue2));
        }

        @Test
        public void differentSizeStreams() {
            final List<PlayQueueItem> streams1 = Collections.nCopies(5, item1);
            final List<PlayQueueItem> streams2 = Collections.nCopies(6, item2);
            final PlayQueue queue1 = makePlayQueue(0, streams1);
            final PlayQueue queue2 = makePlayQueue(0, streams2);
            assertFalse(queue1.equalStreams(queue2));
        }
    }

    public static class MutationTests {
        private final List<RecyclerView.AdapterDataObserver> adapterObservers = new ArrayList<>();
        private final TestScheduler mainThread = new TestScheduler();
        private Function<Callable<Scheduler>, Scheduler> previousMainSchedulerInitializer;
        private MockedStatic<AndroidSchedulers> androidSchedulers;

        @Before
        public void setUp() {
            previousMainSchedulerInitializer =
                    RxAndroidPlugins.getInitMainThreadSchedulerHandler();
            RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> mainThread);
            androidSchedulers = mockStatic(AndroidSchedulers.class);
            androidSchedulers.when(AndroidSchedulers::mainThread).thenReturn(mainThread);
        }

        @After
        public void tearDown() {
            androidSchedulers.close();
            RxAndroidPlugins.setInitMainThreadSchedulerHandler(previousMainSchedulerInitializer);
        }

        private PlayQueue makeAdapterQueue(final List<PlayQueueItem> streams) {
            final PlayQueue queue = spy(makePlayQueue(0, streams));
            queue.init();
            // JVM Android stubs do not initialize Observable.mObservers. Suppress INIT until
            // the test can provide the adapter's observer collection after construction.
            final Flowable<PlayQueueEvent> withoutInit = queue.getBroadcastReceiver().skip(1);
            doReturn(withoutInit).when(queue).getBroadcastReceiver();
            return queue;
        }

        private PlayQueueAdapter makeAdapter(final PlayQueue queue) {
            final PlayQueueAdapter adapter = new PlayQueueAdapter(mock(Context.class), queue);
            try {
                final Field observableField = RecyclerView.Adapter.class
                        .getDeclaredField("mObservable");
                observableField.setAccessible(true);
                final Field observersField = Observable.class.getDeclaredField("mObservers");
                observersField.setAccessible(true);
                observersField.set(observableField.get(adapter), adapterObservers);
            } catch (final ReflectiveOperationException e) {
                throw new AssertionError("Cannot initialize the JVM Android observer stub", e);
            }
            return adapter;
        }

        @Test
        public void delayedAppendsKeepInsertionPositionsWithFooter() {
            final PlayQueue queue = makeAdapterQueue(List.of(
                    makeItemWithUrl("first"), makeItemWithUrl("second")));
            final PlayQueueAdapter adapter = makeAdapter(queue);
            adapter.setFooter(mock(View.class));
            adapter.showFooter(true);
            final RecyclerView.AdapterDataObserver observer =
                    mock(RecyclerView.AdapterDataObserver.class);
            adapterObservers.add(observer);

            queue.append(List.of(makeItemWithUrl("third"), makeItemWithUrl("fourth")));
            queue.append(List.of(makeItemWithUrl("fifth")));
            assertEquals(6, adapter.getItemCount());
            verifyNoInteractions(observer);

            mainThread.triggerActions();
            final InOrder notifications = inOrder(observer);
            notifications.verify(observer).onItemRangeInserted(2, 2);
            notifications.verify(observer).onItemRangeInserted(4, 1);
            assertSame(queue.getItem(4), adapter.getItems().get(4));
            adapter.dispose();
            queue.dispose();
        }

        @Test
        public void replacingPlayingAutoTailRemovesBeforeInsertingAndRepairsHistory() {
            final PlayQueueItem first = makeItemWithUrl("first");
            final PlayQueueItem auto = makeItemWithUrl("auto");
            auto.setAutoQueued(true);
            final PlayQueue queue = makeAdapterQueue(List.of(first, auto));
            queue.setIndex(1);
            final List<PlayQueueEvent> events = new ArrayList<>();
            queue.getBroadcastReceiver().subscribe(events::add);

            final PlayQueueAdapter adapter = makeAdapter(queue);
            final RecyclerView.AdapterDataObserver observer =
                    mock(RecyclerView.AdapterDataObserver.class);
            adapterObservers.add(observer);
            final PlayQueueItem manual = makeItemWithUrl("manual");
            final PlayQueueItem next = makeItemWithUrl("next");
            queue.append(List.of(manual, next));
            assertEquals(0, queue.getIndex());
            assertSame(first, queue.getItem());
            assertFalse(queue.previous()); // the removed tail is not a previous playable item
            verifyNoInteractions(observer);
            assertTrue(events.isEmpty());

            mainThread.triggerActions();
            assertEquals(2, events.size());
            final RemoveEvent removed = (RemoveEvent) events.get(0);
            assertEquals(1, removed.getRemoveIndex());
            assertEquals(0, removed.getQueueIndex());
            final AppendEvent appended = (AppendEvent) events.get(1);
            assertEquals(1, appended.getStartIndex());
            assertEquals(2, appended.getAmount());
            final InOrder notifications = inOrder(observer);
            notifications.verify(observer).onItemRangeRemoved(1, 1);
            notifications.verify(observer).onItemRangeInserted(1, 2);
            assertEquals(List.of(first, manual, next), adapter.getItems());
            assertEquals(3, adapter.getItemCount());
            adapter.dispose();
            queue.dispose();
        }

        @Test
        public void shuffledReplacementRemovesTailFromBackupAndRestoresManualOrder() {
            final PlayQueueItem first = makeItemWithUrl("first");
            final PlayQueueItem second = makeItemWithUrl("second");
            final PlayQueueItem third = makeItemWithUrl("third");
            final PlayQueueItem auto = makeItemWithUrl("auto");
            auto.setAutoQueued(true);
            final PlayQueueItem manualOne = makeItemWithUrl("manual-one");
            final PlayQueueItem manualTwo = makeItemWithUrl("manual-two");
            final PlayQueue queue = makePlayQueue(0, List.of(first, second, third));
            queue.init();
            final List<PlayQueueEvent> events = new ArrayList<>();
            queue.getBroadcastReceiver().subscribe(events::add);
            events.clear();

            queue.shuffle();
            queue.append(List.of(auto));
            queue.append(List.of(manualOne, manualTwo));
            assertFalse(queue.getStreams().contains(auto));
            assertEquals(0, queue.getIndex());
            queue.unshuffle();
            assertEquals(List.of(first, second, third, manualOne, manualTwo),
                    queue.getStreams());
            assertFalse(queue.isShuffled());
            assertSame(first, queue.getItem());

            mainThread.triggerActions();
            assertEquals(List.of(PlayQueueEvent.Type.REORDER, PlayQueueEvent.Type.APPEND,
                    PlayQueueEvent.Type.REMOVE, PlayQueueEvent.Type.APPEND,
                    PlayQueueEvent.Type.REORDER),
                    events.stream().map(PlayQueueEvent::type).toList());
            assertEquals(3, ((RemoveEvent) events.get(2)).getRemoveIndex());
            assertEquals(3, ((AppendEvent) events.get(3)).getStartIndex());
            assertEquals(2, ((AppendEvent) events.get(3)).getAmount());
            queue.dispose();
        }

        @Test
        public void emptyAppendDoesNotRemoveAutoTailOrEmitAnEvent() {
            final PlayQueueItem auto = makeItemWithUrl("auto");
            auto.setAutoQueued(true);
            final PlayQueue queue = makePlayQueue(0, List.of(auto));
            queue.init();
            final List<PlayQueueEvent> events = new ArrayList<>();
            queue.getBroadcastReceiver().subscribe(events::add);
            events.clear();
            queue.shuffle();
            queue.append(Collections.emptyList());
            assertEquals(List.of(auto), queue.getStreams());
            queue.unshuffle();
            assertEquals(List.of(auto), queue.getStreams());
            mainThread.triggerActions();
            assertEquals(List.of(PlayQueueEvent.Type.REORDER),
                    events.stream().map(PlayQueueEvent::type).toList());
            queue.dispose();
        }

        @Test
        public void replacingSoleAutoTailSelectsAndRecordsNewItem() {
            final PlayQueueItem auto = makeItemWithUrl("auto");
            auto.setAutoQueued(true);
            final PlayQueueItem manual = makeItemWithUrl("manual");
            final PlayQueue queue = makePlayQueue(0, List.of(auto));
            queue.append(List.of(manual));
            assertEquals(0, queue.getIndex());
            assertSame(manual, queue.getItem());
            assertFalse(queue.previous());
            queue.append(List.of(makeItemWithUrl("later")));
            queue.setIndex(1);
            assertTrue(queue.previous());
            assertSame(manual, queue.getItem());
        }

        @Test
        public void replacingVisitedAutoTailLeavesOnlyPlayableHistory() {
            final PlayQueueItem first = makeItemWithUrl("first");
            final PlayQueueItem second = makeItemWithUrl("second");
            final PlayQueueItem auto = makeItemWithUrl("auto");
            auto.setAutoQueued(true);
            final PlayQueue queue = makePlayQueue(0, List.of(first, second, auto));
            queue.setIndex(2);
            queue.setIndex(1);
            queue.setIndex(2);

            queue.append(List.of(makeItemWithUrl("manual")));
            assertEquals(0, queue.getIndex());
            assertTrue(queue.previous());
            assertSame(second, queue.getItem());
            assertTrue(queue.previous());
            assertSame(first, queue.getItem());
            assertFalse(queue.previous());
        }

        @Test
        public void enqueueNextAfterPlayingAutoTailUsesReplacementSelection() {
            final PlayQueueItem first = makeItemWithUrl("first");
            final PlayQueueItem second = makeItemWithUrl("second");
            final PlayQueueItem auto = makeItemWithUrl("auto");
            auto.setAutoQueued(true);
            final PlayQueueItem manual = makeItemWithUrl("manual");
            final PlayQueue queue = makePlayQueue(0, List.of(first, second, auto));
            queue.setIndex(2);
            queue.init();
            final List<PlayQueueEvent> events = new ArrayList<>();
            queue.getBroadcastReceiver().subscribe(events::add);
            events.clear();

            queue.enqueueNext(manual, false);
            assertEquals(List.of(first, manual, second), queue.getStreams());
            assertEquals(0, queue.getIndex());
            mainThread.triggerActions();
            assertEquals(List.of(PlayQueueEvent.Type.REMOVE, PlayQueueEvent.Type.APPEND,
                    PlayQueueEvent.Type.MOVE),
                    events.stream().map(PlayQueueEvent::type).toList());
            assertEquals(2, ((RemoveEvent) events.get(0)).getRemoveIndex());
            assertEquals(2, ((AppendEvent) events.get(1)).getStartIndex());
            queue.dispose();
        }

        @Test
        public void notifyChangeRefreshesAdapterWithoutInsertingItems() {
            final PlayQueue queue = makeAdapterQueue(List.of(makeItemWithUrl("first")));
            final PlayQueueAdapter adapter = makeAdapter(queue);
            final RecyclerView.AdapterDataObserver observer =
                    mock(RecyclerView.AdapterDataObserver.class);
            adapterObservers.add(observer);
            queue.notifyChange();

            mainThread.triggerActions();
            verify(observer).onChanged();
            assertEquals(1, adapter.getItemCount());
            adapter.dispose();
            queue.dispose();
        }
    }

}
