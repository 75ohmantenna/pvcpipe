package org.schabi.newpipe.extractor.services.bitchute.extractor;

import com.github.pvcpipe.json2java4nanojson.bitchute.api.results.stream.video.ResultsStreamVideo;
import com.github.pvcpipe.json2java4nanojson.bitchute.api.results.stream.video.counts.ResultsStreamVideoCounts;
import com.github.pvcpipe.json2java4nanojson.bitchute.api.results.stream.video.media.ResultsStreamVideoMedia;
import com.github.pvcpipe.json2java4nanojson.bitchute.api.results.stream.videos.ResultsStreamVideos;
import com.github.pvcpipe.json2java4nanojson.bitchute.api.results.stream.videos.Videos;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.MetaInfo;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.pvc.misc.PvcParsingHelper;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteParserHelper;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.stream.Stream.ID_UNKNOWN;

public class BitchuteStreamExtractor extends StreamExtractor {

    private ResultsStreamVideo streamVideoResults = null;
    private ResultsStreamVideoMedia streamVideoMediaResults = null;

    private ResultsStreamVideoCounts streamVideoViewCounts = null;

    private ResultsStreamVideos streamVideosSuggested;
    private ParsingException countsError;
    private ExtractionException suggestedError;

    public BitchuteStreamExtractor(final StreamingService service, final LinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        streamVideoResults = callApiAndGetResultsStreamVideo();
        streamVideoMediaResults = callApiAndGetResultsStreamVideoMedia();
        // Counts and recommendations must not prevent an available video from playing.
        countsError = null;
        suggestedError = null;
        try {
            streamVideoViewCounts = callApiAndGetResultsStreamVideoCounts();
        } catch (final IOException | ExtractionException e) {
            countsError = new ParsingException("Could not load BitChute video counts", e);
        }
        try {
            streamVideosSuggested = callApiAndGetResultsStreamVideos();
        } catch (final IOException | ExtractionException e) {
            suggestedError = new ExtractionException("Could not load BitChute suggestions", e);
        }
    }

    private ResultsStreamVideos callApiAndGetResultsStreamVideos()
            throws ExtractionException, IOException {
        final JsonObject streamVideoResultsJson = BitchuteParserHelper.callJsonApi(
                JsonObject.builder()
                        .value("selection", "suggested")
                        .value("offset", 1)
                        .value("limit", 20)
                        .value("advertisable", true),
                ResultsStreamVideos.ENDPOINT
        );
        return new ResultsStreamVideos(streamVideoResultsJson);
    }

    private ResultsStreamVideoCounts callApiAndGetResultsStreamVideoCounts()
            throws ExtractionException, IOException {
        final JsonObject streamVideoResultsJson = BitchuteParserHelper.callJsonApi(
                JsonObject.builder().value("video_id", getId()),
                ResultsStreamVideoCounts.ENDPOINT
        );

        return new ResultsStreamVideoCounts(streamVideoResultsJson);
    }

    private ResultsStreamVideoMedia callApiAndGetResultsStreamVideoMedia()
            throws ExtractionException, IOException {
        final JsonObject streamVideoResultsJson = BitchuteParserHelper.callJsonApi(
                JsonObject.builder().value("video_id", getId()),
                ResultsStreamVideoMedia.ENDPOINT
        );
        return new ResultsStreamVideoMedia(streamVideoResultsJson);
    }

    ResultsStreamVideo callApiAndGetResultsStreamVideo()
            throws ExtractionException, IOException {
        final JsonObject streamVideoResultsJson = BitchuteParserHelper.callJsonApi(
                JsonObject.builder().value("video_id", getId()),
                ResultsStreamVideo.ENDPOINT
        );
        return new ResultsStreamVideo(streamVideoResultsJson);
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return streamVideoResults.getVideoName();
    }

    @Nullable
    @Override
    public String getTextualUploadDate() throws ParsingException {
        return streamVideoResults.getDatePublished();
    }

    @Nullable
    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        if (getTextualUploadDate() == null || getTextualUploadDate().isEmpty()) {
            return null;
        }
        return new DateWrapper(PvcParsingHelper.parseDateFrom(getTextualUploadDate()));
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        return List.of(new Image(streamVideoResults.getThumbnailUrl(),
                Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN));
    }

    @Nonnull
    @Override
    public Description getDescription() throws ParsingException {
        return new Description(streamVideoResults.getDescription(), Description.HTML);
    }

    @Override
    public int getAgeLimit() throws ParsingException {
        switch (streamVideoResults.getSensitivityId()) {
            case "safe":
                return StreamExtractor.NO_AGE_LIMIT;
            case "normal":
            default:
                return 12;
            case "nsfw":
                return 15;
            case "nsfl":
                return 18;
        }
    }

    @Override
    public long getLength() throws ParsingException {
        return YoutubeParsingHelper.parseDurationString(streamVideoResults.getDuration());
    }

    @Override
    public long getTimeStamp() {
        return 0;
    }

    @Override
    public long getViewCount() throws ParsingException {
        if (countsError != null) {
            throw countsError;
        }
        return streamVideoViewCounts.getViewCount();
    }

    @Override
    public long getLikeCount() throws ParsingException {
        if (countsError != null) {
            throw countsError;
        }
        return streamVideoViewCounts.getLikeCount();
    }

    @Override
    public long getDislikeCount() throws ParsingException {
        if (countsError != null) {
            throw countsError;
        }
        return streamVideoViewCounts.getDislikeCount();
    }

    @Nullable
    @Override
    public StreamInfoItemsCollector getRelatedItems() throws ExtractionException {
        if (suggestedError != null) {
            throw suggestedError;
        }
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        for (final Videos video : streamVideosSuggested.getVideos()) {
            collector.commit(new BitchuteVideoInfoItemExtractor(video));
        }
        return collector;
    }

    @Nonnull
    @Override
    public String getUploaderUrl() throws ParsingException {
        return BitchuteParserHelper.prependBaseUrl(streamVideoResults.getChannel().getChannelUrl());
    }

    @Nonnull
    @Override
    public String getUploaderName() throws ParsingException {
        return streamVideoResults.getChannel().getChannelName();
    }

    @Override
    public boolean isUploaderVerified() throws ParsingException {
        return false; // The video details response does not expose uploader verification.
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() throws ParsingException {
        return List.of(new Image(streamVideoResults.getChannel().getThumbnailUrl(),
                Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN));
    }

    @Nonnull
    @Override
    public String getSubChannelUrl() {
        return ""; // No sub-channel URL is extracted from video details.
    }

    @Nonnull
    @Override
    public String getSubChannelName() {
        return ""; // No sub-channel name is extracted from video details.
    }

    @Nonnull
    @Override
    public String getDashMpdUrl() {
        return "";
    }

    @Nonnull
    @Override
    public String getHlsUrl() {
        return isHlsMedia(streamVideoMediaResults) ? streamVideoMediaResults.getMediaUrl() : "";
    }

    @Override
    public List<AudioStream> getAudioStreams() {
        return Collections.emptyList();
    }

    @Override
    public List<VideoStream> getVideoStreams() throws ExtractionException {
        try {
            return Collections.singletonList(buildVideoStream(streamVideoMediaResults));
        } catch (final Exception e) {
            throw new ParsingException("Could not parse BitChute media URL", e);
        }
    }

    static VideoStream buildVideoStream(final ResultsStreamVideoMedia media)
            throws ParsingException {
        final String videoUrl = media.getMediaUrl();
        final VideoStream.Builder builder = new VideoStream.Builder()
                .setId(ID_UNKNOWN)
                .setIsVideoOnly(false)
                .setResolution(VideoStream.RESOLUTION_UNKNOWN)
                .setContent(videoUrl, true)
                .setMediaFormat(detectMediaFormat(media));

        if (isHlsMedia(media)) {
            builder.setDeliveryMethod(DeliveryMethod.HLS)
                    .setManifestUrl(videoUrl);
        }
        return builder.build();
    }

    private static boolean isHlsMedia(final ResultsStreamVideoMedia media) {
        final String path = getMediaPath(media.getMediaUrl());
        return "application/x-mpegURL".equalsIgnoreCase(media.getMediaType())
                || "application/vnd.apple.mpegurl".equalsIgnoreCase(media.getMediaType())
                || path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    @Nullable
    private static MediaFormat detectMediaFormat(final ResultsStreamVideoMedia media) {
        final String mediaType = media.getMediaType();
        if (mediaType != null) {
            final int parametersStart = mediaType.indexOf(';');
            final String bareMediaType = (parametersStart < 0
                    ? mediaType : mediaType.substring(0, parametersStart)).trim();
            final MediaFormat mimeFormat = MediaFormat.getFromMimeType(
                    bareMediaType.toLowerCase(Locale.ROOT));
            if (isVideoFormat(mimeFormat)) {
                return mimeFormat;
            }
        }

        final String path = getMediaPath(media.getMediaUrl()).toLowerCase(Locale.ROOT);
        final int extensionStart = path.lastIndexOf('.');
        if (extensionStart >= 0) {
            final MediaFormat suffixFormat = MediaFormat.getFromSuffix(
                    path.substring(extensionStart + 1));
            if (isVideoFormat(suffixFormat)) {
                return suffixFormat;
            }
        }
        return null;
    }

    private static String getMediaPath(final String mediaUrl) {
        final int queryStart = mediaUrl.indexOf('?');
        final int fragmentStart = mediaUrl.indexOf('#');
        final int pathEnd;
        if (queryStart < 0) {
            pathEnd = fragmentStart < 0 ? mediaUrl.length() : fragmentStart;
        } else if (fragmentStart < 0) {
            pathEnd = queryStart;
        } else {
            pathEnd = Math.min(queryStart, fragmentStart);
        }
        return mediaUrl.substring(0, pathEnd);
    }

    private static boolean isVideoFormat(@Nullable final MediaFormat format) {
        return format == MediaFormat.MPEG_4
                || format == MediaFormat.WEBM
                || format == MediaFormat.v3GPP;
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() {
        return Collections.emptyList();
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitlesDefault() {
        return Collections.emptyList();
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitles(final MediaFormat format) {
        return Collections.emptyList();
    }

    @Override
    public StreamType getStreamType() {
        return StreamType.VIDEO_STREAM;
    }

    @Nonnull
    @Override
    public String getHost() {
        return "";
    }

    @Nonnull
    @Override
    public Privacy getPrivacy() {
        return Privacy.OTHER; // The video details response does not expose privacy status.
    }

    @Nonnull
    @Override
    public String getCategory() throws ParsingException {
        return streamVideoResults.getCategoryId();
    }

    @Nonnull
    @Override
    public String getLicence() {
        return "";
    }

    @Nullable
    @Override
    public Locale getLanguageInfo() {
        return null;
    }

    @Nonnull
    @Override
    public List<String> getTags() {
        return streamVideoResults.getHashtags();
    }

    @Nonnull
    @Override
    public String getSupportInfo() {
        return "https://www.bitchute.com/help-us-grow/";
    }

    @Nonnull
    @Override
    public List<StreamSegment> getStreamSegments() throws ParsingException {
        return Collections.emptyList();
    }

    @Nonnull
    @Override
    public List<MetaInfo> getMetaInfo() throws ParsingException {
        return Collections.emptyList();
    }
}
