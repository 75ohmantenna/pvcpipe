package org.schabi.newpipe.player.history;

import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;

/** Scripts preferences and asynchronous operations without replacing history policy. */
final class PlaybackHistoryTestEnvironment implements PlaybackHistory.Environment {
    boolean saveEnabled = true;
    final List<StreamInfo> viewed = new ArrayList<>();
    final List<StreamInfo> saved = new ArrayList<>();
    final List<Long> positions = new ArrayList<>();
    final List<Integer> queueIndicesOnSave = new ArrayList<>();
    final List<Throwable> errors = new ArrayList<>();
    final Deque<Completable> saves = new ArrayDeque<>();
    final Deque<Maybe<Long>> views = new ArrayDeque<>();
    Maybe<StreamStateEntity> load = Maybe.empty();
    PlayQueue observedQueue;
    Long persistedPosition;
    int persistedViews;

    @Override
    public boolean saveEnabled() {
        return saveEnabled;
    }

    @Override
    public Maybe<Long> viewed(final StreamInfo info) {
        viewed.add(info);
        final Maybe<Long> operation = views.isEmpty() ? Maybe.just(1L) : views.removeFirst();
        return operation.doOnSuccess(ignored -> persistedViews++);
    }

    @Override
    public Completable save(final StreamInfo info, final long position) {
        saved.add(info);
        positions.add(position);
        if (observedQueue != null) {
            queueIndicesOnSave.add(observedQueue.getIndex());
        }
        final Completable operation = saves.isEmpty()
                ? Completable.complete() : saves.removeFirst();
        return operation.doOnComplete(() -> persistedPosition = position);
    }

    @Override
    public Maybe<StreamStateEntity> load(final PlayQueueItem item) {
        return load;
    }

    @Override
    public void logError(final Throwable error) {
        errors.add(error);
    }

    static StreamInfo info(final String id) {
        final String url = "https://example.com/watch/" + id;
        final StreamInfo info = new StreamInfo(0, url, url, StreamType.VIDEO_STREAM, id, id, 0);
        info.setDuration(120);
        return info;
    }

    static PlayQueue queue(final StreamInfo... infos) {
        final PlayQueue queue = new SinglePlayQueue(new PlayQueueItem(infos[0]));
        for (int i = 1; i < infos.length; i++) {
            queue.append(List.of(new PlayQueueItem(infos[i])));
        }
        return queue;
    }
}
