package org.schabi.newpipe.player.mediabrowser

import android.content.Context
import androidx.core.net.toUri
import io.reactivex.rxjava3.core.Single
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.local.playlist.RemotePlaylistManager
import org.schabi.newpipe.player.playqueue.ChannelTabPlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlaylistPlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.ChannelTabHelper
import org.schabi.newpipe.util.ExtractorHelper

/** Resolves persisted browser IDs; the service owns request cancellation and playback starts. */
class MediaBrowserPlaybackPreparer(context: Context) {
    private val database = NewPipeDatabase.getInstance(context)

    fun resolve(mediaId: String): Single<PlayQueue> = Single.defer { extractPlayQueueFromMediaId(mediaId) }

    //region Building play queues from playlists and history
    private fun extractLocalPlayQueue(
        mediaId: String,
        playlistId: Long,
        index: Int
    ): Single<PlayQueue> {
        return LocalPlaylistManager(database).getPlaylistStreams(playlistId).firstOrError()
            .map { items ->
                checkIndex(mediaId, index, items.size)
                SinglePlayQueue(items.map { it.toStreamInfoItem() }, index)
            }
    }

    private fun extractRemotePlayQueue(
        mediaId: String,
        playlistId: Long,
        index: Int
    ): Single<PlayQueue> {
        return RemotePlaylistManager(database).getPlaylist(playlistId).firstOrError()
            .flatMap { ExtractorHelper.getPlaylistInfo(it.serviceId, it.url, false) }
            // ignore info.errors, i.e. ignore errors about specific items, since there would
            // be no way to show the error properly in Android Auto anyway
            .map { info ->
                checkIndex(mediaId, index, info.relatedItems.size)
                PlaylistPlayQueue(info, index)
            }
    }

    private fun extractPlayQueueFromMediaId(mediaId: String): Single<PlayQueue> {
        val mediaIdUri = mediaId.toUri()
        val request = MediaIdRequest.parse(
            mediaIdUri.pathSegments,
            mediaIdUri.getQueryParameter(ID_URL)
        ) ?: throw parseError(mediaId)

        return when (request) {
            is MediaIdRequest.LocalPlaylist ->
                extractLocalPlayQueue(mediaId, request.playlistId, request.index)

            is MediaIdRequest.RemotePlaylist ->
                extractRemotePlayQueue(mediaId, request.playlistId, request.index)

            is MediaIdRequest.PlaylistUrl ->
                ExtractorHelper.getPlaylistInfo(request.serviceId, request.url, false)
                    .map { PlaylistPlayQueue(it) }

            is MediaIdRequest.History -> extractPlayQueueFromHistory(mediaId, request.streamId)

            is MediaIdRequest.Item -> extractPlayQueueFromInfoItem(mediaId, request)
        }
    }

    private fun extractPlayQueueFromHistory(mediaId: String, streamId: Long): Single<PlayQueue> {
        return database.streamHistoryDAO().history
            .firstOrError()
            .map { items ->
                val infoItems = items
                    .filter { it.streamId == streamId }
                    .map { it.toStreamInfoItem() }
                checkIndex(mediaId, 0, infoItems.size)
                SinglePlayQueue(infoItems, 0)
            }
    }

    private fun extractPlayQueueFromInfoItem(
        mediaId: String,
        request: MediaIdRequest.Item
    ): Single<PlayQueue> {
        val serviceId = request.serviceId
        return when (request.type) {
            InfoType.STREAM -> ExtractorHelper.getStreamInfo(serviceId, request.url, false)
                .map { SinglePlayQueue(it) }

            InfoType.PLAYLIST -> ExtractorHelper.getPlaylistInfo(serviceId, request.url, false)
                .map { PlaylistPlayQueue(it) }

            InfoType.CHANNEL -> ExtractorHelper.getChannelInfo(serviceId, request.url, false)
                .map { info ->
                    val playableTab = info.tabs
                        .firstOrNull { ChannelTabHelper.isStreamsTab(it) }
                        ?: throw ContentNotAvailableException("No streams tab found")
                    return@map ChannelTabPlayQueue(serviceId, ListLinkHandler(playableTab))
                }

            else -> throw parseError(mediaId)
        }
    }

    /** The item list may have changed since the media browser listed it. */
    private fun checkIndex(mediaId: String, index: Int, size: Int) {
        if (index !in 0 until size) {
            throw parseError(mediaId)
        }
    }
    //endregion
}
