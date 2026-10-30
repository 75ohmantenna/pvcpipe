package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickHls;
import org.schabi.newpipe.extractor.services.kick.KickUrl;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;

public final class KickStreamExtractor extends StreamExtractor {
    private JsonObject channel;
    private JsonObject stream;
    private KickHls.Manifest manifest;
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
        long recordingStart = 0;
        boolean ongoingRecording = false;
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
            if ("sub_only".equals(stream.getString("status"))) {
                throw new PaidContentException(
                        "This Kick recording requires a channel subscription");
            }
            channel = KickApi.child(stream, "channel");
            hls = stream.getString("recording_url", "");
            // The current video list also contains recordings of broadcasts still on air.
            vod = !stream.getBoolean("is_live");
            if (!vod) {
                ongoingRecording = true;
                try {
                    recordingStart = KickApi.startTime(stream);
                } catch (final ParsingException e) {
                    throw new ParsingException("Kick recording has no broadcast identity", e);
                }
            }
        }
        if (!vod) {
            if (channel == null) {
                channel = KickApi.channel(KickUrl.channelId(super.getUrl()));
            } else if (!channel.containsKey("livestream")) {
                // Legacy video metadata omits the channel's current livestream state.
                channel = KickApi.channel(channel.getString("slug", ""));
            }
            stream = channel.getObject("livestream");
            if (stream == null || stream.isEmpty() || !stream.getBoolean("is_live", true)) {
                throw new ContentNotAvailableException(ongoingRecording
                        ? "This Kick broadcast has ended" : "This Kick channel is offline");
            }
            final long requestedStart = ongoingRecording
                    ? recordingStart : KickUrl.liveTime(super.getUrl());
            if ((ongoingRecording || requestedStart != 0)
                    && requestedStart != KickApi.startTime(stream)) {
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
        manifest = KickHls.parse(hls, KickApi.manifest(hls));
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

    @Override
    public long getTimeStamp() throws ParsingException {
        return vod ? KickUrl.vodTime(getOriginalUrl()) : 0;
    }

    @Nonnull
    @Override
    public String getName() {
        return KickApi.title(stream);
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() {
        return KickApi.thumbnails(stream);
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
        return manifest.getAudioStreams();
    }

    @Override
    public List<VideoStream> getVideoStreams() {
        return manifest.getVideoStreams();
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() {
        return manifest.getVideoOnlyStreams();
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitlesDefault() {
        return manifest.getSubtitles();
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitles(final MediaFormat format) {
        return format == MediaFormat.VTT ? manifest.getSubtitles() : List.of();
    }

    @Override
    public String getCategory() {
        return KickApi.category(stream);
    }

    @Override
    public List<String> getTags() {
        return KickApi.tags(stream);
    }

    @Override
    public Locale getLanguageInfo() {
        return KickApi.language(stream);
    }

    @Override
    public ContentAvailability getContentAvailability() {
        return KickApi.availability(stream);
    }

    @Override
    public StreamInfoItemsCollector getRelatedItems() throws IOException, ExtractionException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        if (vod) {
            for (final Object value : KickApi.videos(channel)) {
                if (value instanceof JsonObject) {
                    final JsonObject video = (JsonObject) value;
                    if (KickApi.listable(video) && !video.getBoolean("is_live")
                            && !stream.getString("id", "").equalsIgnoreCase(
                                    video.getString("id", ""))) {
                        collector.commit(new KickStreamInfoItemExtractor(video, channel, false));
                    }
                }
            }
        } else {
            long categoryId = KickApi.child(stream, "category").getLong("id", 0);
            final JsonArray categories = stream.getArray("categories");
            if (categoryId <= 0 && categories != null) {
                for (final Object value : categories) {
                    if (value instanceof JsonObject && ((JsonObject) value).getLong("id", 0) > 0) {
                        categoryId = ((JsonObject) value).getLong("id");
                        break;
                    }
                }
            }
            final String language = getExtractorLocalization().getLocalizationCode()
                    .split("-", 2)[0].toLowerCase(Locale.ROOT);
            final JsonObject data = KickApi.livestreams(language, 24,
                    categoryId > 0 ? categoryId : null, null);
            for (final Object value : data.getArray("livestreams")) {
                if (value instanceof JsonObject) {
                    final JsonObject live = (JsonObject) value;
                    final JsonObject owner = KickApi.child(live, "channel");
                    if (KickApi.listable(live) && KickApi.listable(owner)
                            && live.getBoolean("is_live", true)
                            && !channel.getString("slug").equalsIgnoreCase(
                                    owner.getString("slug", ""))) {
                        collector.commit(new KickStreamInfoItemExtractor(live, owner, true));
                    }
                }
            }
        }
        return collector;
    }

    @Override
    public StreamType getStreamType() {
        return vod ? StreamType.VIDEO_STREAM : StreamType.LIVE_STREAM;
    }
}
