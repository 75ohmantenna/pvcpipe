package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickUrl;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.List;

/** A public clip owned by its broadcast channel, not the user who clipped it. */
public final class KickClipInfoItemExtractor implements StreamInfoItemExtractor {
    private final JsonObject clip;
    private final JsonObject channel;

    public KickClipInfoItemExtractor(final JsonObject clip, final JsonObject channel) {
        this.clip = clip;
        this.channel = channel;
    }

    @Override
    public String getName() {
        return KickApi.title(clip);
    }

    @Override
    public String getUrl() throws ParsingException {
        return KickUrl.clipUrl(channel.getString("slug", ""), clip.getString("id", ""));
    }

    @Override
    public List<Image> getThumbnails() {
        return KickApi.thumbnails(clip);
    }

    @Override
    public StreamType getStreamType() {
        return StreamType.VIDEO_STREAM;
    }

    @Override
    public boolean isAd() {
        return false;
    }

    @Override
    public long getDuration() {
        return clip.getLong("duration", -1);
    }

    @Override
    public long getViewCount() {
        return clip.getLong("views_count", clip.getLong("view_count", clip.getLong("views", -1)));
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
        return clip.getString("created_at");
    }

    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        return KickApi.date(getTextualUploadDate());
    }

    @Override
    public ContentAvailability getContentAvailability() {
        return KickApi.availability(clip);
    }
}
