package org.schabi.newpipe.player.mediabrowser

import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.ServiceList

/**
 * A playback request decoded from a media ID that [MediaBrowserImpl] created. Any connected
 * media controller can send an ID, so every part is checked before it is used.
 */
internal sealed interface MediaIdRequest {
    data class LocalPlaylist(val playlistId: Long, val index: Int) : MediaIdRequest
    data class RemotePlaylist(val playlistId: Long, val index: Int) : MediaIdRequest
    data class PlaylistUrl(val serviceId: Int, val url: String) : MediaIdRequest
    data class History(val streamId: Long) : MediaIdRequest
    data class Item(val type: InfoType, val serviceId: Int, val url: String) : MediaIdRequest

    companion object {
        /**
         * @param path the path segments of the media ID
         * @param url the value of its [ID_URL] query parameter, if any
         * @return the request, or `null` for an ID that [MediaBrowserImpl] does not create
         */
        fun parse(path: List<String>, url: String?): MediaIdRequest? {
            return when (path.firstOrNull()) {
                ID_BOOKMARKS -> parsePlaylist(path.drop(1), url)
                ID_HISTORY -> if (path.size == 2) id(path[1])?.let(::History) else null
                ID_INFO_ITEM -> parseItem(path.drop(1), url)
                else -> null
            }
        }

        private fun parsePlaylist(path: List<String>, url: String?): MediaIdRequest? {
            return when (path.firstOrNull()) {
                ID_LOCAL, ID_REMOTE -> {
                    if (path.size != 3) return null
                    val playlistId = id(path[1]) ?: return null
                    val index = index(path[2]) ?: return null
                    if (path[0] == ID_LOCAL) {
                        LocalPlaylist(playlistId, index)
                    } else {
                        RemotePlaylist(playlistId, index)
                    }
                }

                ID_URL -> {
                    if (path.size != 2 || url.isNullOrBlank()) return null
                    service(path[1])?.let { PlaylistUrl(it, url) }
                }

                else -> null
            }
        }

        private fun parseItem(path: List<String>, url: String?): MediaIdRequest? {
            if (path.size != 2 || url.isNullOrBlank()) return null
            val type = infoItemTypeFromString(path[0]) ?: return null
            return service(path[1])?.let { Item(type, it, url) }
        }

        // Database ids and queue positions are never negative; toLongOrNull rejects overflow.
        private fun id(value: String) = value.toLongOrNull()?.takeIf { it >= 0 }

        private fun index(value: String) = value.toIntOrNull()?.takeIf { it >= 0 }

        private fun service(value: String) = value.toIntOrNull()
            ?.takeIf { id -> ServiceList.all().any { it.serviceId == id } }
    }
}
