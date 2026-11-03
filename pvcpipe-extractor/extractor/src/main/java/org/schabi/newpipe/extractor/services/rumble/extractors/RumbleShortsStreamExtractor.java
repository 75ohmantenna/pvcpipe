package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.rumble.RumbleApi;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.stream.Stream.ID_UNKNOWN;

@SuppressWarnings({"checkstyle:FinalLocalVariable", "checkstyle:FinalParameters"})
public class RumbleShortsStreamExtractor extends StreamExtractor {

    private JsonArray relatedItems;
    private JsonObject currentVideo;
    private JsonArray videos;
    private JsonObject uploader;
    private long numericId;
    private String publicUrl;
    private StreamInfoItemsCollector relatedCollector;
    private List<SubtitlesStream> subtitles = Collections.emptyList();

    RumbleShortsStreamExtractor(StreamingService service, LinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull Downloader downloader)
            throws IOException, ExtractionException {
        final RumbleMediaResolver.ResolvedMedia media = RumbleMediaResolver.resolve(
                downloader, getLinkHandler(), getExtractorLocalization());
        currentVideo = media.getDetails();
        numericId = media.getNumericId();
        publicUrl = media.getPublicUrl();
        if (currentVideo == null || currentVideo.get("videos") == null) {
            throw new ContentNotAvailableException("Rumble Short has no playable videos");
        }
        final JsonArray sources = JsonUtils.getArray(currentVideo, "videos");
        videos = new JsonArray();
        for (final Object source : sources) {
            if (!(source instanceof JsonObject)) {
                throw new ParsingException("Invalid Rumble Short video source");
            }
            final JsonObject video = (JsonObject) source;
            final String url = video.getString("url");
            if (url != null && !url.isEmpty()) {
                videos.add(video);
            }
        }
        if (videos.isEmpty()) {
            throw new ContentNotAvailableException("Rumble Short has no playable videos");
        }
        uploader = currentVideo.get("by") instanceof JsonObject
                ? currentVideo.getObject("by") : null;
        subtitles = RumbleSubtitles.extract(null, currentVideo);
        relatedItems = null;
        relatedCollector = null;
    }

    @Nonnull
    @Override
    public String getUrl() throws ParsingException {
        return publicUrl == null ? super.getUrl() : publicUrl;
    }

    @Override
    public StreamType getStreamType() {
        return currentVideo.getBoolean("live", false)
                ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return currentVideo.getString("title", "");
    }

    @Nullable
    @Override
    public String getTextualUploadDate() {
        return getStreamType() == StreamType.LIVE_STREAM
                ? null : currentVideo.getString("upload_date");
    }

    @Nullable
    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        return DateWrapper.fromOffsetDateTime(getTextualUploadDate());
    }

    @Override
    public int getAgeLimit() {
        return currentVideo.getBoolean("is_age_restricted", false) ? 18 : NO_AGE_LIMIT;
    }

    @Nonnull
    @Override
    public Description getDescription() throws ParsingException {
        String desc = currentVideo.getString("description");
        if (desc == null) {
            desc = currentVideo.getString("processed_description", "");
        }
        return new Description(desc, Description.PLAIN_TEXT);
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        assertPageFetched();
        return images(currentVideo.getString("thumb"));
    }

    @Nonnull
    @Override
    public String getUploaderName() throws ParsingException {
        return uploader == null ? "" : uploader.getString("name", "");
    }

    @Nonnull
    @Override
    public String getUploaderUrl() throws ParsingException {
        return uploader == null ? "" : uploader.getString("url", "");
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() {
        return images(uploader == null ? null : uploader.getString("thumb"));
    }

    @Override
    public boolean isUploaderVerified() {
        return uploader != null && uploader.getBoolean("verified_badge", false);
    }

    @Override
    public long getUploaderSubscriberCount() {
        return uploader == null ? UNKNOWN_SUBSCRIBER_COUNT
                : uploader.getLong("followers", UNKNOWN_SUBSCRIBER_COUNT);
    }

    private static List<Image> images(@Nullable final String url) {
        return url == null || url.isEmpty() ? Collections.emptyList()
                : List.of(new Image(url, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN,
                        Image.ResolutionLevel.UNKNOWN));
    }

    @Override
    public long getLength() throws ParsingException {
        return currentVideo.getLong("duration", -1);
    }

    @Override
    public long getViewCount() throws ParsingException {
        return getStreamType() == StreamType.LIVE_STREAM
                ? currentVideo.getLong("watching_now", -1) : currentVideo.getLong("views", -1);
    }

    @Override
    public long getLikeCount() {
        return getVotes("num_votes_up");
    }

    @Override
    public long getDislikeCount() {
        return getVotes("num_votes_down");
    }

    private long getVotes(final String field) {
        final JsonObject votes = currentVideo.getObject("rumble_votes");
        return votes == null ? -1 : votes.getLong(field, -1);
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitlesDefault() {
        return subtitles;
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitles(final MediaFormat format) {
        return format == MediaFormat.VTT ? subtitles : Collections.emptyList();
    }

    @Override
    public List<VideoStream> getVideoStreams() throws ExtractionException {

        List<VideoStream> streamList = new ArrayList<>();

        for (Object obj : videos) {
            JsonObject video = (JsonObject) obj;

            String url = video.getString("url");
            int bitrateKbps = video.getInt("bitrate_kbps");
            String resolution = String.format(Locale.ROOT, "%dp@%dk", video.getInt("res"),
                    bitrateKbps
            );

            VideoStream videoStream = new VideoStream.Builder()
                    .setId(ID_UNKNOWN)
                    .setIsVideoOnly(false)
                    .setResolution(resolution)
                    .setContent(url, true)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .build();

            videoStream.pvcSetBitrate(bitrateKbps * 1000);
            streamList.add(videoStream);
        }

        return streamList;
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() throws IOException, ExtractionException {
        return Collections.emptyList();
    }

    @Override
    public List<AudioStream> getAudioStreams() {
        return Collections.emptyList();
    }

    @Override
    public StreamInfoItemsCollector getRelatedItems() throws ExtractionException {
        if (relatedCollector != null) {
            return relatedCollector;
        }
        if (relatedItems == null) {
            final JsonObject data;
            try {
                data = RumbleApi.fetchData(getDownloader(), RumbleApi.serviceUrl("shorts.feed",
                        Map.of("api", "7", "offset", "0", "limit", "10",
                                "options", "video.full,video.related_video")),
                        getExtractorLocalization());
            } catch (final IOException | ReCaptchaException e) {
                throw new ParsingException("Could not fetch related Rumble Shorts", e);
            }
            relatedItems = JsonUtils.getArray(data, "items");
        }
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        final Set<Long> seenIds = new HashSet<>();
        for (final Object obj : relatedItems) {
            if (!(obj instanceof JsonObject)) {
                throw new ParsingException("Invalid related Rumble Short item");
            }
            final JsonObject item = (JsonObject) obj;
            if (!"video".equals(item.getString("object_type"))
                    || !item.getBoolean("is_short", false)) {
                continue;
            }
            final Number id = JsonUtils.getNumber(item, "id");
            final long itemId = id.longValue();
            if (itemId <= 0 || id.doubleValue() >= 0x1p63
                    || id.doubleValue() != itemId) {
                throw new ParsingException("Invalid related Rumble Short numeric ID");
            }
            if (itemId != numericId && seenIds.add(itemId)) {
                collector.commit(new RumbleJsonStreamInfoItemExtractor(item));
            }
        }
        relatedCollector = collector;
        return relatedCollector;
    }

}
