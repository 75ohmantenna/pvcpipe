package org.schabi.newpipe.extractor.services.bitchute.extractor;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemExtractor;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteParserHelper;
import org.schabi.newpipe.extractor.stream.Description;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BitchuteCommentsInfoItemExtractor implements CommentsInfoItemExtractor {
    private final JsonObject json;
    private final String url;
    private final JsonArray comments;

    public BitchuteCommentsInfoItemExtractor(final JsonObject json, final String url) {
        this(json, url, new JsonArray());
    }

    public BitchuteCommentsInfoItemExtractor(final JsonObject json, final String url,
                                            final JsonArray comments) {
        this.json = json;
        this.url = url;
        this.comments = comments;
    }

    @Override
    public int getReplyCount() {
        int count = 0;
        for (final Object entry : comments) {
            if (getCommentId().equals(((JsonObject) entry).getString("parent"))) {
                count++;
            }
        }
        return count;
    }

    @Nullable
    @Override
    public Page getReplies() {
        return getReplyCount() == 0 ? null : new Page(url, getCommentId(),
                JsonWriter.string(comments).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getCommentId() {
        return json.getString("id");
    }

    @Override
    public Description getCommentText() {
        return new Description(json.getString("content"), Description.PLAIN_TEXT);
    }

    @Override
    public String getUploaderName() {
        return json.getString("fullname");
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() throws ParsingException {
        final String avatarUrl =
                BitchuteParserHelper.prependBaseUrl(json.getString("profile_picture_url"));
        if (avatarUrl.isEmpty()) {
            return List.of();
        }
        return List.of(new Image(avatarUrl,
                Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN));
    }

    @Override
    public String getUploaderUrl() {
        // return BitchuteConstants.BASE_URL + "/profile/" + json.getString("creator");
        // the */profile/* link is not linking to a channel but to a user that could have
        // a channel. --> therefore disabled for now
        return "";
    }

    @Override
    public String getTextualUploadDate() {
        return json.getString("created");
    }

    @Nullable
    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        final String date = getTextualUploadDate();
        if (date == null || date.isEmpty()) {
            return null;
        }
        try {
            return new DateWrapper(ZonedDateTime.parse(date.replace(' ', 'T'),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME).toOffsetDateTime(), false);
        } catch (final java.time.format.DateTimeParseException e) {
            throw new ParsingException("Could not parse BitChute comment date", e);
        }
    }

    @Override
    public String getName() throws ParsingException {
        return getUploaderName();
    }

    @Override
    public String getUrl() {
        return url;
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        return getUploaderAvatars();
    }

    @Override
    public String getTextualLikeCount() throws ParsingException {
        return json.getString("upvote_count");
    }

    @Override
    public int getLikeCount() throws ParsingException {
        return json.getInt("upvote_count");
    }
}
