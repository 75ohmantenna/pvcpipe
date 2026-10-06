package org.schabi.newpipe.player.history;

import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_INTERNAL;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_REMOVE;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ONE;

import android.content.Context;

import androidx.annotation.Nullable;

import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;

/** Owns playback history interpretation, resume outcomes and recording subscriptions. */
public final class PlaybackHistory {
    private final Environment environment;
    private final WriteQueue writes;

    public PlaybackHistory(final Context context) {
        this(new AndroidPlaybackHistoryEnvironment(context),
                AndroidPlaybackHistoryEnvironment.WRITES);
    }

    PlaybackHistory(final Environment environment) {
        this(environment, new WriteQueue());
    }

    PlaybackHistory(final Environment environment, final WriteQueue writes) {
        this.environment = environment;
        this.writes = writes;
    }

    public enum Event {
        VIEWED, PROGRESS, COMPLETED
    }

    /**
     * Records facts captured together by the player; queue recovery works even without history.
     * @param event the kind of playback observation
     * @param snapshot coherent stream, queue and position facts
     */
    public void record(final Event event, final Snapshot snapshot) {
        if (event == Event.PROGRESS) {
            if (snapshot.queue == null || snapshot.mediaIndex < 0
                    || snapshot.queue.getIndex() != snapshot.mediaIndex) {
                return;
            }
            snapshot.queue.setRecovery(snapshot.queue.getIndex(), snapshot.contentPosition);
        }
        if (snapshot.info == null) {
            return;
        }
        if (event == Event.VIEWED) {
            writes.add(environment.viewed(snapshot.info).ignoreElement());
        } else if (environment.saveEnabled()) {
            final long position = event == Event.COMPLETED
                    ? (snapshot.info.getDuration() + 1) * 1000 : snapshot.position;
            writes.add(environment.save(snapshot.info, position)
                    .doOnError(environment::logError));
        }
    }

    /**
     * Interprets history effects before moving the queue to the incoming media item.
     * @param snapshot facts captured before the queue index changes
     * @param transition the observed media-item discontinuity
     */
    public void onDiscontinuity(final Snapshot snapshot, final Discontinuity transition) {
        if (snapshot.queue == null) {
            return;
        }
        switch (transition.reason) {
            case DISCONTINUITY_REASON_AUTO_TRANSITION:
            case DISCONTINUITY_REASON_REMOVE:
                if (transition.repeatMode == REPEAT_MODE_ONE
                        && transition.newIndex == snapshot.queue.getIndex()) {
                    record(Event.VIEWED, snapshot);
                    break;
                }
                // fall through
            case DISCONTINUITY_REASON_SEEK:
                if (transition.prepared) {
                    record(Event.PROGRESS, snapshot);
                }
                // fall through
            case DISCONTINUITY_REASON_SEEK_ADJUSTMENT:
            case DISCONTINUITY_REASON_INTERNAL:
                if (!transition.blocked && transition.newIndex != snapshot.queue.getIndex()) {
                    record(Event.COMPLETED, snapshot);
                    snapshot.queue.setIndex(transition.newIndex);
                }
                break;
            default:
                break;
        }
    }

    /**
     * Waits for earlier accepted recordings before lookup, then emits an unfinished position.
     * Absent, finished or failed history yields RECOVERY_UNSET. Disposal cancels lookup/delivery
     * without canceling recordings; extraction runs outside the recording queue.
     * @param item the stream to resume
     * @return its unfinished saved position, or RECOVERY_UNSET
     */
    public Single<Long> resumePosition(final PlayQueueItem item) {
        return Single.defer(() -> writes.afterPrevious()
                .andThen(Maybe.defer(() -> environment.load(item)))
                .map(state -> state.isFinished(item.getDuration())
                        ? PlayQueueItem.RECOVERY_UNSET : state.getProgressMillis())
                .defaultIfEmpty(PlayQueueItem.RECOVERY_UNSET)
                .doOnError(environment::logError)
                .onErrorReturnItem(PlayQueueItem.RECOVERY_UNSET));
    }

    public static final class Snapshot {
        @Nullable
        private final StreamInfo info;
        @Nullable
        private final PlayQueue queue;
        private final int mediaIndex;
        private final long contentPosition;
        private final long position;

        public Snapshot(@Nullable final StreamInfo info, @Nullable final PlayQueue queue,
                        final int mediaIndex, final long contentPosition, final long position) {
            this.info = info;
            this.queue = queue;
            this.mediaIndex = mediaIndex;
            this.contentPosition = contentPosition;
            this.position = position;
        }
    }

    public static final class Discontinuity {
        private final int reason;
        private final int newIndex;
        private final int repeatMode;
        private final boolean prepared;
        private final boolean blocked;

        public Discontinuity(final int reason, final int newIndex, final int repeatMode,
                             final boolean prepared, final boolean blocked) {
            this.reason = reason;
            this.newIndex = newIndex;
            this.repeatMode = repeatMode;
            this.prepared = prepared;
            this.blocked = blocked;
        }
    }

    /** Application-owned ordering; queued records never retain a Player or its UI. */
    static final class WriteQueue {
        private final Subject<Completable> operations =
                PublishSubject.<Completable>create().toSerialized();

        WriteQueue() {
            operations.concatMapCompletable(operation -> operation.onErrorComplete()).subscribe();
        }

        void add(final Completable operation) {
            operations.onNext(operation);
        }

        Completable afterPrevious() {
            final CompletableSubject ready = CompletableSubject.create();
            add(Completable.fromAction(ready::onComplete));
            return ready;
        }
    }

    interface Environment {
        boolean saveEnabled();

        Maybe<Long> viewed(StreamInfo info);

        Completable save(StreamInfo info, long position);

        Maybe<StreamStateEntity> load(PlayQueueItem item);

        void logError(Throwable error);
    }
}
