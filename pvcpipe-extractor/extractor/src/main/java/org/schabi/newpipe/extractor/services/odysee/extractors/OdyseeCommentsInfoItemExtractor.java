package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemExtractor;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.services.odysee.OdyseeUrl;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.utils.Utils;

import java.time.Instant;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class OdyseeCommentsInfoItemExtractor implements CommentsInfoItemExtractor {

    private final JsonObject comment;
    private final String streamUrl;
    @Nullable
    private final JsonObject reactions;
    @Nullable
    private final JsonObject author;

    /**
     * @param comment   a comment as the comment service returns it
     * @param streamUrl the address of the video the comment is on
     * @param reactions the reaction counts of the comment, if they could be loaded
     * @param author    the channel claim of the commenter, if it could be loaded
     */
    public OdyseeCommentsInfoItemExtractor(@Nonnull final JsonObject comment,
                                           @Nonnull final String streamUrl,
                                           @Nullable final JsonObject reactions,
                                           @Nullable final JsonObject author) {
        this.comment = comment;
        this.streamUrl = streamUrl;
        this.reactions = reactions;
        this.author = author;
    }

    @Override
    public String getName() {
        return getUploaderName();
    }

    @Override
    public String getUrl() {
        return streamUrl;
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() {
        return getUploaderAvatars();
    }

    @Override
    public String getCommentId() {
        return comment.getString("comment_id", "");
    }

    @Nonnull
    @Override
    public Description getCommentText() {
        return new Description(comment.getString("comment", ""), Description.PLAIN_TEXT);
    }

    @Override
    public String getUploaderName() {
        return author == null ? comment.getString("channel_name", "")
                : OdyseeClaim.title(author);
    }

    @Override
    public String getUploaderUrl() {
        if (author != null) {
            return OdyseeClaim.webUrl(author);
        }
        final String channelUrl = comment.getString("channel_url");
        return Utils.isNullOrEmpty(channelUrl) ? "" : OdyseeUrl.lbryUriToWebUrl(channelUrl);
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() {
        return author == null ? List.of() : OdyseeClaim.avatars(author);
    }

    @Override
    public String getTextualUploadDate() {
        final DateWrapper date = getUploadDate();
        return date == null ? "" : date.getInstant().toString();
    }

    @Nullable
    @Override
    public DateWrapper getUploadDate() {
        final long seconds = comment.getLong("timestamp", 0);
        return seconds <= 0 ? null : new DateWrapper(Instant.ofEpochSecond(seconds));
    }

    @Override
    public int getLikeCount() {
        return reactions == null ? CommentsInfoItem.NO_LIKE_COUNT : reactions.getInt("like", 0);
    }

    @Override
    public String getTextualLikeCount() {
        return reactions == null ? "" : String.valueOf(reactions.getInt("like", 0));
    }

    @Override
    public boolean isHeartedByUploader() {
        return reactions != null && reactions.getInt("creator_like", 0) > 0;
    }

    @Override
    public boolean isPinned() {
        return comment.getBoolean("is_pinned");
    }

    @Override
    public boolean isChannelOwner() {
        return comment.getBoolean("is_creator");
    }

    @Override
    public int getReplyCount() {
        return comment.getInt("replies", 0);
    }

    @Nullable
    @Override
    public Page getReplies() {
        return getReplyCount() <= 0 ? null : new Page(streamUrl,
                OdyseeCommentsExtractor.REPLIES_PAGE_PREFIX + getCommentId() + ":1");
    }
}
