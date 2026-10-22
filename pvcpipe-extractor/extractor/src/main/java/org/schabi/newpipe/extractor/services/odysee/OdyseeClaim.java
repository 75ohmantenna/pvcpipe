package org.schabi.newpipe.extractor.services.odysee;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.utils.Utils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads the claim objects the Odysee API returns. A claim is a video, an audio file, a
 * livestream, a channel, a playlist or a repost of one of those.
 */
public final class OdyseeClaim {

    public static final String TAG_MATURE = "mature";
    public static final String TAG_UNLISTED = "c:unlisted";
    public static final String TAG_MEMBERS_ONLY = "c:members-only";
    public static final String TAG_PURCHASE = "c:purchase";
    public static final String TAG_RENTAL = "c:rental";
    public static final String TAG_SCHEDULED_HIDDEN = "c:scheduled:hide";

    public static final String ANONYMOUS = "Anonymous";

    // Tags that start with this prefix are settings of the website, not topics.
    private static final String CONTROL_TAG_PREFIX = "c:";

    private static final String IMAGE_PROXY = "https://thumbnails.odycdn.com/optimize/";
    private static final int SHORT_MAX_SECONDS = 180;
    // The end of the year 9999.
    private static final long LAST_SECOND = 253402300799L;
    private static final double SHORT_MAX_ASPECT = 0.8;

    private OdyseeClaim() {
    }

    /**
     * Unwraps a repost to obtain its original claim.
     *
     * @param claim a claim, which may be a repost
     * @return the claim that was reposted, or the claim itself
     */
    @Nonnull
    public static JsonObject unwrapRepost(@Nonnull final JsonObject claim) {
        final JsonObject reposted = claim.getObject("reposted_claim");
        return "repost".equals(claim.getString("value_type")) && reposted != null
                && !reposted.isEmpty() ? reposted : claim;
    }

    @Nonnull
    public static JsonObject value(@Nonnull final JsonObject claim) {
        return claim.getObject("value");
    }

    public static boolean isStream(@Nonnull final JsonObject claim) {
        return "stream".equals(claim.getString("value_type"));
    }

    public static boolean isChannel(@Nonnull final JsonObject claim) {
        return "channel".equals(claim.getString("value_type"));
    }

    public static boolean isPlaylist(@Nonnull final JsonObject claim) {
        return "collection".equals(claim.getString("value_type"));
    }

    /**
     * A stream claim without a file is the page of a livestream: one that is on air, one that
     * is announced, or one that has ended and was not published as a recording.
     */
    public static boolean isLivestream(@Nonnull final JsonObject claim) {
        return isStream(claim) && !value(claim).has("source");
    }

    /**
     * Checks whether the claim contains playable media.
     *
     * @return whether the claim is something a media player can play
     */
    public static boolean isPlayable(@Nonnull final JsonObject claim) {
        if (!isStream(claim)) {
            return false;
        }
        final String streamType = value(claim).getString("stream_type");
        return isLivestream(claim) || "video".equals(streamType) || "audio".equals(streamType);
    }

    @Nonnull
    public static StreamType streamType(@Nonnull final JsonObject claim) {
        if (isLivestream(claim)) {
            return StreamType.LIVE_STREAM;
        }
        return "audio".equals(value(claim).getString("stream_type"))
                ? StreamType.AUDIO_STREAM : StreamType.VIDEO_STREAM;
    }

    /**
     * Returns the publisher's title or falls back to the claim name.
     *
     * @return the title the publisher gave the claim, or its name in the address if it has none
     */
    @Nonnull
    public static String title(@Nonnull final JsonObject claim) {
        final String title = value(claim).getString("title");
        return Utils.isBlank(title) ? claim.getString("name", "") : title;
    }

    /**
     * Returns the claim's website address.
     *
     * @return the website address of the claim
     */
    @Nonnull
    public static String webUrl(@Nonnull final JsonObject claim) {
        if (isPlaylist(claim)) {
            return OdyseeUrl.playlistUrl(claim.getString("claim_id", ""));
        }
        final String canonical = claim.getString("canonical_url");
        if (!Utils.isNullOrEmpty(canonical)) {
            return OdyseeUrl.lbryUriToWebUrl(canonical);
        }
        final String permanent = claim.getString("permanent_url");
        if (!Utils.isNullOrEmpty(permanent)) {
            return OdyseeUrl.lbryUriToWebUrl(permanent);
        }
        return OdyseeUrl.toWebUrl(claim.getString("name", "") + ":"
                + claim.getString("claim_id", ""));
    }

    /**
     * A claim names its channel and proves it with a signature. Anyone can name a channel that
     * is not theirs, so a claim whose signature does not hold counts as published anonymously,
     * as it does on the website.
     *
     * @return the channel that published the claim, or {@code null} if it was published
     * anonymously
     */
    @Nullable
    public static JsonObject channel(@Nonnull final JsonObject claim) {
        final JsonObject channel = claim.getObject("signing_channel");
        final boolean forged = claim.has("is_channel_signature_valid")
                && !claim.getBoolean("is_channel_signature_valid");
        return forged || channel == null || Utils.isNullOrEmpty(channel.getString("claim_id"))
                ? null : channel;
    }

    @Nonnull
    public static String uploaderName(@Nonnull final JsonObject claim) {
        final JsonObject channel = channel(claim);
        return channel == null ? ANONYMOUS : title(channel);
    }

    @Nonnull
    public static String uploaderUrl(@Nonnull final JsonObject claim) {
        final JsonObject channel = channel(claim);
        return channel == null ? "" : webUrl(channel);
    }

    @Nonnull
    public static List<Image> uploaderAvatars(@Nonnull final JsonObject claim) {
        final JsonObject channel = channel(claim);
        return channel == null ? List.of() : avatars(channel);
    }

    /**
     * Returns the media duration in seconds.
     *
     * @return the length in seconds, or -1 if the claim does not say
     */
    public static long duration(@Nonnull final JsonObject claim) {
        final JsonObject value = value(claim);
        final long video = value.getObject("video").getLong("duration", -1);
        return video >= 0 ? video : value.getObject("audio").getLong("duration", -1);
    }

    /**
     * Returns the release date, falling back to the claim's creation date.
     *
     * @return when the publisher says the content was released, or when the claim was made
     */
    @Nullable
    public static DateWrapper uploadDate(@Nonnull final JsonObject claim) {
        long seconds = -1;
        try {
            seconds = Long.parseLong(value(claim).getString("release_time", ""));
        } catch (final NumberFormatException ignored) {
            // Older claims have no release time.
        }
        // A publisher can write any number as the release time. One that is no date gives
        // way to the time the claim was made, which the network sets.
        if (!isDate(seconds)) {
            seconds = claim.getObject("meta").getLong("creation_timestamp", -1);
        }
        if (!isDate(seconds)) {
            seconds = claim.getLong("timestamp", -1);
        }
        return isDate(seconds) ? new DateWrapper(Instant.ofEpochSecond(seconds)) : null;
    }

    private static boolean isDate(final long epochSecond) {
        return epochSecond > 0 && epochSecond <= LAST_SECOND;
    }

    @Nullable
    public static String textualUploadDate(@Nonnull final JsonObject claim) {
        final DateWrapper date = uploadDate(claim);
        return date == null ? null : date.getInstant().toString();
    }

    public static boolean hasTag(@Nonnull final JsonObject claim, @Nonnull final String tag) {
        for (final Object entry : value(claim).getArray("tags")) {
            if (entry instanceof String && tag.equalsIgnoreCase((String) entry)) {
                return true;
            }
        }
        return false;
    }

    // A price is written into the tag itself, as in "c:purchase:5.00".
    private static boolean hasTagOrSetting(final JsonObject claim, final String tag) {
        final String prefix = tag + ":";
        for (final Object entry : value(claim).getArray("tags")) {
            if (entry instanceof String && (tag.equalsIgnoreCase((String) entry)
                    || ((String) entry).toLowerCase(Locale.ROOT).startsWith(prefix))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns topic tags without website settings.
     *
     * @return the topic tags of the claim, without the website's own setting tags
     */
    @Nonnull
    public static List<String> tags(@Nonnull final JsonObject claim) {
        final List<String> tags = new ArrayList<>();
        for (final Object entry : value(claim).getArray("tags")) {
            if (entry instanceof String
                    && !((String) entry).toLowerCase(Locale.ROOT).startsWith(CONTROL_TAG_PREFIX)) {
                tags.add((String) entry);
            }
        }
        return tags;
    }

    /**
     * Determines the claim's availability to anonymous visitors.
     *
     * @return whether an anonymous visitor may play the claim
     */
    @Nonnull
    public static ContentAvailability availability(@Nonnull final JsonObject claim) {
        if (hasTag(claim, TAG_MEMBERS_ONLY)) {
            return ContentAvailability.MEMBERSHIP;
        }
        if (hasTagOrSetting(claim, TAG_PURCHASE) || hasTagOrSetting(claim, TAG_RENTAL)
                || hasFee(claim)) {
            return ContentAvailability.PAID;
        }
        return ContentAvailability.AVAILABLE;
    }

    private static boolean hasFee(final JsonObject claim) {
        try {
            return Double.parseDouble(
                    value(claim).getObject("fee").getString("amount", "0")) > 0;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    /**
     * Odysee calls a video a short when it is upright and at most three minutes long.
     */
    public static boolean isShort(@Nonnull final JsonObject claim) {
        final JsonObject video = value(claim).getObject("video");
        final int width = video.getInt("width", 0);
        final int height = video.getInt("height", 0);
        final long duration = video.getLong("duration", -1);
        return width > 0 && height > 0 && duration >= 0 && duration <= SHORT_MAX_SECONDS
                && (double) width / height <= SHORT_MAX_ASPECT;
    }

    @Nonnull
    public static List<Image> thumbnails(@Nonnull final JsonObject claim) {
        final String url = value(claim).getObject("thumbnail").getString("url");
        return images(url, new int[][]{{390, 220}, {1280, 720}}, true);
    }

    @Nonnull
    public static List<Image> avatars(@Nonnull final JsonObject channel) {
        final String url = value(channel).getObject("thumbnail").getString("url");
        return images(url, new int[][]{{160, 160}, {400, 400}}, true);
    }

    @Nonnull
    public static List<Image> banners(@Nonnull final JsonObject channel) {
        final String url = value(channel).getObject("cover").getString("url");
        return images(url, new int[][]{{1280, 320}}, false);
    }

    /**
     * Publishers link images of any size from any host. Odysee's image service scales them to
     * the asked width and keeps their proportions, so the height it returns matches the asked
     * one only for an image of the usual shape.
     */
    private static List<Image> images(@Nullable final String url, final int[][] sizes,
                                      final boolean usualShape) {
        if (url == null || !(url.startsWith(Utils.HTTPS) || url.startsWith(Utils.HTTP))) {
            return List.of();
        }
        final List<Image> images = new ArrayList<>();
        for (final int[] size : sizes) {
            images.add(new Image(
                    IMAGE_PROXY + "s:" + size[0] + ":" + size[1] + "/quality:85/plain/" + url,
                    usualShape ? size[1] : Image.HEIGHT_UNKNOWN, size[0],
                    Image.ResolutionLevel.fromHeight(size[1])));
        }
        return images;
    }

    /**
     * Collects claim objects from a search result array.
     *
     * @param claims objects from a claim search
     * @return those of them that are claims
     */
    @Nonnull
    public static List<JsonObject> list(@Nonnull final JsonArray claims) {
        final List<JsonObject> list = new ArrayList<>();
        for (final Object claim : claims) {
            if (claim instanceof JsonObject) {
                list.add((JsonObject) claim);
            }
        }
        return list;
    }
}
