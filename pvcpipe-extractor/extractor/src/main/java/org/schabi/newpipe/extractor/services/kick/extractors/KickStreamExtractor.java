package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickHls;
import org.schabi.newpipe.extractor.services.kick.KickUrl;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

public final class KickStreamExtractor extends StreamExtractor {
    private JsonObject channel;
    private JsonObject stream;
    private List<VideoStream> videoStreams = List.of();
    private String hls;
    private boolean vod;

    public KickStreamExtractor(final StreamingService service, final LinkHandler handler) {
        super(service, handler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        vod = KickUrl.isVod(getId());
        channel = null;
        if (vod) {
            stream = KickApi.video(getId());
            if (stream.isEmpty() || stream.getBoolean("is_pruned")
                    || stream.get("deleted_at") != null) {
                throw new ContentNotAvailableException(
                        "This Kick recording expired or was deleted");
            }
            if (stream.getBoolean("is_private") || "private".equals(stream.getString("status"))) {
                throw new PrivateContentException("This Kick recording is private");
            }
            channel = KickApi.child(stream, "channel");
            hls = stream.getString("recording_url", "");
            // The current video list also contains recordings of broadcasts still on air.
            vod = !stream.getBoolean("is_live");
        }
        if (!vod) {
            if (channel == null) {
                channel = KickApi.channel(KickUrl.channelId(super.getUrl()));
            } else if (!channel.containsKey("livestream")) {
                // Legacy video metadata embeds only a channel identity, not its live state.
                channel = KickApi.channel(channel.getString("slug", ""));
            }
            stream = channel.getObject("livestream");
            if (stream == null || stream.isEmpty() || !stream.getBoolean("is_live", true)) {
                throw new ContentNotAvailableException("This Kick channel is offline");
            }
            final long requestedStart = KickUrl.liveTime(super.getUrl());
            if (requestedStart != 0 && requestedStart != KickApi.startTime(stream)) {
                throw new ContentNotAvailableException("This Kick broadcast has ended");
            }
            hls = KickApi.object(KickUrl.BASE + "/api/v2/channels/"
                    + KickUrl.slug(channel.getString("slug")) + "/playback-url")
                    .getString("data", "");
        }
        if (channel.getBoolean("is_banned")) {
            throw new ContentNotAvailableException("This Kick channel is banned");
        }
        KickUrl.slug(channel.getString("slug", ""));
        if (!KickUrl.isHttps(hls)) {
            throw new ContentNotAvailableException(
                    "Kick has no playable HLS stream for this content");
        }
        videoStreams = KickHls.variants(hls, KickApi.manifest(hls));
    }

    @Nonnull
    @Override
    public String getUrl() throws ParsingException {
        if (channel == null) {
            return super.getUrl();
        }
        if (!vod) {
            return KickApi.liveUrl(channel, stream);
        }
        return KickUrl.BASE + "/" + KickUrl.slug(channel.getString("slug"))
                + "/videos/" + KickUrl.uuid(stream.getString("id", ""));
    }

    @Nonnull
    @Override
    public String getName() {
        return KickApi.title(stream);
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() {
        return KickApi.images(KickApi.image(stream, "thumbnail"));
    }

    @Nonnull
    @Override
    public Description getDescription() {
        return new Description(KickApi.biography(channel), Description.PLAIN_TEXT);
    }

    @Override
    public long getLength() {
        return vod ? stream.getLong("duration", 0) : 0;
    }

    @Override
    public long getViewCount() {
        return vod ? stream.getLong("views", stream.getLong("viewer_count", -1))
                : KickApi.viewers(stream);
    }

    @Override
    public int getAgeLimit() {
        return stream.getBoolean("is_mature") ? 18 : NO_AGE_LIMIT;
    }

    @Nonnull
    @Override
    public String getUploaderUrl() throws ParsingException {
        return KickUrl.BASE + "/" + KickUrl.slug(channel.getString("slug")) + "/videos";
    }

    @Nonnull
    @Override
    public String getUploaderName() {
        return KickApi.name(channel);
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() {
        return KickApi.images(KickApi.avatar(channel));
    }

    @Override
    public boolean isUploaderVerified() {
        return KickApi.verified(channel);
    }

    @Override
    public long getUploaderSubscriberCount() {
        return KickApi.followers(channel);
    }

    @Override
    public String getTextualUploadDate() {
        return stream.getString("start_time", stream.getString("created_at"));
    }

    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        return KickApi.date(getTextualUploadDate());
    }

    @Nonnull
    @Override
    public String getHlsUrl() {
        return hls;
    }

    @Override
    public List<AudioStream> getAudioStreams() {
        return List.of();
    }

    @Override
    public List<VideoStream> getVideoStreams() {
        return videoStreams;
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() {
        return List.of();
    }

    @Override
    public StreamType getStreamType() {
        return vod ? StreamType.VIDEO_STREAM : StreamType.LIVE_STREAM;
    }
}
