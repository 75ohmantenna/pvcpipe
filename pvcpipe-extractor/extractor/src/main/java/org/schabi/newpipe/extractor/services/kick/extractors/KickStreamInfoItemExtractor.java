package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickUrl;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.List;

public final class KickStreamInfoItemExtractor implements StreamInfoItemExtractor {
    private final JsonObject stream;
    private final JsonObject channel;
    private final boolean live;

    public KickStreamInfoItemExtractor(final JsonObject stream, final JsonObject channel,
                                       final boolean live) {
        this.stream = stream;
        this.channel = channel;
        this.live = live;
    }

    @Override
    public String getName() {
        return KickApi.title(stream);
    }

    @Override
    public String getUrl() throws ParsingException {
        if (live) {
            return KickApi.liveUrl(channel, stream);
        }
        return KickUrl.BASE + "/" + KickUrl.slug(channel.getString("slug", ""))
                + "/videos/" + KickUrl.uuid(stream.getString("id", ""));
    }

    @Override
    public List<Image> getThumbnails() {
        return KickApi.thumbnails(stream);
    }

    @Override
    public StreamType getStreamType() {
        return live ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
    }

    @Override
    public boolean isAd() {
        return false;
    }

    @Override
    public long getDuration() {
        return live ? -1 : stream.getLong("duration", -1);
    }

    @Override
    public long getViewCount() {
        return live ? KickApi.viewers(stream)
                : stream.getLong("views", stream.getLong("viewer_count", -1));
    }

    @Override
    public String getUploaderName() {
        return KickApi.name(channel);
    }

    @Override
    public String getUploaderUrl() throws ParsingException {
        return KickUrl.BASE + "/" + KickUrl.slug(channel.getString("slug", "")) + "/videos";
    }

    @Override
    public List<Image> getUploaderAvatars() {
        return KickApi.images(KickApi.avatar(channel));
    }

    @Override
    public boolean isUploaderVerified() {
        return KickApi.verified(channel);
    }

    @Override
    public String getTextualUploadDate() {
        return stream.getString("start_time", stream.getString("created_at"));
    }

    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        return KickApi.date(getTextualUploadDate());
    }

    @Override
    public ContentAvailability getContentAvailability() {
        return KickApi.availability(stream);
    }
}
