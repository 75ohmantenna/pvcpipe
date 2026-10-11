package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;

public class DetailStackTest {
    private final DetailStack stack = new DetailStack();
    private final PlayQueue first = queue();
    private final PlayQueue second = queue();

    @Test
    public void startsEmptyWithNothingToGoBackTo() {
        assertTrue(stack.isEmpty());
        assertNull(stack.goBack());
    }

    @Test
    public void loadedStreamIsAddedOnceForTheSameStreams() {
        final PlayQueue sameAsFirst = sameStreamsAs(first);

        stack.onStreamLoaded(0, "url 1", "title 1", first);
        stack.onStreamLoaded(0, "url 1 again", "title 1 again", sameAsFirst);

        assertFalse(stack.isEmpty());
        // Only one entry, so there is nothing below it.
        assertNull(stack.goBack());
    }

    @Test
    public void backReturnsTheEntryBelowAndKeepsTheLastOne() {
        stack.onStreamLoaded(0, "url 1", "title 1", first);
        stack.onStreamLoaded(1, "url 2", "title 2", second);

        final StackItem previous = stack.goBack();

        assertEquals("url 1", previous.getUrl());
        assertEquals("title 1", previous.getTitle());
        assertSame(first, previous.getPlayQueue());
        assertNull(stack.goBack());
        assertFalse(stack.isEmpty());
    }

    @Test
    public void playerQueueWithOtherStreamsBecomesAnEntryForItsCurrentItem() {
        stack.onStreamLoaded(0, "url 1", "title 1", first);
        final PlayQueueItem current = mock(PlayQueueItem.class);
        when(current.getServiceId()).thenReturn(3);
        when(current.getUrl()).thenReturn("played url");
        when(current.getTitle()).thenReturn("played title");
        when(second.getItem()).thenReturn(current);

        stack.onQueueUpdate(second);
        // The same queue reported again adds nothing.
        stack.onQueueUpdate(second);

        final StackItem previous = stack.goBack();
        assertEquals("url 1", previous.getUrl());
        assertNull(stack.goBack());
    }

    @Test
    public void playerQueueWithoutCurrentItemAddsNoEntry() {
        stack.onStreamLoaded(0, "url 1", "title 1", first);
        when(second.getItem()).thenReturn(null);

        stack.onQueueUpdate(second);

        assertNull(stack.goBack());
    }

    @Test
    public void playerQueueWithTheSameStreamsReplacesTheStoredQueue() {
        final PlayQueue liveFirst = sameStreamsAs(first);
        stack.onStreamLoaded(0, "url 1", "title 1", first);
        stack.onStreamLoaded(1, "url 2", "title 2", second);
        stack.goBack();

        stack.onQueueUpdate(liveFirst);

        stack.onStreamLoaded(1, "url 2", "title 2", second);
        assertSame(liveFirst, stack.goBack().getPlayQueue());
    }

    @Test
    public void playingStreamRenamesTheEntryWithTheSameStreams() {
        final PlayQueue liveFirst = sameStreamsAs(first);
        stack.onStreamLoaded(0, "url 1", "title 1", first);
        stack.onStreamLoaded(1, "url 2", "title 2", second);

        stack.onMetadataUpdate(liveFirst, "next title", "next url");
        // A queue that matches no entry changes nothing.
        stack.onMetadataUpdate(queue(), "other title", "other url");

        final StackItem previous = stack.goBack();
        assertEquals("next title", previous.getTitle());
        assertEquals("next url", previous.getUrl());
    }

    @Test
    public void playerQueueOnAnEmptyStackAddsNothing() {
        stack.onQueueUpdate(first);

        assertTrue(stack.isEmpty());
    }

    @Test
    public void clearForgetsEveryEntry() {
        stack.onStreamLoaded(0, "url 1", "title 1", first);
        stack.onStreamLoaded(1, "url 2", "title 2", second);

        stack.clear();

        assertTrue(stack.isEmpty());
        assertNull(stack.goBack());
    }

    private static PlayQueue queue() {
        final PlayQueue queue = mock(PlayQueue.class);
        when(queue.equalStreams(queue)).thenReturn(true);
        return queue;
    }

    private static PlayQueue sameStreamsAs(final PlayQueue other) {
        final PlayQueue queue = queue();
        when(queue.equalStreams(other)).thenReturn(true);
        when(other.equalStreams(queue)).thenReturn(true);
        return queue;
    }
}
