package org.schabi.newpipe.player.history;

import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_INTERNAL;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_REMOVE;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SKIP;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.schabi.newpipe.player.history.PlaybackHistoryTestEnvironment.info;
import static org.schabi.newpipe.player.history.PlaybackHistoryTestEnvironment.queue;
import static org.schabi.newpipe.player.playqueue.PlayQueueItem.RECOVERY_UNSET;

import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.history.PlaybackHistory.Discontinuity;
import org.schabi.newpipe.player.history.PlaybackHistory.Event;
import org.schabi.newpipe.player.history.PlaybackHistory.Snapshot;
import org.schabi.newpipe.player.playqueue.PlayQueue;

import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.MaybeSubject;

public class PlaybackHistoryTest {
    private PlaybackHistoryTestEnvironment environment;
    private PlaybackHistory history;
    private StreamInfo stream;
    private PlayQueue queue;

    @Before
    public void setUp() {
        environment = new PlaybackHistoryTestEnvironment();
        history = new PlaybackHistory(environment);
        stream = info("current");
        queue = queue(stream, info("next"));
        environment.observedQueue = queue;
    }

    @Test
    public void progressKeepsContentRecoverySeparateFromSavedPlaybackPosition() {
        history.record(Event.PROGRESS, snapshot(0, 31_000, 33_000));

        assertEquals(31_000, queue.getItem().getRecoveryPosition());
        assertEquals(List.of(33_000L), environment.positions);
        assertEquals(List.of(stream), environment.saved);
    }

    @Test
    public void disabledHistoryStillUpdatesQueueRecovery() {
        environment.saveEnabled = false;

        history.record(Event.PROGRESS, snapshot(0, 31_000, 33_000));

        assertEquals(31_000, queue.getItem().getRecoveryPosition());
        assertTrue(environment.saved.isEmpty());
    }

    @Test
    public void mismatchedWindowAndMissingPlaybackDoNotChangeRecoveryOrSave() {
        history.record(Event.PROGRESS, snapshot(1, 31_000, 33_000));
        history.record(Event.PROGRESS, snapshot(-1, 31_000, 33_000));
        history.record(Event.PROGRESS, new Snapshot(stream, null, 0, 31_000, 33_000));

        assertEquals(RECOVERY_UNSET, queue.getItem().getRecoveryPosition());
        assertTrue(environment.saved.isEmpty());
    }

    @Test
    public void missingStreamInfoKeepsRecoveryButDoesNotWrite() {
        history.record(Event.PROGRESS, new Snapshot(null, queue, 0, 31_000, 33_000));

        assertEquals(31_000, queue.getItem().getRecoveryPosition());
        assertTrue(environment.saved.isEmpty());
    }

    @Test
    public void completedProgressUsesDurationMarkerWithoutWindowAlignment() {
        history.record(Event.COMPLETED, new Snapshot(stream, null, -1, 0, 0));

        assertEquals(List.of(121_000L), environment.positions);
        assertEquals(RECOVERY_UNSET, queue.getItem().getRecoveryPosition());
    }

    @Test
    public void completedAndViewedIgnoreMissingMetadata() {
        final Snapshot missing = new Snapshot(null, queue, 0, 31_000, 33_000);
        history.record(Event.COMPLETED, missing);
        history.record(Event.VIEWED, missing);

        assertTrue(environment.positions.isEmpty());
        assertTrue(environment.viewed.isEmpty());
    }

    @Test
    public void completionHonorsSavePreference() {
        environment.saveEnabled = false;

        history.record(Event.COMPLETED, snapshot(0, 31_000, 33_000));

        assertTrue(environment.positions.isEmpty());
    }

    @Test
    public void viewCountingUsesItsOwnHistoryPreferenceInsteadOfProgressPreference() {
        environment.saveEnabled = false;
        final Snapshot missingPlayer = new Snapshot(stream, null, -1, 0, 0);

        history.record(Event.VIEWED, missingPlayer);
        history.record(Event.VIEWED, missingPlayer);

        assertEquals(List.of(stream, stream), environment.viewed);
        assertTrue(environment.positions.isEmpty());
    }

    @Test
    public void repeatOneTransitionsCountAnotherViewWithoutSavingOrChangingQueue() {
        for (final int reason : List.of(DISCONTINUITY_REASON_AUTO_TRANSITION,
                DISCONTINUITY_REASON_REMOVE)) {
            history.onDiscontinuity(snapshot(0, 31_000, 33_000),
                    new Discontinuity(reason, 0, REPEAT_MODE_ONE, true, false));
        }

        assertEquals(List.of(stream, stream), environment.viewed);
        assertTrue(environment.positions.isEmpty());
        assertEquals(0, queue.getIndex());
        assertEquals(RECOVERY_UNSET, queue.getItem().getRecoveryPosition());
    }

    @Test
    public void preparedSeekSavesProgressBeforeCompletingAndChangingQueue() {
        history.onDiscontinuity(snapshot(0, 31_000, 33_000),
                new Discontinuity(DISCONTINUITY_REASON_SEEK, 1,
                        REPEAT_MODE_OFF, true, false));

        assertEquals(List.of(33_000L, 121_000L), environment.positions);
        assertEquals(List.of(0, 0), environment.queueIndicesOnSave);
        assertEquals(31_000, queue.getItem(0).getRecoveryPosition());
        assertEquals(1, queue.getIndex());
    }

    @Test
    public void preparedSeekWithinSameItemOnlySavesNormalProgress() {
        history.onDiscontinuity(snapshot(0, 31_000, 33_000),
                new Discontinuity(DISCONTINUITY_REASON_SEEK, 0,
                        REPEAT_MODE_OFF, true, false));

        assertEquals(List.of(33_000L), environment.positions);
        assertEquals(0, queue.getIndex());
    }

    @Test
    public void transitionWithAlreadyChangedWindowSkipsWrongItemProgress() {
        history.onDiscontinuity(snapshot(1, 31_000, 33_000),
                new Discontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION, 1,
                        REPEAT_MODE_OFF, true, false));

        assertEquals(List.of(121_000L), environment.positions);
        assertEquals(List.of(0), environment.queueIndicesOnSave);
        assertEquals(RECOVERY_UNSET, queue.getItem(0).getRecoveryPosition());
        assertEquals(1, queue.getIndex());
    }

    @Test
    public void unpreparedSeekStillCompletesPreviousItemWhenIndexChanges() {
        history.onDiscontinuity(snapshot(0, 31_000, 33_000),
                new Discontinuity(DISCONTINUITY_REASON_SEEK, 1,
                        REPEAT_MODE_OFF, false, false));

        assertEquals(List.of(121_000L), environment.positions);
        assertEquals(1, queue.getIndex());
    }

    @Test
    public void internalAndSeekAdjustmentTransitionsOnlyCompletePreviousItem() {
        for (final int reason : List.of(DISCONTINUITY_REASON_INTERNAL,
                DISCONTINUITY_REASON_SEEK_ADJUSTMENT)) {
            queue.setIndex(0);
            history.onDiscontinuity(snapshot(0, 31_000, 33_000),
                    new Discontinuity(reason, 1, REPEAT_MODE_OFF, true, false));
        }

        assertEquals(List.of(121_000L, 121_000L), environment.positions);
        assertEquals(RECOVERY_UNSET, queue.getItem(0).getRecoveryPosition());
    }

    @Test
    public void blockedTransitionAndSkippedTransitionKeepQueueAndHistoryUntouched() {
        history.onDiscontinuity(snapshot(0, 31_000, 33_000),
                new Discontinuity(DISCONTINUITY_REASON_INTERNAL, 1,
                        REPEAT_MODE_OFF, true, true));
        history.onDiscontinuity(snapshot(0, 31_000, 33_000),
                new Discontinuity(DISCONTINUITY_REASON_SKIP, 1,
                        REPEAT_MODE_OFF, true, false));

        assertTrue(environment.positions.isEmpty());
        assertEquals(0, queue.getIndex());
    }

    @Test
    public void blockedPreparedSeekStillSavesProgressWithoutChangingQueue() {
        history.onDiscontinuity(snapshot(0, 31_000, 33_000),
                new Discontinuity(DISCONTINUITY_REASON_SEEK, 1,
                        REPEAT_MODE_OFF, true, true));

        assertEquals(List.of(33_000L), environment.positions);
        assertEquals(0, queue.getIndex());
    }

    @Test
    public void absentQueueTransitionDoesNothing() {
        history.onDiscontinuity(new Snapshot(stream, null, 0, 31_000, 33_000),
                new Discontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION, 1,
                        REPEAT_MODE_OFF, true, false));

        assertTrue(environment.positions.isEmpty());
        assertTrue(environment.viewed.isEmpty());
    }

    @Test
    public void unfinishedResumeReturnsSavedPosition() {
        environment.load = Maybe.just(new StreamStateEntity(1, 45_000));

        history.resumePosition(queue.getItem()).test().assertResult(45_000L);
    }

    @Test
    public void missingAndFinishedHistoryBothStartWithoutRecovery() {
        history.resumePosition(queue.getItem()).test().assertResult(RECOVERY_UNSET);
        environment.load = Maybe.just(new StreamStateEntity(1, 100_000));
        history.resumePosition(queue.getItem()).test().assertResult(RECOVERY_UNSET);
    }

    @Test
    public void failedResumeLogsAndFallsBackToPlaybackWithoutHistory() {
        final Throwable error = new IllegalStateException("scripted lookup failure");
        environment.load = Maybe.error(error);

        history.resumePosition(queue.getItem()).test().assertResult(RECOVERY_UNSET);

        assertEquals(List.of(error), environment.errors);
    }

    @Test
    public void disposedResumeDetachesItsPendingLookup() {
        final MaybeSubject<StreamStateEntity> pending = MaybeSubject.create();
        environment.load = pending;
        final TestObserver<Long> observer = history.resumePosition(queue.getItem()).test();
        assertTrue(pending.hasObservers());

        observer.dispose();
        pending.onSuccess(new StreamStateEntity(1, 45_000));

        assertFalse(pending.hasObservers());
        observer.assertNoValues();
    }

    @Test
    public void failedSaveIsLoggedAndDoesNotPreventFollowingSave() {
        final Throwable error = new IllegalStateException("scripted save failure");
        environment.saves.add(Completable.error(error));

        history.record(Event.PROGRESS, snapshot(0, 31_000, 33_000));
        history.record(Event.PROGRESS, snapshot(0, 41_000, 43_000));

        assertEquals(List.of(error), environment.errors);
        assertEquals(Long.valueOf(43_000), environment.persistedPosition);
    }

    @Test
    public void resetCancelsCurrentWritesButAllowsFollowingWrites() {
        final CompletableSubject save = CompletableSubject.create();
        final MaybeSubject<Long> view = MaybeSubject.create();
        environment.saves.add(save);
        environment.views.add(view);
        history.record(Event.PROGRESS, snapshot(0, 31_000, 33_000));
        history.record(Event.VIEWED, snapshot(0, 31_000, 33_000));
        assertTrue(save.hasObservers());
        assertTrue(view.hasObservers());

        history.reset();

        assertFalse(save.hasObservers());
        assertFalse(view.hasObservers());
        history.record(Event.PROGRESS, snapshot(0, 41_000, 43_000));
        assertEquals(Long.valueOf(43_000), environment.persistedPosition);
    }

    @Test
    public void existingIndependentSubscriptionsAllowConcurrentPendingWrites() {
        final CompletableSubject first = CompletableSubject.create();
        final CompletableSubject second = CompletableSubject.create();
        environment.saves.add(first);
        environment.saves.add(second);

        history.record(Event.PROGRESS, snapshot(0, 31_000, 33_000));
        history.record(Event.PROGRESS, snapshot(0, 41_000, 43_000));

        assertTrue(first.hasObservers());
        assertTrue(second.hasObservers());
    }

    private Snapshot snapshot(final int mediaIndex, final long contentPosition,
                              final long position) {
        return new Snapshot(stream, queue, mediaIndex, contentPosition, position);
    }
}
