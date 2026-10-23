/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.mediasource.LoadedMediaSource;
import org.schabi.newpipe.player.mediasource.ManagedMediaSource;
import org.schabi.newpipe.player.mediasource.ManagedMediaSourcePlaylist;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import io.reactivex.rxjava3.core.Single;

/** Real prepared ExoPlayer playlists, with local PCM media and no extractor/network requests. */
@RunWith(AndroidJUnit4.class)
public class PreparedMediaSourceManagerIntegrationTest implements PlaybackListener {
    private static final long WAIT_SECONDS = 30;
    private final List<String> trace = new ArrayList<>();
    private final AtomicReference<Throwable> playbackThreadFailure = new AtomicReference<>();
    private Context context;
    private File directory;
    private File wave;
    private ExoPlayer player;
    private ExoPlayer retiredPlayer;
    private PlayQueue queue;
    private MediaSourceManager manager;
    private ManagedMediaSourcePlaylist playlist;
    private CompletionHandler completionHandler;
    private PlayQueueItem lastSynchronized;
    private Runnable pendingCheck;
    private PlaybackException playerError;
    private CountDownLatch playbackGateRelease;
    private int synchronizations;
    private int edits;
    private int listenerCalls;
    private int resolutions;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(context.getCacheDir().toPath(),
                "prepared-timeline-").toFile();
        wave = new File(directory, "local.wav");
        writeWave(wave);
        main(() -> player = createPlayer());
    }

    private ExoPlayer createPlayer() {
        final ExoPlayer created = new ExoPlayer.Builder(context).build();
        created.setVolume(0);
        created.addListener(new Player.Listener() {
            @Override
            public void onEvents(@NonNull final Player source,
                                 @NonNull final Player.Events events) {
                event("engine state=" + source.getPlaybackState()
                        + " windows=" + source.getCurrentTimeline().getWindowCount()
                        + " index=" + source.getCurrentMediaItemIndex());
            }

            @Override
            public void onPlayerError(@NonNull final PlaybackException error) {
                playerError = error;
                event("engine error=" + error);
            }
        });
        return created;
    }

    @After
    public void tearDown() throws Exception {
        if (playbackGateRelease != null) {
            playbackGateRelease.countDown();
        }
        try {
            main(() -> {
                pendingCheck = null;
                if (manager != null) {
                    manager.dispose();
                }
                if (queue != null) {
                    queue.dispose();
                }
                if (player != null) {
                    player.release();
                }
                if (retiredPlayer != null) {
                    retiredPlayer.release();
                }
            });
        } finally {
            if (wave != null) {
                Files.deleteIfExists(wave.toPath());
            }
            if (directory != null) {
                Files.deleteIfExists(directory.toPath());
            }
        }
    }

    @Test
    public void queuedAppendMoveAndRemovePreserveSameUrlOccurrences() throws Exception {
        final LocalItem first = item("A");
        final LocalItem second = item("B");
        final LocalItem third = item("C");
        final LocalItem fourth = item("D");
        final LocalItem fifth = item("E");
        start(first, second);
        final ManagedMediaSource preloadedSecond = value(() -> playlist.get(1));
        final int originalEdits = value(() -> edits);
        main(() -> {
            // One main-thread turn: the manager cannot consume any delta until all are queued.
            queue.append(List.of(third, fourth));
            queue.move(1, 3);
            queue.remove(0);
            queue.remove(1);
            queue.append(List.of(fifth));
            assertEquals(2, playlist.size());
        });
        await("queued structural deltas and selected C synchronization", () ->
                edits == originalEdits + 5 && playlist.size() == 3
                        && lastSynchronized == third && loadedAt(0, third));
        main(() -> {
            assertEquals(List.of(third, second, fifth), queue.getStreams());
            // Check the surviving B before selecting/loading any other occurrence.
            assertSame(preloadedSecond, playlist.get(1));
            assertTrue(playlist.get(1).isStreamEqual(second));
            queue.setIndex(1); // Its right-hand neighbor E now enters the loading window.
        });
        await("all final occurrences in manager and published engine windows", () ->
                lastSynchronized == second && corresponds(third, second, fifth));
        fence();
        main(() -> {
            assertSame(preloadedSecond, playlist.get(1));
            assertEquals(List.of(third, second, fifth), queue.getStreams());
        });
        assertPlaybackAdvances(second);
    }

    @Test
    public void removingAWithBPreloadedSynchronizesWithoutAnotherResolution() throws Exception {
        final LocalItem first = item("A");
        final LocalItem second = item("B");
        start(first, second);
        final ManagedMediaSource preloadedSecond = value(() -> playlist.get(1));
        final int originalResolutions = value(() -> resolutions);
        final int originalSynchronizations = value(() -> synchronizations);
        final int originalCompletions = value(() -> completionHandler.dispatches);
        main(() -> queue.remove(0));
        await("prepared removal completion synchronizes preloaded B", () ->
                completionHandler.dispatches > originalCompletions
                        && synchronizations > originalSynchronizations
                        && lastSynchronized == second && corresponds(second));
        fence();
        main(() -> {
            assertSame(preloadedSecond, playlist.get(0));
            assertEquals(originalResolutions, resolutions);
            assertEquals(List.of(second), queue.getStreams());
        });
        assertPlaybackAdvances(second);
    }

    @Test
    public void retiredCompletionCannotAffectPreparedReplacementPlayback() throws Exception {
        final LocalItem first = item("A");
        final LocalItem second = item("B");
        start(first, second);
        final CompletionHandler retiredHandler = completionHandler;
        final ManagedMediaSourcePlaylist retiredPlaylist = playlist;
        final int originalCompletions = value(() -> retiredHandler.dispatches);
        final int originalEdits = value(() -> edits);
        blockPlaybackThread();
        main(() -> queue.remove(0));
        await("manager enqueues removal behind playback-thread gate", () ->
                edits == originalEdits + 1 && playlist.size() == 1);
        main(() -> {
            assertEquals(originalCompletions, retiredHandler.dispatches);
            manager.dispose();
            queue.dispose();
            retiredPlayer = player;
            player = createPlayer();
            event("manager retired with old engine completion still retained");
        });

        // All occurrences deliberately share one URL, including this replacement queue.
        // The shared listener now controls a separately prepared and playable engine.
        final LocalItem replacement = item("replacement");
        final LocalItem replacementNeighbor = item("replacement-neighbor");
        start(replacement, replacementNeighbor);
        assertPlaybackAdvances(replacement);
        fence(); // Let the replacement engine acknowledge pause before capturing its frozen clock.
        final ManagedMediaSource replacementSource = value(() -> playlist.get(0));
        final MediaSourceManager replacementManager = manager;
        final int callsBeforeOldCompletion = value(() -> listenerCalls);
        final int syncsBeforeOldCompletion = value(() -> synchronizations);
        final int resolutionsBeforeOldCompletion = value(() -> resolutions);
        final long replacementPosition = value(() -> player.getCurrentPosition());
        main(() -> assertEquals(originalCompletions, retiredHandler.dispatches));

        playbackGateRelease.countDown();
        await("old prepared engine dispatches retained completion after replacement is playing",
                () -> retiredHandler.dispatches > originalCompletions);
        fence(retiredPlaylist);
        fence();
        main(() -> {
            assertSame(replacementManager, manager);
            assertEquals(callsBeforeOldCompletion, listenerCalls);
            assertEquals(syncsBeforeOldCompletion, synchronizations);
            assertEquals(resolutionsBeforeOldCompletion, resolutions);
            assertSame(replacement, lastSynchronized);
            assertSame(replacementSource, playlist.get(0));
            assertSame(replacement, windowOccurrence(player.getCurrentMediaItemIndex()));
            assertTrue(corresponds(replacement, replacementNeighbor));
            assertEquals(replacementPosition, player.getCurrentPosition());
            assertTrue(!player.getPlayWhenReady());
        });
        assertPlaybackAdvances(replacement);
    }

    private void start(final LocalItem first, final LocalItem second) throws Exception {
        value(() -> {
            queue = new SinglePlayQueue(first);
            queue.append(List.of(second));
            queue.init();
            manager = new MediaSourceManager(context, this, queue);
            playlist = managerPlaylist(manager);
            completionHandler = new CompletionHandler();
            // Observe framework dispatch, never capture or manually invoke completion Runnables.
            final Field handler = MediaSourceManager.class
                    .getDeclaredField("removeMediaSourceHandler");
            handler.setAccessible(true);
            handler.set(manager, completionHandler);
            return null;
        });
        await("A prepared and B preloaded", () -> player.getPlaybackState() == Player.STATE_READY
                && lastSynchronized == first && corresponds(first, second));
        fence();
    }

    private boolean loadedAt(final int index, final PlayQueueItem expected) {
        return playlist.get(index) instanceof LoadedMediaSource
                && playlist.get(index).isStreamEqual(expected);
    }

    private boolean corresponds(final PlayQueueItem... expected) {
        if (playlist.size() != expected.length
                || player.getCurrentTimeline().getWindowCount() != expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (!loadedAt(index, expected[index]) || windowOccurrence(index) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private PlayQueueItem windowOccurrence(final int index) {
        final Timeline.Window window = new Timeline.Window();
        player.getCurrentTimeline().getWindow(index, window);
        return MediaItemTag.from(window.mediaItem)
                .flatMap(tag -> tag.getMaybeExtras(ManagedMediaSource.class))
                .filter(LoadedMediaSource.class::isInstance)
                .map(LoadedMediaSource.class::cast)
                .map(LoadedMediaSource::getStream).orElse(null);
    }

    private void assertPlaybackAdvances(final PlayQueueItem expected) throws Exception {
        final CountDownLatch advanced = new CountDownLatch(1);
        final CountDownLatch seekProcessed = new CountDownLatch(1);
        main(() -> {
            assertSame(expected, windowOccurrence(queue.getIndex()));
            player.seekTo(queue.getIndex(), 0);
            player.createMessage((type, payload) -> seekProcessed.countDown()).send();
        });
        awaitLatch("engine processes seek before timed advancement proof", seekProcessed);
        main(() -> {
            player.createMessage((type, payload) -> advanced.countDown())
                    .setPosition(queue.getIndex(), 300).send();
            player.play();
        });
        awaitLatch("surviving local PCM playback reaches 300 ms", advanced);
        main(() -> {
            assertSame(expected, windowOccurrence(player.getCurrentMediaItemIndex()));
            // The playback-thread timed message proves advancement; the application clock
            // is an asynchronously published estimate and need not yet read exactly 300 ms.
            event("playback reached timed message; application clock="
                    + player.getCurrentPosition());
            assertTrue("Real player remains playing: " + trace, player.isPlaying());
            player.pause();
        });
    }

    private void blockPlaybackThread() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        playbackGateRelease = new CountDownLatch(1);
        main(() -> player.createMessage((type, payload) -> {
            entered.countDown();
            try {
                if (!playbackGateRelease.await(WAIT_SECONDS * 3, TimeUnit.SECONDS)) {
                    playbackThreadFailure.set(new AssertionError("Playback gate timed out"));
                }
            } catch (final InterruptedException error) {
                Thread.currentThread().interrupt();
                playbackThreadFailure.set(error);
            }
        }).send());
        awaitLatch("actual ExoPlayer playback thread enters gate", entered);
    }

    private void fence() throws Exception {
        fence(playlist);
    }

    private void fence(final ManagedMediaSourcePlaylist source) throws Exception {
        final CountDownLatch completed = new CountDownLatch(1);
        main(() -> source.getParentMediaSource().removeMediaSourceRange(
                source.size(), source.size(), new Handler(Looper.getMainLooper()), () -> {
                    event("prepared playlist fence completed");
                    completed.countDown();
                }));
        awaitLatch("prepared playback-thread playlist fence", completed);
        main(() -> { }); // Drain main-thread completion posts from the same engine update.
    }

    private void await(final String description, final BooleanSupplier condition) throws Exception {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        main(() -> {
            pendingCheck = () -> {
                try {
                    if (playerError != null) {
                        throw new AssertionError("Local media playback failed", playerError);
                    }
                    if (condition.getAsBoolean()) {
                        completed.countDown();
                    }
                } catch (final Throwable error) {
                    failure.set(error);
                    completed.countDown();
                }
            };
            event("await " + description);
        });
        try {
            awaitLatch(description, completed);
            if (failure.get() != null) {
                throw new AssertionError(description + ": " + value(() -> trace.toString()),
                        failure.get());
            }
        } finally {
            main(() -> pendingCheck = null);
        }
    }

    private void awaitLatch(final String description, final CountDownLatch latch) throws Exception {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError(description + ": " + value(() -> trace.toString()));
        }
        if (playbackThreadFailure.get() != null) {
            throw new AssertionError(description, playbackThreadFailure.get());
        }
        value(() -> {
            if (playerError != null) {
                throw new AssertionError(description, playerError);
            }
            return null;
        });
    }

    private void event(final String description) {
        trace.add(description);
        if (trace.size() > 100) {
            trace.remove(0);
        }
        if (pendingCheck != null) {
            pendingCheck.run();
        }
    }

    private static void main(final Runnable action) throws Exception {
        value(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T value(final Callable<T> action) throws Exception {
        final FutureTask<T> result = new FutureTask<>(action);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(result);
        return result.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static ManagedMediaSourcePlaylist managerPlaylist(final MediaSourceManager owner)
            throws ReflectiveOperationException {
        final Field field = MediaSourceManager.class.getDeclaredField("playlist");
        field.setAccessible(true);
        return (ManagedMediaSourcePlaylist) field.get(owner);
    }

    private LocalItem item(final String occurrence) {
        // Same URL deliberately: occurrence ownership cannot be inferred from the URI or title.
        final String url = Uri.fromFile(wave).toString();
        final StreamInfo info = new StreamInfo(ServiceList.SoundCloud.getServiceId(), url, url,
                StreamType.AUDIO_STREAM, "local", occurrence, 0);
        info.setDuration(10);
        return new LocalItem(info);
    }

    private static void writeWave(final File destination) throws Exception {
        final int sampleRate = 8000;
        final int samples = sampleRate * 10;
        final int dataBytes = samples * 2;
        final ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes);
        buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        buffer.putShort((short) 1).putShort((short) 1).putInt(sampleRate);
        buffer.putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        for (int sample = 0; sample < samples; sample++) {
            buffer.putShort((short) (2000 * Math.sin(2 * Math.PI * 440 * sample / sampleRate)));
        }
        Files.write(destination.toPath(), buffer.array());
    }

    private static final class LocalItem extends PlayQueueItem {
        private final StreamInfo info;

        LocalItem(final StreamInfo metadata) {
            super(metadata);
            info = metadata;
        }

        @NonNull
        @Override
        public Single<StreamInfo> getStream() {
            return Single.just(info);
        }
    }

    private final class CompletionHandler extends Handler {
        private int dispatches;

        CompletionHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void dispatchMessage(@NonNull final Message message) {
            super.dispatchMessage(message);
            dispatches++;
            event("framework dispatched manager completion " + dispatches);
        }
    }

    @Override
    public boolean isApproachingPlaybackEdge(final long timeToEndMillis) {
        return false;
    }

    @Override
    public void onPlaybackBlock() {
        listenerCalls++;
        player.stop();
        event("manager blocks");
    }

    @Override
    public void onPlaybackUnblock(final MediaSource mediaSource) {
        listenerCalls++;
        try {
            // INIT's blocking reset replaces the manager's original empty playlist.
            playlist = managerPlaylist(manager);
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError("Cannot observe the prepared manager playlist", error);
        }
        player.setMediaSource(mediaSource);
        player.prepare();
        event("manager unblocks and prepares real source");
    }

    @Override
    public void onPlaybackSynchronize(@NonNull final PlayQueueItem item, final boolean wasBlocked) {
        listenerCalls++;
        synchronizations++;
        lastSynchronized = item;
        player.seekTo(queue.indexOf(item), 0);
        event("manager synchronizes " + item.getTitle());
    }

    @Override
    public MediaSource sourceOf(final PlayQueueItem item, final StreamInfo info) {
        listenerCalls++;
        resolutions++;
        event("resolve local " + item.getTitle());
        final MediaItem mediaItem = new MediaItem.Builder().setUri(Uri.fromFile(wave))
                .setTag(StreamInfoTag.of(info)).build();
        return new ProgressiveMediaSource.Factory(new DefaultDataSource.Factory(context))
                .createMediaSource(mediaItem);
    }

    @Override
    public void onPlaybackShutdown() {
        listenerCalls++;
        event("manager shutdown");
    }

    @Override
    public void onPlayQueueEdited() {
        listenerCalls++;
        edits++;
        event("manager queue edit " + edits);
    }
}
