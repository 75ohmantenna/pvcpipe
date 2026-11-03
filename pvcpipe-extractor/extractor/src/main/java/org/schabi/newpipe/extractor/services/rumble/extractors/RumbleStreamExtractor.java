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
import org.schabi.newpipe.extractor.services.rumble.RumbleApi;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleStreamLinkHandlerFactory;
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
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.schabi.newpipe.extractor.utils.Utils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final String THUMBNAILS = "t";
    private static final String PUBLICATION_DATE = "pubDate";
    private static final String DURATION = "duration";
    private static final String BITRATE = "bitrate";
    private static final String HEIGHT = "h";
    private static final String WIDTH = "w";
    private static final String STREAMS = "ua";
    private static final String STREAM_METADATA = "meta";
    private static final String STREAM_URL = "url";
    private static final String RELATED_STREAMS_SELECTOR = "ul.mediaList-list";
    private static final Pattern HLS_RESOLUTION_PATTERN =
            Pattern.compile("^[1-9][0-9]*x([1-9][0-9]*)$");
    private static final JsonObject EMPTY_METADATA = new JsonObject();

    private Document doc;
    JsonObject embedJsonStreamInfoObj;
    @Nullable
    private JsonObject details;
    private long numericId;
    private StreamInfoItemsCollector relatedItems;
    @Nullable
    private String resolvedPublicUrl;

    private List<VideoStream> videoStreams;
    private List<AudioStream> audioStreams;
    private List<SubtitlesStream> subtitles = Collections.emptyList();

    public static StreamExtractor factory(
            final StreamingService service,
            final LinkHandler linkHandler) {
        if (linkHandler.getUrl().startsWith(
                RumbleStreamLinkHandlerFactory.BASE_URL + "/shorts/")) {
            return new RumbleShortsStreamExtractor(service, linkHandler);
        }
        return new RumbleStreamExtractor(service, linkHandler);
    }

    private RumbleStreamExtractor(final StreamingService service, final LinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public String getUrl() throws ParsingException {
        return resolvedPublicUrl == null ? super.getUrl() : resolvedPublicUrl;
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        assertPageFetched();
        return Parser.unescapeEntities(firstNonempty(
                embedJsonStreamInfoObj.getString(TITLE), detailString(TITLE)), true);
    }

    @Nullable
    @Override
    public String getTextualUploadDate() throws ParsingException {

        final String embedDate = embedJsonStreamInfoObj.getString(PUBLICATION_DATE);
        if (!isNullOrEmpty(embedDate)) {
            try {
                PvcParsingHelper.parseDateFrom(embedDate);
                return embedDate;
            } catch (final ParsingException ignored) {
                // Invalid optional embed dates may be supplied by details instead.
            }
        }
        return detailString("upload_date");
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
        String thumbUrl = embedJsonStreamInfoObj.getString(COVER_IMAGE);
        if (isNullOrEmpty(thumbUrl)) {
            thumbUrl = detailString("thumb");
            return images(thumbUrl);
        }
        int height = Image.HEIGHT_UNKNOWN;
        int width = Image.WIDTH_UNKNOWN;
        // the size of the cover image is only given in the list of all thumbnails
        for (final Object item : embedJsonStreamInfoObj.getArray(THUMBNAILS)) {
            if (!(item instanceof JsonObject)) {
                continue;
            }
            final JsonObject thumbnail = (JsonObject) item;
            if (thumbUrl != null && thumbUrl.equals(thumbnail.getString(COVER_IMAGE))) {
                height = toImageSize(thumbnail.getInt(HEIGHT), Image.HEIGHT_UNKNOWN);
                width = toImageSize(thumbnail.getInt(WIDTH), Image.WIDTH_UNKNOWN);
                break;
            }
        }
        return List.of(new Image(thumbUrl,
                height, width, Image.ResolutionLevel.fromHeight(height)));
    }

    private static int toImageSize(final int size, final int unknown) {
        return size > 0 ? size : unknown;
    }

    @Nonnull
    @Override
    public Description getDescription() throws ParsingException {
        assertPageFetched();
        if (details != null && details.get("description") instanceof String) {
            return new Description(details.getString("description"), Description.PLAIN_TEXT);
        }
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
        return details != null && details.getBoolean("is_age_restricted") ? 18 : NO_AGE_LIMIT;
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
        if (duration != null && duration.doubleValue() >= 0) {
            return duration.longValue();
        }
        return details == null ? 0 : details.getLong(DURATION, 0);
    }

    /**
     * Returns zero because timestamp extraction is not supported.
     *
     * @return 0 means no timestamp is found.
     */
    @Override
    public long getTimeStamp() {
        return 0;
    }

    @Override
    public long getViewCount() throws ParsingException {
        assertPageFetched();
        if (getStreamType() == StreamType.LIVE_STREAM) {
            return details == null ? -1 : details.getLong("watching_now", -1);
        }
        if (details != null && details.get("views") instanceof Number) {
            return details.getLong("views");
        }
        if (doc == null) {
            return -1;
        }
        final String errorMsg = "Could not extract the view count";
        try {
            final String viewCount =
                    RumbleParsingHelper.extractSafely(false, errorMsg,
                            () -> doc.select("div.media-description-info-views")
                                    .first().text());
            // Some recorded live streams have no view count.
            if (viewCount == null) {
                return super.getViewCount();
            }
            return Utils.mixedNumberWordToLong(viewCount.replace(",", ""));
        } catch (final NumberFormatException e) {
            throw new ParsingException(errorMsg, e);
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
            return isNullOrEmpty(votes) ? -1 : Utils.mixedNumberWordToLong(votes);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public long getLikeCount() throws ParsingException {
        final JsonObject votes = details == null ? null : details.getObject("rumble_votes");
        return votes != null && votes.get("num_votes_up") instanceof Number
                ? votes.getLong("num_votes_up")
                : getLikeOrDislikesCount("span[data-js=\"rumbles_up_votes\"]");
    }

    @Override
    public long getDislikeCount() throws ParsingException {
        final JsonObject votes = details == null ? null : details.getObject("rumble_votes");
        return votes != null && votes.get("num_votes_down") instanceof Number
                ? votes.getLong("num_votes_down")
                : getLikeOrDislikesCount("span[data-js=\"rumbles_down_votes\"]");
    }

    @Nonnull
    @Override
    public String getUploaderUrl() throws ParsingException {
        assertPageFetched();
        return firstNonempty(embedJsonStreamInfoObj.getObject(AUTHOR).getString("url"),
                detailUploader().getString("url"));
    }

    @Nonnull
    @Override
    public String getUploaderName() throws ParsingException {
        assertPageFetched();
        return firstNonempty(embedJsonStreamInfoObj.getObject(AUTHOR).getString("name"),
                detailUploader().getString("name"));
    }

    @Override
    public boolean isUploaderVerified() throws ParsingException {
        return detailUploader().getBoolean("verified_badge");
    }

    @Override
    public long getUploaderSubscriberCount() {
        return detailUploader().getLong("followers", UNKNOWN_SUBSCRIBER_COUNT);
    }

    private JsonObject detailUploader() {
        return details != null && details.get("by") instanceof JsonObject
                ? (JsonObject) details.get("by") : EMPTY_METADATA;
    }

    private String detailString(final String key) {
        return details == null ? null : details.getString(key);
    }

    private static String firstNonempty(final String first, final String second) {
        return !isNullOrEmpty(first) ? first : second == null ? "" : second;
    }

    private static List<Image> images(final String url) {
        return isNullOrEmpty(url) ? Collections.emptyList() : List.of(new Image(url,
                Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN));
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() throws ParsingException {
        assertPageFetched();
        if (detailUploader().containsKey("thumb")) {
            return images(detailUploader().getString("thumb"));
        }
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
            if (isNullOrEmpty(thumbnailUrl)) {
                return Collections.emptyList(); // a letter is shown in place of an image
            }
            // the page does not give a size, but Rumble serves these avatars no larger than
            // 150px in either direction
            return List.of(new Image(thumbnailUrl,
                    Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.LOW));
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
                .setMediaFormat(audioFormat(videoUrl))
                .setAverageBitrate(bitrate);
        return builder.build();
    }

    @Nullable
    private static MediaFormat audioFormat(final String url) {
        try {
            final String path = new URI(url).getPath();
            if (path == null) {
                return null;
            }
            final String suffix = path.toLowerCase(Locale.ROOT);
            if (suffix.endsWith(".aac")) {
                return MediaFormat.AAC;
            }
            return suffix.endsWith(".m4a") || suffix.endsWith(".mp4") ? MediaFormat.M4A : null;
        } catch (final URISyntaxException e) {
            return null;
        }
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
        final boolean isLiveStream = isLive == null
                ? details != null && details.getBoolean(videoLiveStreamKey)
                : isLive.doubleValue() == 2;
        return isLiveStream ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
    }

    @Nullable
    @Override
    public StreamInfoItemsCollector getRelatedItems() throws ExtractionException {
        if (relatedItems != null) {
            return relatedItems;
        }
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        if (doc == null || doc.select(RELATED_STREAMS_SELECTOR).isEmpty()) {
            final JsonObject data;
            try {
                data = RumbleApi.fetchData(getDownloader(), RumbleApi.serviceUrl(
                        "video.autoplay", Map.of("api", "8", "video_id",
                                Long.toString(numericId))), getExtractorLocalization());
            } catch (final IOException e) {
                throw new ParsingException("Could not load Rumble related videos", e);
            }
            final Set<Long> seen = new HashSet<>();
            seen.add(numericId);
            for (final Object entry : JsonUtils.getArray(data, "videos")) {
                if (!(entry instanceof JsonObject)) {
                    continue;
                }
                final JsonObject video = (JsonObject) entry;
                final Number value = JsonUtils.getNumber(video, "id");
                final long id;
                try {
                    id = value instanceof Long || value instanceof Integer
                            ? value.longValue() : new BigDecimal(value.toString()).longValueExact();
                } catch (final ArithmeticException | NumberFormatException e) {
                    throw new ParsingException("Invalid Rumble related video ID", e);
                }
                if (id <= 0) {
                    throw new ParsingException("Rumble related video ID must be positive");
                }
                if (seen.add(id)) {
                    collector.commit(new RumbleJsonStreamInfoItemExtractor(video));
                }
            }
            relatedItems = collector;
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

        relatedItems = collector;
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

        final RumbleMediaResolver.ResolvedMedia resolved = RumbleMediaResolver.resolve(
                downloader, getLinkHandler(), getExtractorLocalization());
        doc = resolved.getDocument();
        details = resolved.getDetails();
        numericId = resolved.getNumericId();
        final String embedVideoId = resolved.getEmbedId();

        final String queryUrl = "https://rumble.com/embedJS/u3/?request=video&ver=2&v="
                + embedVideoId;

        final Response response2 = RumbleParsingHelper.fetchResponse(downloader, queryUrl);

        // TODO Check whether embed requests need site cookies on challenged sessions.
        try {
            embedJsonStreamInfoObj = JsonParser.object().from(response2.responseBody());
            if (embedJsonStreamInfoObj.containsKey("vid")) {
                final Object vid = embedJsonStreamInfoObj.get("vid");
                try {
                    if (!(vid instanceof Number)
                            || (vid instanceof Long || vid instanceof Integer
                            ? ((Number) vid).longValue()
                            : new BigDecimal(vid.toString()).longValueExact())
                            != resolved.getNumericId()) {
                        throw new ParsingException("Rumble embed vid does not match resolved ID");
                    }
                } catch (final ArithmeticException | NumberFormatException e) {
                    throw new ParsingException("Invalid Rumble numeric embed vid", e);
                }
            }
            extractStreams(downloader);
            subtitles = RumbleSubtitles.extract(embedJsonStreamInfoObj, details);
            resolvedPublicUrl = resolved.getPublicUrl();
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

}
