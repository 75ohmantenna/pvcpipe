package org.schabi.newpipe.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.util.function.BooleanSupplier;

/**
 * What the player does with a queue it is asked to play.
 * <p>
 * Starting a queue from scratch needs network calls, so the first three choices exist to avoid
 * that when the stream is already there or its position is known. The checks run in the order
 * of the constants; the first one that applies wins.
 */
enum QueuePlaybackChoice {
    /**
     * The one stream that is playing was asked for again with a position, as a timestamp link
     * does: seek to it.
     */
    SEEK_IN_PLACE,
    /**
     * The same queue at the same index is already loaded, as when the player type changes:
     * keep it and only apply play-when-ready.
     */
    KEEP_CURRENT_QUEUE,
    /** A new queue that may resume: look up the saved position first, then start. */
    RESUME_FROM_HISTORY,
    /** Start the queue. */
    START;

    /**
     * Chooses how to play the incoming queue based on the current playback state.
     *
     * @param hasPlayer       whether an ExoPlayer instance exists
     * @param current         the queue the player has now, if any
     * @param incoming        the queue it is asked to play
     * @param sameQueue       whether both queues have the same streams and index
     * @param resumeRequested whether the request asks to resume from the saved position
     * @param resumeEnabled   the user's "resume playback" setting; only read when needed
     * @return what to do with the incoming queue
     */
    @NonNull
    static QueuePlaybackChoice of(final boolean hasPlayer,
                                  @Nullable final PlayQueue current,
                                  @NonNull final PlayQueue incoming,
                                  final boolean sameQueue,
                                  final boolean resumeRequested,
                                  @NonNull final BooleanSupplier resumeEnabled) {
        if (hasPlayer
                && incoming.size() == 1 && incoming.getItem() != null
                && current != null && current.size() == 1 && current.getItem() != null
                && incoming.getItem().isSameItem(current.getItem())
                && incoming.getItem().getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET) {
            return SEEK_IN_PLACE;
        } else if (hasPlayer && sameQueue && current != null && !current.isDisposed()) {
            return KEEP_CURRENT_QUEUE;
        } else if (resumeRequested
                && resumeEnabled.getAsBoolean()
                && !sameQueue
                && !incoming.isEmpty()
                && incoming.getItem() != null
                && incoming.getItem().getRecoveryPosition() == PlayQueueItem.RECOVERY_UNSET) {
            return RESUME_FROM_HISTORY;
        } else {
            return START;
        }
    }
}
