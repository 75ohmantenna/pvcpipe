package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.schabi.newpipe.player.QueuePlaybackChoice.KEEP_CURRENT_QUEUE;
import static org.schabi.newpipe.player.QueuePlaybackChoice.RESUME_FROM_HISTORY;
import static org.schabi.newpipe.player.QueuePlaybackChoice.SEEK_IN_PLACE;
import static org.schabi.newpipe.player.QueuePlaybackChoice.START;

import org.junit.Test;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.util.function.BooleanSupplier;

public class QueuePlaybackChoiceTest {
    private static final BooleanSupplier ENABLED = () -> true;
    private static final BooleanSupplier DISABLED = () -> false;
    private static final BooleanSupplier MUST_NOT_BE_READ = () -> {
        throw new AssertionError("the resume setting was read");
    };

    private final PlayQueueItem playing = item(PlayQueueItem.RECOVERY_UNSET);
    private final PlayQueue current = queue(1, playing);

    @Test
    public void sameSingleStreamWithAPositionSeeksInPlace() {
        final PlayQueue incoming = queue(1, sameItemAs(playing, 42_000L));

        assertEquals(SEEK_IN_PLACE,
                QueuePlaybackChoice.of(true, current, incoming, false, false, MUST_NOT_BE_READ));
        // It wins over the later choices even when they would apply too.
        assertEquals(SEEK_IN_PLACE,
                QueuePlaybackChoice.of(true, current, incoming, true, true, ENABLED));
    }

    @Test
    public void seekingInPlaceNeedsAPlayerAndTwoSingleQueuesWithTheSameItemAndAPosition() {
        final PlayQueue incoming = queue(1, sameItemAs(playing, 42_000L));

        assertEquals(START,
                QueuePlaybackChoice.of(false, current, incoming, false, false, DISABLED));
        assertEquals(START,
                QueuePlaybackChoice.of(true, null, incoming, false, false, DISABLED));
        assertEquals(START, QueuePlaybackChoice.of(true, queue(2, playing), incoming,
                false, false, DISABLED));
        assertEquals(START, QueuePlaybackChoice.of(true, current,
                queue(2, sameItemAs(playing, 42_000L)), false, false, DISABLED));
        assertEquals(START, QueuePlaybackChoice.of(true, current,
                queue(1, item(42_000L)), false, false, DISABLED));
        assertEquals(START, QueuePlaybackChoice.of(true, current,
                queue(1, sameItemAs(playing, PlayQueueItem.RECOVERY_UNSET)),
                false, false, DISABLED));
    }

    @Test
    public void sameQueueThatIsStillLoadedIsKept() {
        final PlayQueue incoming = queue(2, item(PlayQueueItem.RECOVERY_UNSET));

        assertEquals(KEEP_CURRENT_QUEUE,
                QueuePlaybackChoice.of(true, current, incoming, true, true, MUST_NOT_BE_READ));
    }

    @Test
    public void sameQueueIsStartedAgainWithoutAPlayerOrOnceItIsDisposed() {
        final PlayQueue incoming = queue(2, item(PlayQueueItem.RECOVERY_UNSET));
        final PlayQueue disposed = queue(1, playing);
        when(disposed.isDisposed()).thenReturn(true);

        assertEquals(START,
                QueuePlaybackChoice.of(false, current, incoming, true, true, ENABLED));
        assertEquals(START,
                QueuePlaybackChoice.of(true, disposed, incoming, true, true, ENABLED));
    }

    @Test
    public void newQueueWithoutAPositionResumesFromHistoryWhenAskedAndAllowed() {
        final PlayQueue incoming = queue(2, item(PlayQueueItem.RECOVERY_UNSET));

        assertEquals(RESUME_FROM_HISTORY,
                QueuePlaybackChoice.of(true, current, incoming, false, true, ENABLED));
        assertEquals(RESUME_FROM_HISTORY,
                QueuePlaybackChoice.of(false, null, incoming, false, true, ENABLED));
    }

    @Test
    public void resumingNeedsTheRequestTheSettingANewQueueAnItemAndNoPosition() {
        final PlayQueue incoming = queue(2, item(PlayQueueItem.RECOVERY_UNSET));
        final PlayQueue empty = queue(0, null);
        when(empty.isEmpty()).thenReturn(true);

        assertEquals(START,
                QueuePlaybackChoice.of(true, current, incoming, false, false, MUST_NOT_BE_READ));
        assertEquals(START,
                QueuePlaybackChoice.of(true, current, incoming, false, true, DISABLED));
        assertEquals(START,
                QueuePlaybackChoice.of(false, current, incoming, true, true, ENABLED));
        assertEquals(START, QueuePlaybackChoice.of(true, current, empty, false, true, ENABLED));
        assertEquals(START, QueuePlaybackChoice.of(true, current, queue(2, null),
                false, true, ENABLED));
        assertEquals(START, QueuePlaybackChoice.of(true, current, queue(2, item(42_000L)),
                false, true, ENABLED));
    }

    private static PlayQueueItem item(final long recoveryPosition) {
        final PlayQueueItem item = mock(PlayQueueItem.class);
        when(item.getRecoveryPosition()).thenReturn(recoveryPosition);
        return item;
    }

    private static PlayQueueItem sameItemAs(final PlayQueueItem other,
                                            final long recoveryPosition) {
        final PlayQueueItem item = item(recoveryPosition);
        when(item.isSameItem(other)).thenReturn(true);
        return item;
    }

    private static PlayQueue queue(final int size, final PlayQueueItem current) {
        final PlayQueue queue = mock(PlayQueue.class);
        when(queue.size()).thenReturn(size);
        when(queue.getItem()).thenReturn(current);
        return queue;
    }
}
