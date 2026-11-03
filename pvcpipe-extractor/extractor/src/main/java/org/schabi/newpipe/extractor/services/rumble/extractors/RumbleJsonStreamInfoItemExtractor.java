package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.rumble.RumbleCreatorResolver;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.rumble.settings.RumbleSettings;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps API and embedded channel-listing video objects without eagerly parsing optional fields. */
final class RumbleJsonStreamInfoItemExtractor implements StreamInfoItemExtractor {
    private final JsonObject item;

    RumbleJsonStreamInfoItemExtractor(@Nonnull final JsonObject item) {
        this.item = item;
    }

    @Override
    public StreamType getStreamType() {
        return item.getBoolean("live") ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
    }

    @Override
    public boolean isAd() {
        return "premium".equals(item.getString("availability"))
                && ServiceList.Rumble.getServiceSettings()
                .isSettingEnabled(RumbleSettings.HIDE_PREMIUM_STREAMS);
    }

    @Override
    public long getDuration() throws ParsingException {
        return getStreamType() == StreamType.LIVE_STREAM ? -1 : number(item, "duration");
    }

    @Override
    public long getViewCount() throws ParsingException {
        return getStreamType() == StreamType.LIVE_STREAM && item.get("watching_now") != null
                ? number(item, "watching_now") : number(item, "views");
    }

    @Nullable
    private JsonObject uploader() throws ParsingException {
        return item.get("by") == null ? null : JsonUtils.getObject(item, "by");
    }

    @Override
    public String getUploaderName() throws ParsingException {
        return string(uploader(), "name");
    }

    @Override
    public String getUploaderUrl() throws ParsingException {
        final JsonObject by = uploader();
        if (by == null) {
            return null;
        }
        RumbleCreatorResolver.rememberCreator(by);
        final String url = string(by, "url");
        if (url != null) {
            return url;
        }
        return "https://rumble.com/" + ("channel".equals(by.getString("type")) ? "c/" : "user/")
                + Utils.encodeUrlUtf8(JsonUtils.getString(by, "slug")).replace("+", "%20");
    }

    @Override
    public boolean isUploaderVerified() throws ParsingException {
        final JsonObject by = uploader();
        return by != null && by.get("verified_badge") != null
                && JsonUtils.getBoolean(by, "verified_badge");
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() throws ParsingException {
        return images(string(uploader(), "thumb"));
    }

    @Override
    public String getTextualUploadDate() throws ParsingException {
        return string(item, "upload_date");
    }

    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        return DateWrapper.fromOffsetDateTime(getTextualUploadDate());
    }

    @Override
    public String getName() throws ParsingException {
        final String title = JsonUtils.getString(item, "title");
        if (title.trim().isEmpty()) {
            throw new ParsingException("Rumble video has an empty title");
        }
        return title;
    }

    @Override
    public String getUrl() throws ParsingException {
        final String url = JsonUtils.getString(item, "url");
        if (url.trim().isEmpty()) {
            throw new ParsingException("Rumble video has an empty URL");
        }
        final LinkHandler link = RumbleStreamLinkHandlerFactory.getInstance().fromUrl(url);
        if (link.getUrl().contains("/embed/")) {
            throw new ParsingException("Expected a public Rumble watch or Short URL");
        }
        RumbleMediaResolver.rememberVideo(item);
        return url;
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        return images(string(item, "thumb"));
    }

    @Override
    public boolean isShortFormContent() throws ParsingException {
        return item.get("is_short") != null && JsonUtils.getBoolean(item, "is_short");
    }

    @Nonnull
    @Override
    public ContentAvailability getContentAvailability() throws ParsingException {
        final String availability = string(item, "availability");
        if ("premium".equals(availability)) {
            return ContentAvailability.MEMBERSHIP;
        }
        return "public".equals(string(item, "visibility"))
                && (availability == null || availability.isEmpty())
                ? ContentAvailability.AVAILABLE : ContentAvailability.UNKNOWN;
    }

    @Nullable
    @Override
    public String getShortDescription() throws ParsingException {
        final String description = string(item, "description");
        return description != null ? description : string(item, "processed_description");
    }

    private static long number(final JsonObject object, final String key) throws ParsingException {
        return object.get(key) == null ? -1 : JsonUtils.getNumber(object, key).longValue();
    }

    @Nullable
    private static String string(@Nullable final JsonObject object, final String key)
            throws ParsingException {
        return object == null || object.get(key) == null ? null : JsonUtils.getString(object, key);
    }

    private static List<Image> images(@Nullable final String url) {
        return url == null || url.isEmpty() ? Collections.emptyList()
                : List.of(new Image(url, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN,
                        Image.ResolutionLevel.UNKNOWN));
    }
}
