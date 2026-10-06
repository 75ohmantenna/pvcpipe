package org.schabi.newpipe.local.playlist;

import androidx.annotation.Nullable;

import org.schabi.newpipe.database.AppDatabase;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

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
        final List<StreamEntity> snapshot = copyStreams(streams);
        return Maybe.fromCallable(() -> database.runInTransaction(() -> {
            requirePlaylist(playlistId);
            final int maximum = playlistStreamTable.getMaximumIndexOfSync(playlistId);
            final List<Long> streamIds = streamTable.upsertAll(snapshot);
            final List<Long> inserted = insertJoinEntities(playlistId, streamIds, maximum + 1);
            refreshAutomaticThumbnailSync(playlistId);
            return inserted;
        })).subscribeOn(writes).cache();
    }

    private static List<StreamEntity> copyStreams(final List<StreamEntity> streams) {
        final List<StreamEntity> snapshot = new ArrayList<>(streams.size());
        for (final StreamEntity stream : streams) {
            snapshot.add(stream.copy(stream.getUid(), stream.getServiceId(), stream.getUrl(),
                    stream.getTitle(), stream.getStreamType(), stream.getDuration(),
                    stream.getUploader(), stream.getUploaderUrl(), stream.getThumbnailUrl(),
                    stream.getViewCount(), stream.getTextualUploadDate(), stream.getUploadDate(),
                    stream.isUploadDateApproximation()));
        }
        return snapshot;
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
        final List<Long> snapshot = new ArrayList<>(streamIds);
        return Completable.fromRunnable(() -> database.runInTransaction(() -> {
            requirePlaylist(playlistId);
            replaceContents(playlistId, snapshot);
            refreshAutomaticThumbnailSync(playlistId);
        })).subscribeOn(writes).cache();
    }

    private void requirePlaylist(final long playlistId) {
        if (playlistTable.getPlaylistSync(playlistId) == null) {
            throw new IllegalArgumentException("Playlist does not exist: " + playlistId);
        }
    }

    private void replaceContents(final long playlistId, final List<Long> streamIds) {
        playlistStreamTable.deleteBatch(playlistId);
        insertJoinEntities(playlistId, streamIds, 0);
        // Reject deferred foreign-key failures before commit, while rollback is still reliable.
        if (playlistStreamTable.getOrderedStreamsOfSync(playlistId).size() != streamIds.size()) {
            throw new IllegalArgumentException("Playlist contents contain unknown streams");
        }
    }

    private void refreshAutomaticThumbnailSync(final long playlistId) {
        final PlaylistEntity playlist = playlistTable.getPlaylistSync(playlistId);
        if (playlist == null || playlist.isThumbnailPermanent()) {
            return;
        }
        final List<PlaylistStreamEntry> streams =
                playlistStreamTable.getOrderedStreamsOfSync(playlistId);
        for (final PlaylistStreamEntry stream : streams) {
            if (stream.getStreamId() == playlist.getThumbnailStreamId()) {
                return;
            }
        }
        final long thumbnailId = streams.isEmpty() ? PlaylistEntity.DEFAULT_THUMBNAIL_ID
                : streams.get(0).getStreamId();
        if (playlist.getThumbnailStreamId() != thumbnailId) {
            playlist.setThumbnailStreamId(thumbnailId);
            playlistTable.update(playlist);
        }
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
        return removeStreams(playlistId, removal, null);
    }

    /**
     * Apply removal to stored contents or a supplied pending snapshot in one transaction.
     *
     * @param playlistId the playlist whose contents are changed
     * @param removal the rule used to select streams for removal
     * @param pendingStreamIds replacement contents before removal; null uses stored contents,
     *                         while an empty list clears the playlist
     * @return the saved ordered contents
     * @throws IllegalArgumentException if the playlist or a pending stream does not exist
     */
    public Maybe<List<PlaylistStreamEntry>> removeStreams(
            final long playlistId,
            final Removal removal,
            @Nullable final List<Long> pendingStreamIds) {
        final List<Long> snapshot = pendingStreamIds == null
                ? null : new ArrayList<>(pendingStreamIds);
        return Maybe.fromCallable(() -> database.runInTransaction(() -> {
            requirePlaylist(playlistId);
            if (snapshot != null) {
                replaceContents(playlistId, snapshot);
            }
            final List<PlaylistStreamEntry> streams =
                    playlistStreamTable.getOrderedStreamsOfSync(playlistId);
            final List<Long> kept = new ArrayList<>(streams.size());
            final Set<Long> seen = new HashSet<>();
            for (final PlaylistStreamEntry stream : streams) {
                if (removal == Removal.DUPLICATES) {
                    if (seen.add(stream.getStreamId())) {
                        kept.add(stream.getStreamId());
                    }
                } else {
                    final StreamStateEntity state = database.streamStateDAO()
                            .getStateSync(stream.getStreamId());
                    if (database.streamHistoryDAO().getLatestEntry(stream.getStreamId()) == null
                            || state == null || (removal == Removal.WATCHED
                                    && !state.isFinished(stream.getStreamEntity().getDuration()))) {
                        kept.add(stream.getStreamId());
                    }
                }
            }
            replaceContents(playlistId, kept);
            refreshAutomaticThumbnailSync(playlistId);
            return playlistStreamTable.getOrderedStreamsOfSync(playlistId);
        })).subscribeOn(writes).cache();
    }

    public Completable updatePlaylists(final List<PlaylistMetadataEntry> updateItems,
                                       final List<Long> deletedItems) {
        final List<PlaylistEntity> items = new ArrayList<>(updateItems.size());
        for (final PlaylistMetadataEntry item : updateItems) {
            items.add(new PlaylistEntity(item));
        }
        final List<Long> deleted = new ArrayList<>(deletedItems);
        return Completable.fromRunnable(() -> database.runInTransaction(() -> {
            for (final Long uid : deleted) {
                playlistTable.deletePlaylist(uid);
            }
            for (final PlaylistEntity item : items) {
                final PlaylistEntity current = playlistTable.getPlaylistSync(item.getUid());
                if (current != null) {
                    current.setDisplayIndex(item.getDisplayIndex());
                    playlistTable.update(current);
                }
            }
        })).subscribeOn(writes).cache();
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
        return Maybe.fromCallable(() -> database.runInTransaction(() -> {
            final PlaylistEntity playlist = playlistTable.getPlaylistSync(playlistId);
            if (playlist == null) {
                return null;
            }
            if (name != null) {
                playlist.setName(name);
            }
            if (thumbnailStreamId != THUMBNAIL_ID_LEAVE_UNCHANGED) {
                playlist.setThumbnailStreamId(thumbnailStreamId);
                playlist.setThumbnailPermanent(isPermanent);
            }
            return playlistTable.update(playlist);
        })).subscribeOn(writes).cache();
    }

    public Maybe<Boolean> hasPlaylists() {
        return playlistTable.getCount()
                .firstElement()
                .map(count -> count > 0)
                .subscribeOn(Schedulers.io());
    }
}
