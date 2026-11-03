package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.rumble.RumbleCreatorResolver;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

/** Maps typed API creators, sharing identity validation with the creator resolver. */
final class RumbleJsonChannelInfoItemExtractor implements ChannelInfoItemExtractor {
    private final JsonObject item;

    RumbleJsonChannelInfoItemExtractor(final JsonObject item) {
        this.item = item;
    }

    @Override
    public String getUrl() throws ParsingException {
        RumbleCreatorResolver.rememberCreator(item);
        if (item.get("url") != null) {
            return JsonUtils.getString(item, "url");
        }
        return "https://rumble.com/" + ("channel".equals(item.getString("type")) ? "c/" : "user/")
                + Utils.encodeUrlUtf8(JsonUtils.getString(item, "slug")).replace("+", "%20");
    }

    @Override
    public String getName() throws ParsingException {
        final String title = JsonUtils.getString(item, "title");
        if (title.trim().isEmpty()) {
            throw new ParsingException("Rumble creator has an empty title");
        }
        return title;
    }

    @Override
    public String getDescription() throws ParsingException {
        return item.get("description") == null ? "" : JsonUtils.getString(item, "description");
    }

    @Override
    public long getSubscriberCount() throws ParsingException {
        return number("followers");
    }

    @Override
    public long getStreamCount() throws ParsingException {
        return number("videos");
    }

    @Override
    public boolean isVerified() throws ParsingException {
        return item.get("verified_badge") != null && JsonUtils.getBoolean(item, "verified_badge");
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        final String thumb = item.get("thumb") == null ? null : JsonUtils.getString(item, "thumb");
        return thumb == null || thumb.isEmpty() ? Collections.emptyList()
                : List.of(new Image(thumb, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN,
                        Image.ResolutionLevel.UNKNOWN));
    }

    private long number(final String key) throws ParsingException {
        return item.get(key) == null ? -1 : JsonUtils.getNumber(item, key).longValue();
    }
}
