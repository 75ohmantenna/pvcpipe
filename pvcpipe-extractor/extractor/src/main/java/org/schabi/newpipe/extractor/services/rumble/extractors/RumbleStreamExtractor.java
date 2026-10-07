package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.github.evermindzz.hlsdownloader.common.Fetcher;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.MetaInfo;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.pvc.misc.PvcParsingHelper;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.utils.ExtractorLogger;
import org.schabi.newpipe.extractor.utils.Utils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.schabi.newpipe.extractor.stream.Stream.ID_UNKNOWN;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

@SuppressWarnings({"checkstyle:FinalLocalVariable", "checkstyle:FinalParameters"})
public final class RumbleStreamExtractor extends StreamExtractor {

    private static final String TAG = "RumbleStreamExtractor";
    private static final String AUTHOR = "author";
    private static final String TITLE = "title";
    private static final String COVER_IMAGE = "i";
    private static final String PUBLICATION_DATE = "pubDate";
    private static final String DURATION = "duration";
    private static final String BITRATE = "bitrate";
    private static final String HEIGHT = "h";
    private static final String STREAMS = "ua";
    private static final String STREAM_METADATA = "meta";
    private static final String STREAM_URL = "url";
    private static final String RELATED_STREAMS_SELECTOR = "ul.mediaList-list";
    private static final Pattern HLS_RESOLUTION_PATTERN =
            Pattern.compile("^[1-9][0-9]*x([1-9][0-9]*)$");

    private Document doc;
    JsonObject embedJsonStreamInfoObj;
    private boolean embedOnly;

    private int ageLimit = -1;
    private List<VideoStream> videoStreams;
    private List<AudioStream> audioStreams;
    private List<SubtitlesStream> subtitles = Collections.emptyList();

    public static StreamExtractor factory(
            final StreamingService service,
            final LinkHandler linkHandler) {
        if (linkHandler.getOriginalUrl().contains("/shorts/")) {
            return new RumbleShortsStreamExtractor(service, linkHandler);
        }
        return new RumbleStreamExtractor(service, linkHandler);
    }

    private RumbleStreamExtractor(final StreamingService service, final LinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        assertPageFetched();
        final String title =
                Parser.unescapeEntities(embedJsonStreamInfoObj.getString(TITLE), true);

        return title;
    }

    @Nullable
    @Override
    public String getTextualUploadDate() throws ParsingException {

        final String textualDate = embedJsonStreamInfoObj.getString(PUBLICATION_DATE);
        return textualDate;
    }

    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        final String textualUploadDate = getTextualUploadDate();


        if (isNullOrEmpty(textualUploadDate)) {
            return null;
        }
        // the format is: 2021-02-08T19:37:25+00:00  youtube-dl parses it with iso8601b
        return new DateWrapper(PvcParsingHelper.parseDateFrom(textualUploadDate), false);
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        assertPageFetched();
        final String thumbUrl = embedJsonStreamInfoObj.getString(COVER_IMAGE);
        return List.of(new Image(thumbUrl,
                Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN));
    }

    @Nonnull
    @Override
    public Description getDescription() throws ParsingException {
        assertPageFetched();
        if (doc == null) {
            return new Description("", Description.PLAIN_TEXT);
        }
        String description = "";

        final Elements descriptionData = doc.select("p.media-description");
        if (!descriptionData.isEmpty()) {
            final List<Node> nodes = descriptionData.first().childNodes();

            // the node that contains the the description may vary.
            // Some videos do not have a description at all
            for (final Node node : nodes) {
                if (node instanceof TextNode) {
                    if (!((TextNode) node).isBlank()) {
                        description += node.toString();
                    }
                }
            }
        }

        return new Description(Parser.unescapeEntities(description, false), Description.PLAIN_TEXT);
    }

    @Override
    public int getAgeLimit() throws ParsingException {
        if (ageLimit == -1) {
            ageLimit = NO_AGE_LIMIT;
        }

        return ageLimit;
    }

    public static class FetchWithDownloaderImpl implements Fetcher {
        private final Downloader downloader;

        public FetchWithDownloaderImpl(final Downloader downloader) {
            this.downloader = downloader;
        }

        private InputStream stringToInputStream(String input) {
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            return new ByteArrayInputStream(bytes);
        }

        public InputStream fetchContent(URI uri) throws IOException {
            try {
                final Response response = downloader.get(uri.toString());
                return stringToInputStream(response.responseBody());
            } catch (ReCaptchaException e) {
                throw new IOException(e);
            }
        }
    }

    @Override
    public long getLength() throws ParsingException {
        assertPageFetched();
        final Number duration = embedJsonStreamInfoObj.getNumber(DURATION);
        return duration == null ? 0 : duration.longValue();
    }

    /**
     * @return 0 means no timestamp is found.
     */
    @Override
    public long getTimeStamp() {
        return 0;
    }

    @Override
    public long getViewCount() throws ParsingException {
        assertPageFetched();
        if (doc == null) {
            return -1;
        }
        if (getStreamType() == StreamType.LIVE_STREAM) {
            return getLiveViewCount();
        } else {
            final String errorMsg = "Could not extract the view count";
            try {
                final String viewCount =
                        RumbleParsingHelper.extractSafely(false, errorMsg,
                                () -> doc.select("div.media-description-info-views")
                                        .first().text());
                // some or all recorded live streams have no view count
                // eg.: https://rumble.com/v6q1s7c
                if (null == viewCount) {
                    return super.getViewCount();
                }
                return Utils.mixedNumberWordToLong(viewCount.replace(",", ""));
            } catch (final NumberFormatException e) {
                throw new ParsingException(errorMsg, e);
            }
        }
    }

    public long getLikeOrDislikesCount(final String cssQuery) throws ParsingException {
        if (doc == null) {
            return -1;
        }
        try {
            final String votes = RumbleParsingHelper.extractSafely(false, "",
                    () -> doc.select(cssQuery)
                            .first().text());
            return Utils.mixedNumberWordToLong(votes);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public long getLikeCount() throws ParsingException {
        return getLikeOrDislikesCount("span[data-js=\"rumbles_up_votes\"]");
    }

    @Override
    public long getDislikeCount() throws ParsingException {
        return getLikeOrDislikesCount("span[data-js=\"rumbles_down_votes\"]");
    }

    @Nonnull
    @Override
    public String getUploaderUrl() throws ParsingException {
        assertPageFetched();
        return embedJsonStreamInfoObj.getObject(AUTHOR).getString("url");
    }

    @Nonnull
    @Override
    public String getUploaderName() throws ParsingException {
        assertPageFetched();
        final String uploaderName =
                embedJsonStreamInfoObj.getObject(AUTHOR).getString("name");
        return uploaderName;
    }

    @Override
    public boolean isUploaderVerified() throws ParsingException {
        // TODO Extract the watch page's uploader verification badge when available.
        return false;
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() throws ParsingException {
        assertPageFetched();
        if (doc == null) {
            return Collections.emptyList();
        }
        final Elements elems = doc.getElementsByClass("media-by--a");
        if (elems.isEmpty()) {
            return Collections.emptyList();
        }
        final String theUserPathToHisAvatar =
                elems.get(0).getElementsByTag("i").first().attributes().get("class");
        try {
            final String thumbnailUrl = RumbleParsingHelper
                    .extractUploaderAvatarUrl(theUserPathToHisAvatar, doc);
            return List.of(new Image(thumbnailUrl,
                    Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN));
        } catch (final Exception e) {
            throw new ParsingException(
                    "Could not extract the avatar url: " + theUserPathToHisAvatar);
        }
    }

    @Nonnull
    @Override
    public String getSubChannelUrl() {
        return "";
    }

    @Nonnull
    @Override
    public String getSubChannelName() {
        return "";
    }

    @Nonnull
    @Override
    public String getDashMpdUrl() {
        return "";
    }

    @Nonnull
    @Override
    public String getHlsUrl() {
        final List<JsonObject> hlsEntries = getFormatEntries(
                embedJsonStreamInfoObj.getObject(STREAMS).get("hls"));
        return hlsEntries.isEmpty() ? "" : hlsEntries.get(0).getString(STREAM_URL, "");
    }

    @Override
    public List<AudioStream> getAudioStreams() {
        return audioStreams;
    }

    @Override
    public List<VideoStream> getVideoStreams() throws ExtractionException {
        return videoStreams;
    }

    private void extractStreams(final Downloader downloader) throws ExtractionException {

        final List<AudioStream> audioStreamsList = new ArrayList<>();
        final List<VideoStream> videoStreamsList = new ArrayList<>();
        String fallbackHlsUrl = null;

        final JsonObject streamFormats = embedJsonStreamInfoObj.getObject(STREAMS);
        final Set<String> formatKeys = streamFormats.keySet();
        for (final String formatKey : formatKeys) {
            // Timeline streams contain one frame per second. Tar entries duplicate HLS variants
            // when the embed endpoint is queried without its default parameters. Neither is a
            // useful playback source. This mirrors yt-dlp's Rumble format handling.
            if ("timeline".equals(formatKey) || "tar".equals(formatKey)) {
                continue;
            }

            final Object formatData = streamFormats.get(formatKey);
            for (final JsonObject stream : getFormatEntries(formatData)) {
                final JsonObject metadata = stream.getObject(STREAM_METADATA);
                final String videoUrl = stream.getString(STREAM_URL);
                if (isNullOrEmpty(videoUrl)) {
                    continue;
                }

                if ("hls".equals(formatKey)) {
                    final int sizeBefore = videoStreamsList.size();
                    extractStreamsFromMasterHlsPlaylist(downloader, videoUrl, videoStreamsList);
                    if (videoStreamsList.size() == sizeBefore) {
                        fallbackHlsUrl = videoUrl;
                    }
                    continue;
                }

                if ("audio".equals(formatKey)) {
                    audioStreamsList.add(createAudioStream(videoUrl, metadata.getInt(BITRATE)));
                    continue;
                }

                final int height = metadata.getInt(HEIGHT, -1);
                final int bitrate = metadata.getInt(BITRATE, -1);
                final String resolution = height > 0 ? height + "p" : "unknown";
                final String resolutionAndBitrate = bitrate > 0
                        ? resolution + "@" + bitrate + "k" : resolution;
                final VideoStream videoStream = createVideoStream(
                        formatKey, videoUrl, resolutionAndBitrate);
                if (bitrate > 0) {
                    videoStream.pvcSetBitrate(bitrate * 1000);
                }
                videoStreamsList.add(videoStream);
            }
        }

        if (videoStreamsList.isEmpty() && fallbackHlsUrl != null) {
            videoStreamsList.add(createVideoStream("hls", fallbackHlsUrl, "auto"));
        }

        videoStreams = videoStreamsList;
        audioStreams = audioStreamsList;
    }

    private static List<JsonObject> getFormatEntries(final Object formatData) {
        final List<JsonObject> entries = new ArrayList<>();
        if (formatData instanceof JsonObject) {
            for (final Object value : ((JsonObject) formatData).values()) {
                if (value instanceof JsonObject) {
                    entries.add((JsonObject) value);
                }
            }
        } else if (formatData instanceof JsonArray) {
            ((JsonArray) formatData).streamAsJsonObjects().forEach(entries::add);
        }
        return entries;
    }

    /**
     *  Extract available HLS variant playlists as streams from master HLS playlist.
     *
     * @param downloader        the downloader instance
     * @param hlsMasterPlaylist URL of the HLS master playlist
     * @param videoStreamsList  the video stream list to add found streams
     */
    private void extractStreamsFromMasterHlsPlaylist(
            final Downloader downloader,
            final String hlsMasterPlaylist,
            final List<VideoStream> videoStreamsList) {
        try {
            final HlsParser parser = new HlsParser(
                    variants -> {
                        insertVariantsIntoVideoStreams(variants, videoStreamsList);
                        // The parser requires a selected variant and then fetches its media
                        // playlist. We only need the variants; stop before that extra fetch.
                        throw new HlsVariantsCollectedException();
                    },
                    new FetchWithDownloaderImpl(downloader),
                    false
            );
            parser.parse(new URI(hlsMasterPlaylist));
        } catch (final HlsVariantsCollectedException ignored) {
            // All variants have been collected successfully.
        } catch (final IOException | URISyntaxException | RuntimeException e) {
            // HLS is optional: retain progressive streams or the master-playlist fallback.
            logOptionalHlsFailure(e);
        }
    }

    private static void logOptionalHlsFailure(final Exception failure) {
        try {
            if (failure instanceof RuntimeException) {
                ExtractorLogger.w(TAG, "Unexpected failure while extracting HLS variants", failure);
            } else {
                ExtractorLogger.d(TAG, "Could not load HLS variants", failure);
            }
        } catch (final RuntimeException ignored) {
            // A custom logger must not prevent playback through the optional HLS fallback.
        }
    }

    private static final class HlsVariantsCollectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private void insertVariantsIntoVideoStreams(
            List<HlsParser.VariantStream> variants,
            List<VideoStream> videoStreamsList) {
        for (final HlsParser.VariantStream stream : variants) {
            // hls only provides bandwidth so the bitrate is lower
            // a simple tests shows that the bitrate is approx. 15% less
            final float bandwidth2bitrateFactor = 0.85f;

            final URI playlistUrl = stream.getUri();
            if (playlistUrl == null) {
                continue;
            }
            final int bitrate = (int) (stream.getBandwidth() * bandwidth2bitrateFactor);
            final VideoStream videoStream = createVideoStream(
                    "hls",
                    playlistUrl.toString(),
                    resolutionLabel(stream.getResolution(), bitrate)
            );
            videoStream.pvcSetBitrate(bitrate);
            videoStreamsList.add(videoStream);
        }
    }

    static String resolutionLabel(@Nullable final String resolution, final int bitrate) {
        String height = null;
        if (resolution != null) {
            final Matcher matcher = HLS_RESOLUTION_PATTERN.matcher(resolution);
            if (matcher.matches()) {
                height = matcher.group(1);
            }
        }
        final String bitratePart = bitrate > 0 ? "@" + bitrate / 1000 + "k" : "";
        return height == null ? "unknown" + bitratePart : height + "p" + bitratePart;
    }

    private AudioStream createAudioStream(
            final String videoUrl,
            final int bitrate) {
        final AudioStream.Builder builder = new AudioStream.Builder()
                .setId(ID_UNKNOWN)
                .setContent(videoUrl, true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setMediaFormat(MediaFormat.M4A)
                .setAverageBitrate(bitrate);
        return builder.build();
    }

    private VideoStream createVideoStream(
            final String formatKey,
            final String videoUrl,
            final String resolution) {
        final VideoStream.Builder builder = new VideoStream.Builder()
                .setId(ID_UNKNOWN)
                .setIsVideoOnly(false)
                .setResolution(resolution)
                .setContent(videoUrl, true);
        if ("hls".equals(formatKey)) {
            builder.setManifestUrl(videoUrl)
                    .setDeliveryMethod(DeliveryMethod.HLS)
                    .setMediaFormat(MediaFormat.MPEG_4);
        } else {
            builder.setMediaFormat(MediaFormat.getFromSuffix(formatKey));
        }
        return builder.build();
    }

    @Override
    public StreamType getStreamType() {
        final String videoLiveStreamKey = "live";
        // The embed API uses 2 for an active live stream. A value of 1 is upcoming or post-live,
        // depending on livestream_has_dvr, and must not be exposed as currently live.
        final Number isLive = embedJsonStreamInfoObj.getNumber(videoLiveStreamKey);
        final boolean isLiveStream = isLive != null && isLive.intValue() == 2;
        return isLiveStream ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
    }

    @Nullable
    @Override
    public StreamInfoItemsCollector getRelatedItems() throws ExtractionException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        if (doc == null || doc.select(RELATED_STREAMS_SELECTOR).isEmpty()) {
            return collector;
        }
        final List<Node> nodes = doc.select(RELATED_STREAMS_SELECTOR).first().childNodes();
        for (final Node node : nodes) {
            // we only want Element(s) as they might bear useful content
            if ((node instanceof Element)
                    && (null != ((Element) node).closest(".mediaList-item"))) {
                collector.commit(new RumbleStreamRelatedInfoItemExtractor(
                        getTimeAgoParser(), (Element) node, doc
                ));
            }
        }

        return collector;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getErrorMessage() {
        return null;
    }

    @SuppressWarnings("checkstyle:LineLength")
    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {


        embedOnly = getUrl().contains("/embed/");
        final String embedVideoId;
        if (embedOnly) {
            embedVideoId = getId();
        } else {
            doc = RumbleParsingHelper.fetchParseValidate(downloader, getUrl());
            embedVideoId = "v" + RumbleParsingHelper.getEmbedVideoId(
                    getUrl(), () -> doc.toString());
        }

        final String queryUrl = "https://rumble.com/embedJS/u3/?request=video&ver=2&v="
                + embedVideoId;

        final Response response2 = RumbleParsingHelper.fetchResponse(downloader, queryUrl);

        // TODO Check whether embed requests need site cookies on challenged sessions.
        try {
            embedJsonStreamInfoObj = JsonParser.object().from(response2.responseBody());
            extractStreams(downloader);
            extractSubtitles();
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not read JSON from: " + queryUrl, e);
        }
    }

    @Nonnull
    @Override
    public String getHost() {
        return "";
    }

    @Nonnull
    @Override
    public Privacy getPrivacy() {
        return Privacy.PUBLIC;
    }

    @Nonnull
    @Override
    public String getCategory() {
        return "";
    }

    @Nonnull
    @Override
    public String getLicence() {
        return "";
    }

    @Override
    public Locale getLanguageInfo() {
        return null;
    }

    @Nonnull
    @Override
    public List<String> getTags() {
        return Collections.emptyList();
    }

    @Nonnull
    @Override
    public String getSupportInfo() {
        return "";
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

    @Override
    public List<VideoStream> getVideoOnlyStreams() {
        return Collections.emptyList();
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

    private void extractSubtitles() throws ParsingException {
        final JsonObject captions = embedJsonStreamInfoObj.getObject("cc");
        if (captions.isEmpty()) {
            subtitles = Collections.emptyList();
            return;
        }

        final List<SubtitlesStream> extractedSubtitles = new ArrayList<>();
        for (final String languageCode : captions.keySet()) {
            final JsonObject caption = captions.getObject(languageCode);
            final String url = caption.getString("path");
            if (!isNullOrEmpty(url)) {
                extractedSubtitles.add(new SubtitlesStream.Builder()
                        .setContent(url, true)
                        .setMediaFormat(MediaFormat.VTT)
                        .setLanguageCode(languageCode)
                        .setAutoGenerated(false)
                        .build());
            }
        }
        subtitles = extractedSubtitles;
    }

    private long getLiveViewCount() throws ParsingException {
        if (embedOnly || doc == null) {
            return -1;
        }
        final Pattern matchChecksum = Pattern.compile("viewer_id: \"(.*)\"");
        final Matcher matcher = matchChecksum.matcher(doc.toString());
        if (!matcher.find()) {
            throw new ParsingException("Could not extract viewer_id");
        }
        final String viewerId = matcher.group(1);

        return retrieveLiveStreamViewerCount(getDownloader(),
                retrieveNumericVideoId(),
                viewerId);
    }

    private long retrieveLiveStreamViewerCount(final Downloader downloader,
                                               final String videoNumericId,
                                               final String theViewerId) {
        try {

            // The former POST version of this viewer-count request was not usable.
            final Response response = downloader
                    .get("https://wn0.rumble.com/service.php?video_id="
                            + videoNumericId
                            + "&viewer_id="
                            + theViewerId
                            + "&name=video.watching-now");
            final JsonObject jsonObject =
                    JsonParser.object().from(response.responseBody());
            return jsonObject.getObject("data").getLong("viewer_count", -1);
        } catch (final IOException | ReCaptchaException | JsonParserException ignored) {
            // Live viewer counts are optional metadata.
        }

        return -1;
    }

    private String retrieveNumericVideoId() {
        return embedJsonStreamInfoObj.get("vid").toString();
    }
}
