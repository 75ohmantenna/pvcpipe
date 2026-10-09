package org.schabi.newpipe.local.playlist

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity

open class LocalPlaylistManager internal constructor(
    private val database: AppDatabase,
    private val writes: Scheduler
) {
    constructor(db: AppDatabase) : this(db, WRITES)

    enum class Removal {
        DUPLICATES,
        WATCHED,
        WATCHED_AND_PARTIALLY_WATCHED
    }

    private val streamTable = database.streamDAO()
    private val playlistTable = database.playlistDAO()
    private val playlistStreamTable = database.playlistStreamDAO()

    open fun createPlaylist(name: String?, streams: List<StreamEntity>): Maybe<List<Long>> {
        // Creation retains its original deferred, uncached IO operation and live input list.
        if (streams.isEmpty()) return Maybe.empty()
        return Maybe.fromCallable {
            database.runInTransaction(
                Callable {
                    val streamIds = streamTable.upsertAll(streams)
                    val playlist = PlaylistEntity(
                        name = name,
                        isThumbnailPermanent = false,
                        thumbnailStreamId = streamIds[0],
                        displayIndex = -1
                    )
                    insertJoinEntities(playlistTable.insert(playlist), streamIds, 0)
                }
            )
        }.subscribeOn(Schedulers.io())
    }

    open fun appendToPlaylist(playlistId: Long, streams: List<StreamEntity>): Maybe<List<Long>> {
        // upsertAll mutates its entities: freeze both the list and every entity at request creation.
        val snapshot = streams.mapTo(ArrayList(streams.size)) { it.copy() }
        return Maybe.fromCallable {
            database.runInTransaction(
                Callable {
                    requirePlaylist(playlistId)
                    val maximum = playlistStreamTable.getMaximumIndexOfSync(playlistId)
                    val streamIds = streamTable.upsertAll(snapshot)
                    val inserted = insertJoinEntities(playlistId, streamIds, maximum + 1)
                    refreshAutomaticThumbnailSync(playlistId)
                    inserted
                }
            )
        }.subscribeOn(writes).cache()
    }

    private fun insertJoinEntities(playlistId: Long, streamIds: List<Long>, indexOffset: Int): List<Long> {
        val joins = ArrayList<PlaylistStreamEntity>(streamIds.size)
        for (index in streamIds.indices) {
            joins.add(PlaylistStreamEntity(playlistId, streamIds[index], index + indexOffset))
        }
        return playlistStreamTable.insertAll(joins)
    }

    open fun updateJoin(playlistId: Long, streamIds: List<Long>): Completable {
        val snapshot = ArrayList(streamIds)
        return Completable.fromRunnable {
            database.runInTransaction(
                Runnable {
                    requirePlaylist(playlistId)
                    replaceContents(playlistId, snapshot)
                    refreshAutomaticThumbnailSync(playlistId)
                }
            )
        }.subscribeOn(writes).cache()
    }

    private fun requirePlaylist(playlistId: Long) {
        if (playlistTable.getPlaylistSync(playlistId) == null) {
            throw IllegalArgumentException("Playlist does not exist: $playlistId")
        }
    }

    private fun replaceContents(playlistId: Long, streamIds: List<Long>) {
        playlistStreamTable.deleteBatch(playlistId)
        insertJoinEntities(playlistId, streamIds, 0)
        // Reject deferred foreign-key failures inside the transaction, while rollback is reliable.
        if (playlistStreamTable.getOrderedStreamsOfSync(playlistId).size != streamIds.size) {
            throw IllegalArgumentException("Playlist contents contain unknown streams")
        }
    }

    private fun refreshAutomaticThumbnailSync(playlistId: Long) {
        val playlist = playlistTable.getPlaylistSync(playlistId) ?: return
        if (playlist.isThumbnailPermanent) return
        val streams = playlistStreamTable.getOrderedStreamsOfSync(playlistId)
        if (streams.any { it.streamId == playlist.thumbnailStreamId }) return
        val thumbnailId = streams.firstOrNull()?.streamId ?: PlaylistEntity.DEFAULT_THUMBNAIL_ID
        if (playlist.thumbnailStreamId != thumbnailId) {
            playlist.thumbnailStreamId = thumbnailId
            playlistTable.update(playlist)
        }
    }

    open fun removeStreams(playlistId: Long, removal: Removal?): Maybe<List<PlaylistStreamEntry>> = removeStreams(playlistId, removal, null)

    /** Null pending contents select stored rows; an empty list explicitly clears them. */
    open fun removeStreams(
        playlistId: Long,
        removal: Removal?,
        pendingStreamIds: List<Long>?
    ): Maybe<List<PlaylistStreamEntry>> {
        val snapshot = pendingStreamIds?.let { ArrayList(it) }
        return Maybe.fromCallable<List<PlaylistStreamEntry>> {
            database.runInTransaction(
                Callable {
                    requirePlaylist(playlistId)
                    if (snapshot != null) replaceContents(playlistId, snapshot)
                    val streams = playlistStreamTable.getOrderedStreamsOfSync(playlistId)
                    val kept = ArrayList<Long>(streams.size)
                    val seen = HashSet<Long>()
                    for (stream in streams) {
                        val streamId = stream.streamId
                        when (removal) {
                            Removal.DUPLICATES -> if (seen.add(streamId)) kept.add(streamId)

                            else -> {
                                val state = database.streamStateDAO().getStateSync(streamId)
                                if (database.streamHistoryDAO().getLatestEntry(streamId) == null ||
                                    state == null ||
                                    (removal == Removal.WATCHED && !state.isFinished(stream.streamEntity.duration))
                                ) {
                                    kept.add(streamId)
                                }
                            }
                        }
                    }
                    replaceContents(playlistId, kept)
                    refreshAutomaticThumbnailSync(playlistId)
                    playlistStreamTable.getOrderedStreamsOfSync(playlistId)
                }
            )
        }.subscribeOn(writes).cache()
    }

    @JvmSuppressWildcards
    open fun updatePlaylists(updateItems: List<PlaylistMetadataEntry>, deletedItems: List<Long>): Completable {
        val items = updateItems.mapTo(ArrayList(updateItems.size)) { PlaylistEntity(it) }
        val deleted = ArrayList(deletedItems)
        return Completable.fromRunnable {
            database.runInTransaction(
                Runnable {
                    for (uid in deleted) playlistTable.deletePlaylist(uid)
                    for (item in items) {
                        val current = playlistTable.getPlaylistSync(item.uid)
                        if (current != null) {
                            current.displayIndex = item.displayIndex
                            playlistTable.update(current)
                        }
                    }
                }
            )
        }.subscribeOn(writes).cache()
    }

    @Suppress("UNCHECKED_CAST")
    open fun getDistinctPlaylistStreams(playlistId: Long): Flowable<List<PlaylistStreamEntry>> = playlistStreamTable
        .getStreamsWithoutDuplicates(playlistId).subscribeOn(Schedulers.io()) as Flowable<List<PlaylistStreamEntry>>

    @Suppress("UNCHECKED_CAST")
    open fun getPlaylistDuplicates(streamUrl: String): Flowable<List<PlaylistDuplicatesEntry>> = playlistStreamTable
        .getPlaylistDuplicatesMetadata(streamUrl).subscribeOn(Schedulers.io()) as Flowable<List<PlaylistDuplicatesEntry>>

    @get:Suppress("UNCHECKED_CAST")
    open val playlists: Flowable<List<PlaylistMetadataEntry>>
        get() = playlistStreamTable.getPlaylistMetadata()
            .subscribeOn(Schedulers.io()) as Flowable<List<PlaylistMetadataEntry>>

    @Suppress("UNCHECKED_CAST")
    open fun getPlaylistStreams(playlistId: Long): Flowable<List<PlaylistStreamEntry>> = playlistStreamTable
        .getOrderedStreamsOf(playlistId).subscribeOn(Schedulers.io()) as Flowable<List<PlaylistStreamEntry>>

    open fun renamePlaylist(playlistId: Long, name: String?): Maybe<Int> = modifyPlaylist(
        playlistId,
        name,
        THUMBNAIL_ID_LEAVE_UNCHANGED,
        false
    )

    open fun changePlaylistThumbnail(playlistId: Long, thumbnailStreamId: Long, isPermanent: Boolean): Maybe<Int> = modifyPlaylist(
        playlistId,
        null,
        thumbnailStreamId,
        isPermanent
    )

    open fun getPlaylistThumbnailStreamId(playlistId: Long): Long = playlistTable
        .getPlaylist(playlistId).blockingFirst()[0].thumbnailStreamId

    open fun getIsPlaylistThumbnailPermanent(playlistId: Long): Boolean = playlistTable
        .getPlaylist(playlistId).blockingFirst()[0].isThumbnailPermanent

    open fun getAutomaticPlaylistThumbnailStreamId(playlistId: Long): Long {
        val streamId = playlistStreamTable.getAutomaticThumbnailStreamId(playlistId).blockingFirst()
        return if (streamId < 0) PlaylistEntity.DEFAULT_THUMBNAIL_ID else streamId
    }

    private fun modifyPlaylist(
        playlistId: Long,
        name: String?,
        thumbnailStreamId: Long,
        isPermanent: Boolean
    ): Maybe<Int> = Maybe.fromCallable {
        database.runInTransaction(
            Callable {
                val playlist = playlistTable.getPlaylistSync(playlistId) ?: return@Callable null
                if (name != null) playlist.name = name
                if (thumbnailStreamId != THUMBNAIL_ID_LEAVE_UNCHANGED) {
                    playlist.thumbnailStreamId = thumbnailStreamId
                    playlist.isThumbnailPermanent = isPermanent
                }
                playlistTable.update(playlist)
            }
        )
    }.subscribeOn(writes).cache()

    open fun hasPlaylists(): Maybe<Boolean> = playlistTable.count.firstElement()
        .map { it > 0 }
        .subscribeOn(Schedulers.io())

    private companion object {
        private val THUMBNAIL_ID_LEAVE_UNCHANGED = -2L

        // Application-owned ordering for accepted playlist mutations, across manager instances.
        val WRITES: Scheduler = Schedulers.from(
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PlaylistWrites").apply { isDaemon = true }
            }
        )
    }
}
