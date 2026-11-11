package org.schabi.newpipe.local.feed.service

import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Post-store notification inputs, without the already-persisted channel payload. */
data class FeedNotificationInfo(
    val name: String,
    val avatarUrl: String?,
    val url: String,
    val serviceId: Int,
    val newStreams: List<StreamInfoItem>
) {
    val pseudoId: Int
        get() = url.hashCode()
}
