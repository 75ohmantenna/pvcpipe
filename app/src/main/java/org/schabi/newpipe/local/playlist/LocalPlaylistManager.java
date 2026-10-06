package org.schabi.newpipe.local.playlist;

import androidx.annotation.Nullable;

import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry;
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO;
import org.schabi.newpipe.database.playlist.model.PlaylistEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class LocalPlaylistManager {
    private static final long THUMBNAIL_ID_LEAVE_UNCHANGED = -2;

    public enum Removal {
        DUPLICATES,
        WATCHED,
        WATCHED_AND_PARTIALLY_WATCHED
    }

    // Application-owned ordering for accepted playlist mutations, across manager instances.
    private static final Scheduler WRITES = Schedulers.from(
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "PlaylistWrites");
                thread.setDaemon(true);
                return thread;
            }));
    private final Scheduler writes;
    private final AppDatabase database;
    private final StreamDAO streamTable;
    private final PlaylistDAO playlistTable;
    private final PlaylistStreamDAO playlistStreamTable;

    public LocalPlaylistManager(final AppDatabase db) {
        this(db, WRITES);
    }

    LocalPlaylistManager(final AppDatabase db, final Scheduler writeScheduler) {
        writes = writeScheduler;
        database = db;
        streamTable = db.streamDAO();
        playlistTable = db.playlistDAO();
        playlistStreamTable = db.playlistStreamDAO();
    }

    public Maybe<List<Long>> createPlaylist(final String name, final List<StreamEntity> streams) {
        // Disallow creation of empty playlists
        if (streams.isEmpty()) {
            return Maybe.empty();
        }

        // Save to the database directly.
        // Make sure the new playlist is always on the top of bookmark.
        // The index will be reassigned to non-negative number in BookmarkFragment.
        return Maybe.fromCallable(() -> database.runInTransaction(() -> {
                    final List<Long> streamIds = streamTable.upsertAll(streams);
                    final PlaylistEntity newPlaylist = new PlaylistEntity(name, false,
                            streamIds.get(0), -1);

                    return insertJoinEntities(playlistTable.insert(newPlaylist),
                            streamIds, 0);
                }
        )).subscribeOn(Schedulers.io());
    }

    public Maybe<List<Long>> appendToPlaylist(final long playlistId,
                                              final List<StreamEntity> streams) {
        final Maybe<List<Long>> saved = Maybe.fromCallable(() -> database.runInTransaction(() -> {
            final int maxJoinIndex = playlistStreamTable.getMaximumIndexOfSync(playlistId);
            final List<Long> streamIds = streamTable.upsertAll(streams);
            return insertJoinEntities(playlistId, streamIds, maxJoinIndex + 1);
        })).subscribeOn(writes).cache();
        return saved.flatMap(joinIds -> playlistTable.getPlaylist(playlistId).firstElement()
                .flatMap(playlists -> {
                    if (!playlists.isEmpty() && playlists.get(0).getThumbnailStreamId()
                            == PlaylistEntity.DEFAULT_THUMBNAIL_ID) {
                        return changePlaylistThumbnail(playlistId, streams.get(0).getUid(), false)
                                .map(ignored -> joinIds);
                    }
                    return Maybe.just(joinIds);
                }));
    }

    private List<Long> insertJoinEntities(final long playlistId, final List<Long> streamIds,
                                          final int indexOffset) {

        final List<PlaylistStreamEntity> joinEntities = new ArrayList<>(streamIds.size());

        for (int index = 0; index < streamIds.size(); index++) {
            joinEntities.add(new PlaylistStreamEntity(playlistId, streamIds.get(index),
                    index + indexOffset));
        }
        return playlistStreamTable.insertAll(joinEntities);
    }

    public Completable updateJoin(final long playlistId, final List<Long> streamIds) {
        final List<PlaylistStreamEntity> joinEntities = new ArrayList<>(streamIds.size());
        for (int i = 0; i < streamIds.size(); i++) {
            joinEntities.add(new PlaylistStreamEntity(playlistId, streamIds.get(i), i));
        }

        final Completable saved = Completable.fromRunnable(() -> database.runInTransaction(() -> {
            playlistStreamTable.deleteBatch(playlistId);
            playlistStreamTable.insertAll(joinEntities);
        })).subscribeOn(writes).cache();
        return saved.andThen(Completable.defer(
                () -> refreshAutomaticThumbnail(playlistId, streamIds)));
    }

    private Completable refreshAutomaticThumbnail(final long playlistId,
                                                   final List<Long> streamIds) {
        return playlistTable.getPlaylist(playlistId).firstElement()
                .flatMap(playlists -> {
                    if (playlists.isEmpty()) {
                        return Maybe.<Integer>empty();
                    }
                    final PlaylistEntity playlist = playlists.get(0);
                    if (playlist.isThumbnailPermanent()
                            || streamIds.contains(playlist.getThumbnailStreamId())) {
                        return Maybe.just(0);
                    }
                    final long thumbnailId = streamIds.isEmpty()
                            ? PlaylistEntity.DEFAULT_THUMBNAIL_ID : streamIds.get(0);
                    return changePlaylistThumbnail(playlistId, thumbnailId, false);
                }).ignoreElement().subscribeOn(Schedulers.io());
    }

    /**
     * Remove selected streams and return the saved ordered contents.
     *
     * @param playlistId the playlist whose contents are changed
     * @param removal the rule used to select streams for removal
     * @return the saved ordered contents
     */
    public Maybe<List<PlaylistStreamEntry>> removeStreams(final long playlistId,
                                                         final Removal removal) {
        final Maybe<List<PlaylistStreamEntry>> selected;
        if (removal == Removal.DUPLICATES) {
            selected = getDistinctPlaylistStreams(playlistId).firstElement();
        } else {
            final var historyIds = database.streamHistoryDAO().getHistorySortedById()
                    .firstElement()
                    .map(history -> history.stream().map(StreamHistoryEntry::getStreamId)
                            .collect(Collectors.toList()));
            selected = getPlaylistStreams(playlistId).firstElement()
                    .zipWith(historyIds, (playlist, watchedIds) -> {
                        final List<PlaylistStreamEntry> kept = new ArrayList<>();
                        for (final PlaylistStreamEntry item : playlist) {
                            final List<StreamStateEntity> states = database.streamStateDAO()
                                    .getState(item.getStreamId()).blockingFirst();
                            if (Collections.binarySearch(watchedIds, item.getStreamId()) < 0
                                    || states.isEmpty()
                                    || (removal == Removal.WATCHED && !states.get(0)
                                            .isFinished(item.toStreamInfoItem().getDuration()))) {
                                kept.add(item);
                            }
                        }
                        return kept;
                    });
        }
        return selected.subscribeOn(Schedulers.io()).flatMap(items -> {
            final List<Long> ids = items.stream().map(PlaylistStreamEntry::getStreamId)
                    .collect(Collectors.toList());
            return updateJoin(playlistId, ids)
                    .andThen(getPlaylistStreams(playlistId).firstElement());
        });
    }

    public Completable updatePlaylists(final List<PlaylistMetadataEntry> updateItems,
                                       final List<Long> deletedItems) {
        final List<PlaylistEntity> items = new ArrayList<>(updateItems.size());
        for (final PlaylistMetadataEntry item : updateItems) {
            items.add(new PlaylistEntity(item));
        }
        return Completable.fromRunnable(() -> database.runInTransaction(() -> {
            for (final Long uid : deletedItems) {
                playlistTable.deletePlaylist(uid);
            }
            for (final PlaylistEntity item : items) {
                playlistTable.upsertPlaylist(item);
            }
        })).subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistStreamEntry>> getDistinctPlaylistStreams(final long playlistId) {
        return playlistStreamTable
                .getStreamsWithoutDuplicates(playlistId).subscribeOn(Schedulers.io());
    }

    /**
     * Get playlists with attached information about how many times the provided stream is already
     * contained in each playlist.
     *
     * @param streamUrl the stream url for which to check for duplicates
     * @return a list of {@link PlaylistDuplicatesEntry}
     */
    public Flowable<List<PlaylistDuplicatesEntry>> getPlaylistDuplicates(final String streamUrl) {
        return playlistStreamTable.getPlaylistDuplicatesMetadata(streamUrl)
                .subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistMetadataEntry>> getPlaylists() {
        return playlistStreamTable.getPlaylistMetadata().subscribeOn(Schedulers.io());
    }

    public Flowable<List<PlaylistStreamEntry>> getPlaylistStreams(final long playlistId) {
        return playlistStreamTable.getOrderedStreamsOf(playlistId).subscribeOn(Schedulers.io());
    }

    public Maybe<Integer> renamePlaylist(final long playlistId, final String name) {
        return modifyPlaylist(playlistId, name, THUMBNAIL_ID_LEAVE_UNCHANGED, false);
    }

    public Maybe<Integer> changePlaylistThumbnail(final long playlistId,
                                                  final long thumbnailStreamId,
                                                  final boolean isPermanent) {
        return modifyPlaylist(playlistId, null, thumbnailStreamId, isPermanent);
    }

    public long getPlaylistThumbnailStreamId(final long playlistId) {
        return playlistTable.getPlaylist(playlistId).blockingFirst().get(0).getThumbnailStreamId();
    }

    public boolean getIsPlaylistThumbnailPermanent(final long playlistId) {
        return playlistTable.getPlaylist(playlistId).blockingFirst().get(0)
                .isThumbnailPermanent();
    }

    public long getAutomaticPlaylistThumbnailStreamId(final long playlistId) {
        final long streamId = playlistStreamTable.getAutomaticThumbnailStreamId(playlistId)
                .blockingFirst();
        if (streamId < 0) {
            return PlaylistEntity.DEFAULT_THUMBNAIL_ID;
        }
        return streamId;
    }

    private Maybe<Integer> modifyPlaylist(final long playlistId,
                                          @Nullable final String name,
                                          final long thumbnailStreamId,
                                          final boolean isPermanent) {
        return playlistTable.getPlaylist(playlistId)
                .firstElement()
                .filter(playlistEntities -> !playlistEntities.isEmpty())
                .map(playlistEntities -> {
                    final PlaylistEntity playlist = playlistEntities.get(0);
                    if (name != null) {
                        playlist.setName(name);
                    }
                    if (thumbnailStreamId != THUMBNAIL_ID_LEAVE_UNCHANGED) {
                        playlist.setThumbnailStreamId(thumbnailStreamId);
                        playlist.setThumbnailPermanent(isPermanent);
                    }
                    return playlistTable.update(playlist);
                }).subscribeOn(Schedulers.io());
    }

    public Maybe<Boolean> hasPlaylists() {
        return playlistTable.getCount()
                .firstElement()
                .map(count -> count > 0)
                .subscribeOn(Schedulers.io());
    }
}
