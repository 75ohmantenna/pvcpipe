package org.schabi.newpipe.fragments.detail;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

import java.util.Iterator;
import java.util.LinkedList;

/**
 * The navigation history of the detail screen. The top entry is the video on screen, and the
 * Back key returns to the entry below it.
 * <p>
 * Each entry keeps the play queue it was opened with. Two entries next to each other never hold
 * queues with the same streams.
 */
final class DetailStack {
    private final LinkedList<StackItem> items = new LinkedList<>();

    boolean isEmpty() {
        return items.isEmpty();
    }

    void clear() {
        items.clear();
    }

    /**
     * Adds the stream that was just loaded, unless the top entry already has a queue with the
     * same streams.
     *
     * @param serviceId the service of the loaded stream
     * @param url       the url of the loaded stream
     * @param title     the title of the loaded stream
     * @param queue     the queue the stream was opened with
     */
    void onStreamLoaded(final int serviceId, final String url, final String title,
                        @NonNull final PlayQueue queue) {
        final StackItem top = items.peek();
        if (top == null || !top.getPlayQueue().equalStreams(queue)) {
            items.push(new StackItem(serviceId, url, title, queue));
        }
    }

    /**
     * Follows the queue the player reports.
     * <p>
     * A queue with other streams than the top entry's becomes a new entry for its current item,
     * which gives a history of played items. Otherwise the oldest entry with the same streams
     * takes over the reported queue: the player disposes its queue whenever its service is
     * destroyed, and the entry would be left with a queue that no longer tracks the playback
     * position.
     *
     * @param queue the queue the player is playing
     */
    void onQueueUpdate(@NonNull final PlayQueue queue) {
        final StackItem top = items.peek();
        if (top != null && !top.getPlayQueue().equalStreams(queue)) {
            final PlayQueueItem current = queue.getItem();
            if (current != null) {
                items.push(new StackItem(current.getServiceId(), current.getUrl(),
                        current.getTitle(), queue));
                return;
            }
        }

        final StackItem sameStreams = findOldestWithStreamsOf(queue);
        if (sameStreams != null) {
            sameStreams.setPlayQueue(queue);
        }
    }

    /**
     * Renames the oldest entry whose queue has the same streams as the playing one.
     * <p>
     * An entry is created for the first stream played from its queue. A queue with several
     * streams, such as a playlist or a channel, gives a new title and url with every stream it
     * plays, and the entry follows.
     *
     * @param queue the queue the player is playing
     * @param title the title of the stream that is playing now
     * @param url   the url of the stream that is playing now
     */
    void onMetadataUpdate(@NonNull final PlayQueue queue, final String title, final String url) {
        final StackItem sameStreams = findOldestWithStreamsOf(queue);
        if (sameStreams != null) {
            sameStreams.setTitle(title);
            sameStreams.setUrl(url);
        }
    }

    /**
     * Drops the top entry if there is one below it.
     *
     * @return the entry that is on top now, or {@code null} if there was nothing to go back to
     */
    @Nullable
    StackItem goBack() {
        if (items.size() <= 1) {
            return null;
        }
        items.pop();
        return items.peek();
    }

    @Nullable
    private StackItem findOldestWithStreamsOf(final PlayQueue queue) {
        final Iterator<StackItem> iterator = items.descendingIterator();
        while (iterator.hasNext()) {
            final StackItem next = iterator.next();
            if (next.getPlayQueue().equalStreams(queue)) {
                return next;
            }
        }
        return null;
    }
}
