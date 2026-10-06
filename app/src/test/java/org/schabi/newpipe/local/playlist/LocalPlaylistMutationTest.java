package org.schabi.newpipe.local.playlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO;
import org.schabi.newpipe.database.playlist.model.PlaylistEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.TestScheduler;

public class LocalPlaylistMutationTest {
    private final TestScheduler scheduler = new TestScheduler();
    private final AppDatabase database = mock(AppDatabase.class);
    private final PlaylistStreamDAO joins = mock(PlaylistStreamDAO.class);
    private final PlaylistDAO playlists = mock(PlaylistDAO.class);
    private final StreamDAO streams = mock(StreamDAO.class);
    private final List<List<Long>> writes = new ArrayList<>();
    private LocalPlaylistManager manager;
    private boolean inTransaction;
    private int maximum = -1;

    @Before
    public void setUp() {
        when(database.playlistStreamDAO()).thenReturn(joins);
        when(database.playlistDAO()).thenReturn(playlists);
        when(playlists.getPlaylist(anyLong())).thenReturn(Flowable.just(
                new ArrayList<>(List.of(new PlaylistEntity("playlist", false, 100L, 0L)))));
        when(database.streamDAO()).thenReturn(streams);
        manager = new LocalPlaylistManager(database, scheduler);
        when(database.runInTransaction(any(Callable.class))).thenAnswer(call -> {
            inTransaction = true;
            try {
                return ((Callable<?>) call.getArgument(0)).call();
            } finally {
                inTransaction = false;
            }
        });
        doAnswer(call -> {
            inTransaction = true;
            try {
                ((Runnable) call.getArgument(0)).run();
            } finally {
                inTransaction = false;
            }
            return null;
        }).when(database).runInTransaction(any(Runnable.class));
        when(joins.getMaximumIndexOfSync(anyLong())).thenAnswer(call -> {
            assertTrue(inTransaction);
            return maximum;
        });
        when(streams.upsertAll(anyList())).thenReturn(List.of(100L));
        when(joins.insertAll(any())).thenAnswer(call -> {
            assertTrue(inTransaction);
            final Collection<PlaylistStreamEntity> entities = call.getArgument(0);
            final List<Long> ids = new ArrayList<>();
            for (final PlaylistStreamEntity entity : entities) {
                maximum = Math.max(maximum, entity.getIndex());
                ids.add(entity.getStreamUid());
            }
            writes.add(ids);
            return List.of(1L);
        });
    }

    @Test
    public void acceptedAppendSurvivesObserverDisposalAndAllocatesInsideTransaction() {
        final var first = manager.appendToPlaylist(1, List.of(mock(StreamEntity.class))).test();
        first.dispose();
        final var second = manager.appendToPlaylist(1, List.of(mock(StreamEntity.class))).test();
        scheduler.triggerActions();
        second.assertComplete();
        assertEquals(2, writes.size());
        assertEquals(1, maximum);
    }

    @Test
    public void acceptedSnapshotsPersistInRequestOrderAfterViewDisposal() {
        final var first = manager.updateJoin(1, List.of(10L, 20L)).test();
        final var second = manager.updateJoin(1, List.of(20L, 10L)).test();
        first.dispose();
        second.dispose();
        scheduler.triggerActions();
        assertEquals(List.of(List.of(10L, 20L), List.of(20L, 10L)), writes);
    }
}
