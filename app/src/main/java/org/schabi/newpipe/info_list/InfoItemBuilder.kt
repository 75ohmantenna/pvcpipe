/*
 * SPDX-FileCopyrightText: 2016-2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.info_list

import android.content.Context
import java.util.function.Consumer
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.OnClickGesture

class InfoItemBuilder(val context: Context) {
    var onStreamSelectedListener: OnClickGesture<StreamInfoItem>? = null
    var onChannelSelectedListener: OnClickGesture<ChannelInfoItem>? = null
    var onPlaylistSelectedListener: OnClickGesture<PlaylistInfoItem>? = null
    var onCommentsSelectedListener: OnClickGesture<CommentsInfoItem>? = null

    /** Called with a comment when the user asks for its replies. */
    var onCommentRepliesSelectedListener: Consumer<CommentsInfoItem>? = null
}
