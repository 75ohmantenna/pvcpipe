package org.schabi.newpipe.player.mediabrowser

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaLibraryService.LibraryParams
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.function.Consumer
import org.schabi.newpipe.MainActivity.DEBUG
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.history.model.StreamHistoryEntry
import org.schabi.newpipe.database.playlist.PlaylistLocalItem
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.bookmark.MergedPlaylistManager
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.local.playlist.RemotePlaylistManager
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * This class is used to cleanly separate the Service implementation (in
 * [org.schabi.newpipe.player.PlayerService]) and the media browser implementation (in this file).
 *
 * @param notifyChildrenChanged takes the parent id of the children that changed
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaBrowserImpl(
    private val context: Context,
    // parentId
    notifyChildrenChanged: Consumer<String>
) {
    private val database = NewPipeDatabase.getInstance(context)
    private var disposables = CompositeDisposable()

    init {
        // this will listen to changes in the bookmarks until this MediaBrowserImpl is dispose()d
        disposables.add(
            getMergedPlaylists().subscribe { notifyChildrenChanged.accept(ID_BOOKMARKS) }
        )
    }

    //region Cleanup
    fun dispose() {
        disposables.dispose()
    }
    //endregion

    fun root(params: LibraryParams?): MediaItem? {
        if (params?.isRecent == true) return null
        return BrowserItemBuilder().setMediaId(ID_ROOT)
            .setTitle(context.getString(R.string.app_name)).build(true)
    }

    fun loadChildren(parentId: String): Single<List<MediaItem>> = Single.defer { loadChildrenRequest(parentId) }

    private fun loadChildrenRequest(parentId: String): Single<List<MediaItem>> {
        try {
            val parentIdUri = parentId.toUri()
            val path = ArrayList(parentIdUri.pathSegments)

            if (path.isEmpty()) {
                return Single.just(
                    listOf(
                        createRootMediaItem(
                            ID_BOOKMARKS,
                            context.resources.getString(R.string.tab_bookmarks_short),
                            R.drawable.ic_bookmark_white
                        ),
                        createRootMediaItem(
                            ID_HISTORY,
                            context.resources.getString(R.string.action_history),
                            R.drawable.ic_history_white
                        )
                    )
                )
            }

            when (path.removeAt(0)) {
                ID_BOOKMARKS -> {
                    if (path.isEmpty()) {
                        return populateBookmarks()
                    }
                    if (path.size == 2) {
                        val localOrRemote = path[0]
                        val playlistId = path[1].toLong()
                        if (localOrRemote == ID_LOCAL) {
                            return populateLocalPlaylist(playlistId)
                        } else if (localOrRemote == ID_REMOTE) {
                            return populateRemotePlaylist(playlistId)
                        }
                    }
                    Log.w(TAG, "Unknown playlist URI: $parentId")
                    throw parseError(parentId)
                }

                ID_HISTORY -> return populateHistory()

                else -> throw parseError(parentId)
            }
        } catch (e: ContentNotAvailableException) {
            return Single.error(e)
        }
    }

    private fun createRootMediaItem(
        mediaId: String?,
        folderName: String?,
        @DrawableRes iconResId: Int
    ): MediaItem {
        val builder = BrowserItemBuilder()
        builder.setMediaId(mediaId)
        builder.setTitle(folderName)
        val resources = context.resources
        builder.setIconUri(
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(resources.getResourcePackageName(iconResId))
                .appendPath(resources.getResourceTypeName(iconResId))
                .appendPath(resources.getResourceEntryName(iconResId))
                .build()
        )

        val extras = Bundle()
        extras.putString(
            "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT",
            context.getString(R.string.app_name)
        )
        builder.setExtras(extras)
        return builder.build(true)
    }

    private fun createPlaylistMediaItem(playlist: PlaylistLocalItem): MediaItem {
        val builder = BrowserItemBuilder()
        builder
            .setMediaId(createMediaIdForInfoItem(playlist is PlaylistRemoteEntity, playlist.uid))
            .setTitle(playlist.orderingName)
            .setIconUri(imageUriOrNullIfDisabled(playlist.thumbnailUrl))

        val extras = Bundle()
        extras.putString(
            "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT",
            context.resources.getString(R.string.tab_bookmarks)
        )
        builder.setExtras(extras)
        return builder.build(true)
    }

    private fun createInfoItemMediaItem(item: InfoItem): MediaItem? {
        val builder = BrowserItemBuilder()
        builder.setMediaId(createMediaIdForInfoItem(item))
            .setTitle(item.name)

        when (item.infoType) {
            InfoType.STREAM -> builder.setSubtitle((item as StreamInfoItem).uploaderName)
            InfoType.PLAYLIST -> builder.setSubtitle((item as PlaylistInfoItem).uploaderName)
            InfoType.CHANNEL -> builder.setSubtitle((item as ChannelInfoItem).description)
            else -> return null
        }

        ImageStrategy.choosePreferredImage(item.thumbnails)?.let {
            builder.setIconUri(imageUriOrNullIfDisabled(it))
        }

        return builder.build(false)
    }

    private fun buildMediaId(): Uri.Builder {
        return Uri.Builder().authority(ID_AUTHORITY)
    }

    private fun buildPlaylistMediaId(playlistType: String?): Uri.Builder {
        return buildMediaId()
            .appendPath(ID_BOOKMARKS)
            .appendPath(playlistType)
    }

    private fun buildLocalPlaylistItemMediaId(isRemote: Boolean, playlistId: Long): Uri.Builder {
        return buildPlaylistMediaId(if (isRemote) ID_REMOTE else ID_LOCAL)
            .appendPath(playlistId.toString())
    }

    private fun buildInfoItemMediaId(item: InfoItem): Uri.Builder {
        return buildMediaId()
            .appendPath(ID_INFO_ITEM)
            .appendPath(infoItemTypeToString(item.infoType))
            .appendPath(item.serviceId.toString())
            .appendQueryParameter(ID_URL, item.url)
    }

    private fun createMediaIdForInfoItem(isRemote: Boolean, playlistId: Long): String {
        return buildLocalPlaylistItemMediaId(isRemote, playlistId)
            .build().toString()
    }

    private fun createLocalPlaylistStreamMediaItem(
        playlistId: Long,
        item: PlaylistStreamEntry,
        index: Int
    ): MediaItem {
        val builder = BrowserItemBuilder()
        builder.setMediaId(createMediaIdForPlaylistIndex(false, playlistId, index))
            .setTitle(item.streamEntity.title)
            .setSubtitle(item.streamEntity.uploader)
            .setIconUri(imageUriOrNullIfDisabled(item.streamEntity.thumbnailUrl))

        return builder.build(false)
    }

    private fun createRemotePlaylistStreamMediaItem(
        playlistId: Long,
        item: StreamInfoItem,
        index: Int
    ): MediaItem {
        val builder = BrowserItemBuilder()
        builder.setMediaId(createMediaIdForPlaylistIndex(true, playlistId, index))
            .setTitle(item.name)
            .setSubtitle(item.uploaderName)

        ImageStrategy.choosePreferredImage(item.thumbnails)?.let {
            builder.setIconUri(imageUriOrNullIfDisabled(it))
        }

        return builder.build(false)
    }

    private fun createMediaIdForPlaylistIndex(
        isRemote: Boolean,
        playlistId: Long,
        index: Int
    ): String {
        return buildLocalPlaylistItemMediaId(isRemote, playlistId)
            .appendPath(index.toString())
            .build().toString()
    }

    private fun createMediaIdForInfoItem(item: InfoItem): String {
        return buildInfoItemMediaId(item).build().toString()
    }

    private fun populateHistory(): Single<List<MediaItem>> {
        val history = database.streamHistoryDAO().history.firstOrError()
        return history.map { items ->
            items.map { this.createHistoryMediaItem(it) }
        }
    }

    private fun createHistoryMediaItem(streamHistoryEntry: StreamHistoryEntry): MediaItem {
        val builder = BrowserItemBuilder()
        val mediaId = buildMediaId()
            .appendPath(ID_HISTORY)
            .appendPath(streamHistoryEntry.streamId.toString())
            .build().toString()
        builder.setMediaId(mediaId)
            .setTitle(streamHistoryEntry.streamEntity.title)
            .setSubtitle(streamHistoryEntry.streamEntity.uploader)
            .setIconUri(imageUriOrNullIfDisabled(streamHistoryEntry.streamEntity.thumbnailUrl))

        return builder.build(false)
    }

    private fun getMergedPlaylists(): Flowable<MutableList<PlaylistLocalItem>> {
        return MergedPlaylistManager.getMergedOrderedPlaylists(
            LocalPlaylistManager(database),
            RemotePlaylistManager(database)
        )
    }

    private fun populateBookmarks(): Single<List<MediaItem>> {
        val playlists = getMergedPlaylists().firstOrError()
        return playlists.map { playlist ->
            playlist.map { this.createPlaylistMediaItem(it) }
        }
    }

    private fun populateLocalPlaylist(playlistId: Long): Single<List<MediaItem>> {
        val playlist = LocalPlaylistManager(database).getPlaylistStreams(playlistId).firstOrError()
        return playlist.map { items ->
            items.mapIndexed { index, item ->
                createLocalPlaylistStreamMediaItem(playlistId, item, index)
            }
        }
    }

    private fun populateRemotePlaylist(playlistId: Long): Single<List<MediaItem>> {
        return RemotePlaylistManager(database).getPlaylist(playlistId).firstOrError()
            .flatMap { ExtractorHelper.getPlaylistInfo(it.serviceId, it.url, false) }
            .map {
                // ignore it.errors, i.e. ignore errors about specific items, since there would
                // be no way to show the error properly in Android Auto anyway
                it.relatedItems.mapIndexed { index, item ->
                    createRemotePlaylistStreamMediaItem(playlistId, item, index)
                }
            }
    }
    //endregion

    //region Search
    fun search(query: String): Single<List<MediaItem>> = searchMusicBySongTitle(query)
        .map { it.relatedItems.mapNotNull(this::createInfoItemMediaItem) }
        .subscribeOn(Schedulers.io())

    private fun searchMusicBySongTitle(query: String?): Single<SearchInfo> {
        val serviceId = ServiceHelper.getSelectedServiceId(context)
        return ExtractorHelper.searchFor(serviceId, query, listOf(), listOf())
    }
    //endregion

    companion object {
        private val TAG: String = MediaBrowserImpl::class.java.getSimpleName()

        fun imageUriOrNullIfDisabled(url: String?): Uri? {
            return if (ImageStrategy.shouldLoadImages()) {
                url?.toUri()
            } else {
                null
            }
        }
    }
}

/** Keeps catalog IDs separate from stream URLs and represents browse/play flags as metadata. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class BrowserItemBuilder {
    private val item = MediaItem.Builder()
    private val metadata = MediaMetadata.Builder()

    fun setMediaId(id: String?) = apply { item.setMediaId(requireNotNull(id)) }
    fun setTitle(value: CharSequence?) = apply { metadata.setTitle(value) }
    fun setSubtitle(value: CharSequence?) = apply { metadata.setSubtitle(value).setArtist(value) }
    fun setIconUri(uri: Uri?) = apply { metadata.setArtworkUri(uri) }
    fun setExtras(extras: Bundle) = apply { metadata.setExtras(extras) }
    fun build(browsable: Boolean): MediaItem = item.setMediaMetadata(
        metadata.setIsBrowsable(browsable).setIsPlayable(!browsable)
            .setMediaType(if (browsable) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_MIXED)
            .build()
    ).build()
}
