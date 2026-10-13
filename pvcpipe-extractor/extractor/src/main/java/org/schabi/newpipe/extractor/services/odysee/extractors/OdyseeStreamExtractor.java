package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.services.odysee.OdyseeUrl;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class OdyseeStreamExtractor extends StreamExtractor {

    static final String ORIGINAL_FILE_ID = "original";
    private static final String HLS_ID = "hls";
    private static final String UNKNOWN_RESOLUTION = "unknown";
    private static final int RELATED_COUNT = 20;
    private static final int ADULT_AGE = 18;

    // Runs the extras of a video page next to the lookup of its media address.
    private static final ExecutorService EXTRAS = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "odysee-extras");
        thread.setDaemon(true);
        return thread;
    });

    private static final Pattern HLS_VARIANT = Pattern.compile(
            "#EXT-X-STREAM-INF:([^\\r\\n]*)\\r?\\n(?:#[^\\r\\n]*\\r?\\n)*([^#\\r\\n][^\\r\\n]*)");
    private static final Pattern HLS_BANDWIDTH = Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)");
    private static final Pattern HLS_RESOLUTION =
            Pattern.compile("(?:^|,)RESOLUTION=\\d+x(\\d+)");

    private JsonObject claim;
    private String liveUrl = "";
    private List<VideoStream> videoStreams = List.of();
    private List<AudioStream> audioStreams = List.of();
    private long likes = -1;
    private long dislikes = -1;
    private long viewCount = -1;
    private long uploaderSubscribers = UNKNOWN_SUBSCRIBER_COUNT;

    public OdyseeStreamExtractor(final StreamingService service, final LinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        claim = OdyseeClaim.unwrapRepost(OdyseeApi.resolve(OdyseeUrl.toLbryUri(getId())));
        if (!OdyseeClaim.isPlayable(claim)) {
            final String kind = OdyseeClaim.isStream(claim)
                    ? OdyseeClaim.value(claim).getString("stream_type", "unknown")
                    : claim.getString("value_type", "unknown");
            throw new ContentNotSupportedException(
                    "This Odysee address holds a " + kind + ", which cannot be played");
        }

        final boolean live = OdyseeClaim.isLivestream(claim);
        if (live) {
            fetchLivestream();
        } else {
            requireFree();
        }
        // Likes and counts are extras that depend neither on each other nor on the media
        // address, so they are asked for while that address is looked up. Each of them
        // gives up on its own without failing the page.
        final List<Future<?>> extras = List.of(EXTRAS.submit(this::fetchReactions),
                EXTRAS.submit(() -> fetchViewCount(live)), EXTRAS.submit(this::fetchFollowers));
        try {
            if (!live) {
                fetchFile();
            }
            for (final Future<?> extra : extras) {
                try {
                    extra.get();
                } catch (final ExecutionException ignored) {
                    // An extra that failed leaves its numbers unknown.
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while loading an Odysee video");
        } finally {
            extras.forEach(extra -> extra.cancel(true));
        }
    }

    private void fetchViewCount(final boolean live) {
        // A livestream shows how many are watching it now, which came with its state.
        if (!live) {
            final String claimId = claim.getString("claim_id", "");
            viewCount = OdyseeApi.viewCounts(List.of(claimId)).getOrDefault(claimId, -1L);
        }
    }

    private void fetchFollowers() {
        final JsonObject channel = OdyseeClaim.channel(claim);
        if (channel != null) {
            final String channelId = channel.getString("claim_id", "");
            uploaderSubscribers = OdyseeApi.followerCounts(List.of(channelId))
                    .getOrDefault(channelId, UNKNOWN_SUBSCRIBER_COUNT);
        }
    }

    private void fetchLivestream() throws IOException, ExtractionException {
        final JsonObject channel = OdyseeClaim.channel(claim);
        if (channel == null) {
            throw new ContentNotAvailableException("This Odysee livestream has no channel");
        }
        final JsonObject live = OdyseeApi.livestream(channel.getString("claim_id"));
        final String activeClaimId = live.getObject("ActiveClaim").getString("ClaimID", "");
        final String url = live.getString("VideoURL", "");
        // A channel broadcasts one stream at a time, and its other livestream pages stay up.
        final boolean isThisOne = activeClaimId.isEmpty()
                || activeClaimId.equals(claim.getString("claim_id"));
        if (!live.getBoolean("Live") || url.isEmpty() || !isThisOne) {
            throw new ContentNotAvailableException("This Odysee livestream is not on air");
        }
        liveUrl = url;
        // For a livestream the number shown is how many are watching it now.
        viewCount = live.getLong("ViewerCount", -1);
    }

    // A visitor who is not signed in is not handed what has to be paid for.
    private void requireFree() throws PaidContentException {
        final ContentAvailability availability = OdyseeClaim.availability(claim);
        if (availability == ContentAvailability.MEMBERSHIP) {
            throw new PaidContentException("This Odysee content is for members of the channel");
        }
        if (availability == ContentAvailability.PAID) {
            throw new PaidContentException("This Odysee content has to be bought or rented");
        }
    }

    private void fetchFile() throws IOException, ExtractionException {
        final String fileUrl = OdyseeApi.streamingUrl(claim.getString("permanent_url"));
        if (getStreamType() == StreamType.AUDIO_STREAM) {
            audioStreams = List.of(new AudioStream.Builder()
                    .setId(ORIGINAL_FILE_ID)
                    .setContent(fileUrl, true)
                    .setMediaFormat(fileFormat(claim, true))
                    .setAverageBitrate(fileBitrate(claim) / 1000)
                    .build());
            return;
        }

        final List<VideoStream> streams = new ArrayList<>(fetchTranscodedStreams(fileUrl));
        streams.add(originalFileStream(claim, fileUrl));
        videoStreams = streams;
    }

    /**
     * Odysee transcodes many uploads to HLS and keeps the uploaded file next to it. Without
     * the transcoded renditions the uploaded file still plays, so a failure here is not fatal.
     */
    private List<VideoStream> fetchTranscodedStreams(final String fileUrl) {
        try {
            final String playlistUrl = OdyseeApi.followStreamingUrl(fileUrl);
            if (!playlistUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) {
                return List.of();
            }
            return parseHlsVariants(playlistUrl, OdyseeApi.get(playlistUrl));
        } catch (final IOException | ExtractionException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * @param playlistUrl the address of an HLS master playlist
     * @param playlist    its content
     * @return one stream for each rendition the playlist lists
     */
    @Nonnull
    static List<VideoStream> parseHlsVariants(@Nonnull final String playlistUrl,
                                              @Nonnull final String playlist) {
        final List<VideoStream> variants = new ArrayList<>();
        final URI base = URI.create(playlistUrl);
        final Matcher matcher = HLS_VARIANT.matcher(playlist);
        while (matcher.find()) {
            final String url = base.resolve(matcher.group(2).trim()).toString();
            final Matcher resolution = HLS_RESOLUTION.matcher(matcher.group(1));
            final Matcher bandwidth = HLS_BANDWIDTH.matcher(matcher.group(1));
            final int bitrate = bandwidth.find() ? Integer.parseInt(bandwidth.group(1)) : 0;
            final VideoStream stream = new VideoStream.Builder()
                    .setId(HLS_ID)
                    .setContent(url, true)
                    .setIsVideoOnly(false)
                    .setDeliveryMethod(DeliveryMethod.HLS)
                    .setManifestUrl(url)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setResolution(resolutionLabel(
                            resolution.find() ? Integer.parseInt(resolution.group(1)) : 0,
                            bitrate))
                    .build();
            if (bitrate > 0) {
                stream.pvcSetBitrate(bitrate);
            }
            variants.add(stream);
        }
        return variants;
    }

    @Nonnull
    static VideoStream originalFileStream(@Nonnull final JsonObject claim,
                                          @Nonnull final String fileUrl) {
        final int bitrate = fileBitrate(claim);
        final VideoStream stream = new VideoStream.Builder()
                .setId(ORIGINAL_FILE_ID)
                .setContent(fileUrl, true)
                .setIsVideoOnly(false)
                .setMediaFormat(fileFormat(claim, false))
                .setResolution(resolutionLabel(
                        OdyseeClaim.value(claim).getObject("video").getInt("height", 0), bitrate))
                .build();
        if (bitrate > 0) {
            stream.pvcSetBitrate(bitrate);
        }
        return stream;
    }

    // The app tells streams of one height apart by the bitrate in this label.
    private static String resolutionLabel(final int height, final int bitrate) {
        if (height <= 0) {
            return UNKNOWN_RESOLUTION;
        }
        return bitrate >= 1000 ? height + "p@" + bitrate / 1000 + "k" : height + "p";
    }

    /**
     * @return the average bitrate of the uploaded file in bits per second, or 0 if the claim
     * does not give its size and length
     */
    private static int fileBitrate(final JsonObject claim) {
        final long duration = OdyseeClaim.duration(claim);
        try {
            final long size = Long.parseLong(
                    OdyseeClaim.value(claim).getObject("source").getString("size", "0"));
            return duration > 0 && size > 0 && size < Long.MAX_VALUE / 8
                    ? (int) Math.min(Integer.MAX_VALUE, size * 8 / duration) : 0;
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    private static MediaFormat fileFormat(final JsonObject claim, final boolean audio) {
        final JsonObject source = OdyseeClaim.value(claim).getObject("source");
        MediaFormat format = MediaFormat.getFromMimeType(
                source.getString("media_type", "").toLowerCase(Locale.ROOT));
        if (format == null) {
            final String name = source.getString("name", "");
            format = MediaFormat.getFromSuffix(
                    name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        }
        if (format == null) {
            return null;
        }
        final boolean isVideoFormat = format.getMimeType().startsWith("video/");
        final boolean isAudioFormat = format.getMimeType().startsWith("audio/");
        return audio ? (isAudioFormat ? format : null) : (isVideoFormat ? format : null);
    }

    // Likes are an extra; a video must still open when they cannot be loaded.
    private void fetchReactions() {
        try {
            final JsonObject reactions = OdyseeApi.reactions(claim.getString("claim_id"));
            likes = reactions.getLong("like", -1);
            dislikes = reactions.getLong("dislike", -1);
        } catch (final IOException | ExtractionException | RuntimeException e) {
            likes = -1;
            dislikes = -1;
        }
    }

    @Nonnull
    @Override
    public String getName() {
        return OdyseeClaim.title(claim);
    }

    @Nullable
    @Override
    public String getTextualUploadDate() {
        return OdyseeClaim.textualUploadDate(claim);
    }

    @Nullable
    @Override
    public DateWrapper getUploadDate() {
        return OdyseeClaim.uploadDate(claim);
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() {
        return OdyseeClaim.thumbnails(claim);
    }

    @Nonnull
    @Override
    public Description getDescription() {
        // Odysee renders descriptions as Markdown with every line break kept. Shown as plain
        // text they keep those line breaks; shown as Markdown they would lose them.
        return new Description(OdyseeClaim.value(claim).getString("description", ""),
                Description.PLAIN_TEXT);
    }

    @Override
    public int getAgeLimit() {
        return OdyseeClaim.hasTag(claim, OdyseeClaim.TAG_MATURE) ? ADULT_AGE : NO_AGE_LIMIT;
    }

    @Override
    public long getLength() {
        return Math.max(0, OdyseeClaim.duration(claim));
    }

    @Override
    public long getTimeStamp() throws ParsingException {
        final long timestamp = getTimestampSeconds("((#|&|\\?)t=\\d*h?\\d*m?\\d+s?)");
        return timestamp == -2 ? 0 : timestamp;
    }

    @Override
    public long getViewCount() {
        return viewCount;
    }

    @Override
    public long getUploaderSubscriberCount() {
        return uploaderSubscribers;
    }

    @Override
    public long getLikeCount() {
        return likes;
    }

    @Override
    public long getDislikeCount() {
        return dislikes;
    }

    @Nonnull
    @Override
    public String getUploaderUrl() {
        return OdyseeClaim.uploaderUrl(claim);
    }

    @Nonnull
    @Override
    public String getUploaderName() {
        return OdyseeClaim.uploaderName(claim);
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() {
        return OdyseeClaim.uploaderAvatars(claim);
    }

    @Nonnull
    @Override
    public String getHlsUrl() {
        return liveUrl;
    }

    @Override
    public List<AudioStream> getAudioStreams() {
        return audioStreams;
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
        return OdyseeClaim.streamType(claim);
    }

    @Nullable
    @Override
    public StreamInfoItemsCollector getRelatedItems() throws IOException, ExtractionException {
        final String claimId = claim.getString("claim_id");
        final JsonArray hits = OdyseeApi.search("s=" + Utils.encodeUrlUtf8(getName())
                + "&size=" + RELATED_COUNT + "&from=0&nsfw=false&claimType=file"
                + "&related_to=" + claimId);
        final Set<String> claimIds = new LinkedHashSet<>();
        for (final Object hit : hits) {
            if (hit instanceof JsonObject) {
                final String hitId = ((JsonObject) hit).getString("claimId");
                if (hitId != null && !hitId.equals(claimId)) {
                    claimIds.add(hitId);
                }
            }
        }
        final StreamInfoItemsCollector items = new StreamInfoItemsCollector(getServiceId());
        OdyseeItems.commitStreams(items, OdyseeApi.claimsByIds(claimIds));
        return items;
    }

    @Override
    public Privacy getPrivacy() {
        return OdyseeClaim.hasTag(claim, OdyseeClaim.TAG_UNLISTED)
                ? Privacy.UNLISTED : Privacy.PUBLIC;
    }

    @Nonnull
    @Override
    public String getLicence() {
        final String licence = OdyseeClaim.value(claim).getString("license", "");
        return "None".equalsIgnoreCase(licence) ? "" : licence;
    }

    @Nullable
    @Override
    public Locale getLanguageInfo() {
        final Object language = OdyseeClaim.value(claim).getArray("languages").isEmpty()
                ? null : OdyseeClaim.value(claim).getArray("languages").get(0);
        return language instanceof String && !"none".equals(language)
                ? Locale.forLanguageTag((String) language) : null;
    }

    @Nonnull
    @Override
    public List<String> getTags() {
        return OdyseeClaim.tags(claim);
    }

    @Override
    public boolean isShortFormContent() {
        return OdyseeClaim.isShort(claim);
    }

    @Nonnull
    @Override
    public ContentAvailability getContentAvailability() {
        return ContentAvailability.AVAILABLE;
    }
}
