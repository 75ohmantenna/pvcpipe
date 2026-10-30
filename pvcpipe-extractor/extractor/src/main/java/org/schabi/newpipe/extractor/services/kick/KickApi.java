package org.schabi.newpipe.extractor.services.kick;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.kick.settings.KickSettings;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public final class KickApi {
    private static final Map<String, List<String>> HEADERS = Map.of(
            "Accept", List.of("application/json"),
            "Referer", List.of(KickUrl.BASE + "/"),
            "Origin", List.of(KickUrl.BASE));
    private static final Pattern THUMBNAIL_HEIGHT = Pattern.compile(
            "https://images\\.kick\\.com/video_thumbnails/[^/?#]+/[^/?#]+/"
                    + "(160|360|480|720|1080)\\.webp(?:[?#].*)?");
    private static final Pattern AVATAR_WIDTH = Pattern.compile(
            "https://files\\.kick\\.com/images/user/[0-9]+/profile_image/conversion/"
                    + "[^/?#]+-(medium|fullsize)\\.webp(?:[?#].*)?");
    private static final Pattern VOD_PREVIEW = Pattern.compile(
            "https://web\\.kick\\.com/api/v1/videos/[^/?#]+/thumbnails/[0-9]+"
                    + "\\.jpeg(?:[?#].*)?");

    private KickApi() {
    }

    private static String body(final String url) throws IOException, ExtractionException {
        final Response response = NewPipe.getDownloader().get(url, HEADERS);
        final int status = response.responseCode();
        final String body = response.responseBody();
        final String trimmed = body == null ? "" : body.stripLeading();
        // User-supplied titles and biographies in JSON are not security challenges.
        final String lower = trimmed.startsWith("<") ? trimmed.toLowerCase(Locale.ROOT) : "";
        if ("challenge".equalsIgnoreCase(response.getHeader("cf-mitigated"))
                || lower.contains("cf-chl-")
                || lower.contains("<title>just a moment")
                || lower.contains("/cdn-cgi/challenge-platform")
                || lower.contains("request blocked by security policy")) {
            throw new ReCaptchaException("Kick blocked this request; open Kick to solve the"
                    + " security challenge", KickUrl.BASE + "/");
        }
        if (status == 401) {
            throw new PrivateContentException("Kick requires a signed-in account for this content");
        }
        if (status == 429) {
            throw new IOException("Kick rate-limited this request (HTTP 429)");
        }
        if (status == 404) {
            throw new NotFoundException();
        }
        if (status == 410) {
            throw new ContentNotAvailableException(
                    "This Kick content is unavailable or has expired");
        }
        if (status < 200 || status >= 300) {
            throw new IOException("Kick request returned HTTP " + status);
        }
        if (body == null || body.isBlank() || "null".equals(body.trim())) {
            throw new ContentNotAvailableException("Kick returned no content");
        }
        return body;
    }

    public static JsonObject object(final String url) throws IOException, ExtractionException {
        try {
            return JsonParser.object().from(body(url));
        } catch (final JsonParserException e) {
            throw new ParsingException("Invalid Kick API object", e);
        }
    }

    public static String manifest(final String url) throws IOException, ExtractionException {
        if (!KickUrl.isHttps(url)) {
            throw new ParsingException("Kick HLS URLs must use HTTPS");
        }
        final String playlist = body(url);
        if (!playlist.stripLeading().startsWith("#EXTM3U")) {
            throw new ParsingException("Kick returned an invalid HLS playlist");
        }
        return playlist;
    }

    public static JsonObject channel(final String id) throws IOException, ExtractionException {
        final JsonObject channel = object(KickUrl.BASE + "/api/v2/channels/" + KickUrl.slug(id));
        if (channel.isEmpty() || channel.getString("slug", "").isEmpty()) {
            throw new ContentNotAvailableException("This Kick channel does not exist");
        }
        KickUrl.slug(channel.getString("slug"));
        if (channel.getBoolean("is_banned")) {
            throw new ContentNotAvailableException("This Kick channel is banned");
        }
        return channel;
    }

    public static JsonArray videos(final JsonObject channel)
            throws IOException, ExtractionException {
        final JsonObject response = object(channelVideosUrl(channel));
        if (!(response.get("data") instanceof JsonArray)) {
            throw new ParsingException("Invalid Kick channel video list");
        }
        return response.getArray("data");
    }

    public static String channelVideosUrl(final JsonObject channel) throws ParsingException {
        final long id = channel.getLong("id", 0);
        if (id <= 0) {
            throw new ParsingException("Kick channel has no numeric ID");
        }
        return "https://web.kick.com/api/v1/channels/" + id + "/videos";
    }

    /**
     * Channel-scoped links try the current resource first, with legacy fallback only for
     * a missing resource. Older shared {@code video/} links use the legacy UUID API directly.
     */
    public static JsonObject video(final String streamId) throws IOException, ExtractionException {
        final String uuid = KickUrl.videoId(streamId);
        if (!streamId.startsWith("video/")) {
            final JsonObject channel = channel(streamId.substring(0, streamId.indexOf('/')));
            final JsonObject data;
            try {
                data = child(object(channelVideosUrl(channel) + "/" + uuid), "data");
            } catch (final NotFoundException e) {
                final JsonObject legacy = legacyVideo(uuid);
                if (!channel.getString("slug").equals(child(legacy, "channel").getString("slug"))) {
                    throw new ContentNotAvailableException(
                            "This Kick recording does not belong to this channel");
                }
                return legacy;
            }
            if (data.isEmpty()) {
                throw new ContentNotAvailableException("Kick returned no recording");
            }
            if (!uuid.equals(data.getString("id", ""))) {
                throw new ParsingException("Kick returned a different recording");
            }
            if (!channel.getString("slug").equals(child(data, "channel").getString("slug"))) {
                throw new ParsingException("Kick recording belongs to a different channel");
            }
            data.put("channel", channel);
            return data;
        }
        return legacyVideo(uuid);
    }

    private static JsonObject legacyVideo(final String uuid)
            throws IOException, ExtractionException {
        final JsonObject legacy = object(KickUrl.BASE + "/api/v1/video/" + uuid);
        if (legacy.isEmpty()) {
            throw new ContentNotAvailableException("Kick returned no recording");
        }
        if (!uuid.equals(legacy.getString("uuid", ""))) {
            throw new ParsingException("Kick returned a different legacy recording");
        }
        requirePublicPlayback(legacy, "recording");
        final JsonObject data = child(legacy, "livestream");
        data.put("id", data.getString("vod_id", uuid));
        data.put("title", title(data));
        data.put("duration", data.getLong("duration", 0) / 1000);
        data.put("recording_url", legacy.getString("source", ""));
        data.put("views", legacy.getLong("views", -1));
        final String status = legacy.getString("status", data.getString("status", ""));
        final String nestedStatus = data.getString("status", "");
        data.put("status", "private".equals(status) || "private".equals(nestedStatus)
                ? "private" : "sub_only".equals(status) || "sub_only".equals(nestedStatus)
                ? "sub_only" : status);
        data.put("is_private", legacy.getBoolean("is_private") || data.getBoolean("is_private"));
        data.put("is_pruned", legacy.getBoolean("is_pruned") || data.getBoolean("is_pruned"));
        data.put("deleted_at", legacy.get("deleted_at") == null
                ? data.get("deleted_at") : legacy.get("deleted_at"));
        return data;
    }

    public static JsonObject clip(final String clipId) throws IOException, ExtractionException {
        final String id = KickUrl.clipId(clipId);
        final JsonObject clip = child(object(KickUrl.BASE + "/api/v2/clips/" + id), "clip");
        if (clip.isEmpty()) {
            throw new ContentNotAvailableException("This Kick clip is unavailable");
        }
        if (!id.equals(clip.getString("id"))) {
            throw new ParsingException("Kick returned a different clip");
        }
        final JsonObject channel = child(clip, "channel");
        KickUrl.slug(channel.getString("slug", ""));
        if (channel.getLong("id", 0) <= 0) {
            throw new ParsingException("Kick clip has no channel identity");
        }
        return clip;
    }

    public static JsonObject clips(final long channelId, final String sort,
                                   final String timeRange, @Nullable final String cursor)
            throws IOException, ExtractionException {
        if (channelId <= 0) {
            throw new ParsingException("Kick channel has no numeric ID");
        }
        KickClipFilters.fromValues(sort, timeRange);
        final String url = "https://web.kick.com/api/v1/channels/" + channelId
                + "/clips?sort=" + sort + "&time=" + timeRange
                + (cursor == null || cursor.isBlank() ? ""
                : "&cursor=" + Utils.encodeUrlUtf8(cursor));
        final JsonObject data = child(object(url), "data");
        if (!(data.get("clips") instanceof JsonArray)) {
            throw new ParsingException("Kick clips response has no clip list");
        }
        return data;
    }

    public static JsonArray search(final String query, final int limit)
            throws IOException, ExtractionException {
        final JsonObject result = object("https://search.kick.com/api/v1/channels/search?query="
                + Utils.encodeUrlUtf8(query) + "&limit=" + limit);
        if (!(result.get("data") instanceof JsonArray)) {
            throw new ParsingException("Kick search response has no channel list");
        }
        return result.getArray("data");
    }

    public static JsonObject enrichedSearch(final String query)
            throws IOException, ExtractionException {
        return child(object("https://search.kick.com/api/v1/search/enriched?query="
                + Utils.encodeUrlUtf8(query)), "data");
    }

    public static String livestreamsUrl(final String language, final int limit,
                                       @Nullable final Long categoryId,
                                       @Nullable final String after) {
        final String normalized = language == null || language.isBlank()
                ? "en" : language.toLowerCase(Locale.ROOT);
        if (limit <= 0 || categoryId != null && categoryId <= 0) {
            throw new IllegalArgumentException("Kick discovery requires positive limit/category");
        }
        return "https://web.kick.com/api/v1/livestreams?limit=" + limit
                + "&sort=featured_home&language=" + Utils.encodeUrlUtf8(normalized)
                + (categoryId == null ? "" : "&category_id=" + categoryId)
                + (after == null || after.isBlank() ? "" : "&after=" + Utils.encodeUrlUtf8(after));
    }

    public static JsonObject livestreams(final String language, final int limit,
                                        @Nullable final Long categoryId,
                                        @Nullable final String after)
            throws IOException, ExtractionException {
        final JsonObject data = child(object(livestreamsUrl(language, limit, categoryId, after)),
                "data");
        if (!(data.get("livestreams") instanceof JsonArray)) {
            throw new ParsingException("Kick discovery response has no livestream list");
        }
        return data;
    }

    public static JsonObject child(final JsonObject value, final String key) {
        final JsonObject child = value.getObject(key);
        return child == null ? new JsonObject() : child;
    }

    public static String name(final JsonObject channel) {
        final Object user = channel.get("user");
        final JsonObject source = user instanceof JsonObject ? (JsonObject) user : channel;
        return source.getString("username", channel.getString("slug", ""));
    }

    public static String avatar(final JsonObject channel) {
        final Object user = channel.get("user");
        final JsonObject source = user instanceof JsonObject ? (JsonObject) user : channel;
        return source.getString("profile_pic",
                source.getString("profilepic", source.getString("profile_picture", "")));
    }

    public static String biography(final JsonObject channel) {
        final JsonObject user = channel.getObject("user");
        return user == null ? "" : user.getString("bio", "");
    }

    public static List<Image> images(final String url) {
        if (!KickUrl.isHttps(url)) {
            return List.of();
        }
        int width = Image.WIDTH_UNKNOWN;
        int height = Image.HEIGHT_UNKNOWN;
        Image.ResolutionLevel quality = Image.ResolutionLevel.UNKNOWN;
        if (url.startsWith("https://images.kick.com/video_thumbnails/")) {
            final Matcher thumbnail = THUMBNAIL_HEIGHT.matcher(url);
            if (thumbnail.matches()) {
                // The filename encodes height; Kick's srcset width descriptors can be wrong.
                height = Integer.parseInt(thumbnail.group(1));
                quality = Image.ResolutionLevel.fromHeight(height);
            }
        } else if (url.startsWith("https://files.kick.com/images/user/")) {
            final Matcher avatar = AVATAR_WIDTH.matcher(url);
            if (avatar.matches()) {
                // Conversion widths are fixed, but portrait avatars are not square.
                width = "medium".equals(avatar.group(1)) ? 150 : 350;
            }
        } else if (url.startsWith("https://web.kick.com/api/v1/videos/")
                && VOD_PREVIEW.matcher(url).matches()) {
            // This endpoint serves HD previews without providing intrinsic dimensions.
            quality = Image.ResolutionLevel.HIGH;
        }
        return List.of(new Image(url, height, width, quality));
    }

    public static String image(final JsonObject item, final String field) {
        final Object image = item.get(field);
        if (image instanceof String) {
            return (String) image;
        }
        if (image instanceof JsonObject) {
            return ((JsonObject) image).getString("src", ((JsonObject) image).getString("url", ""));
        }
        return "";
    }

    public static List<Image> thumbnails(final JsonObject item) {
        final Set<String> urls = new LinkedHashSet<>();
        final Object thumbnail = item.get("thumbnail");
        if (thumbnail instanceof JsonObject) {
            final JsonObject image = (JsonObject) thumbnail;
            thumbnailCandidates(urls, image.get("srcSet"));
            thumbnailCandidates(urls, image.get("srcset"));
            urls.add(image.getString("src", ""));
            urls.add(image.getString("url", ""));
        } else if (thumbnail instanceof String) {
            urls.add((String) thumbnail);
        }
        urls.add(item.getString("thumbnail_url", ""));
        final List<Image> result = new ArrayList<>();
        for (final String url : urls) {
            result.addAll(images(url));
        }
        return result;
    }

    private static void thumbnailCandidates(final Set<String> urls, final Object candidates) {
        if (candidates instanceof String) {
            for (final String candidate : ((String) candidates).split(",")) {
                final String url = candidate.trim().split("\\s+", 2)[0];
                if (!url.isEmpty()) {
                    urls.add(url);
                }
            }
        } else if (candidates instanceof JsonArray) {
            for (final Object candidate : (JsonArray) candidates) {
                thumbnailCandidates(urls, candidate);
            }
        }
    }

    public static String category(final JsonObject item) {
        final String singular = child(item, "category").getString("name", "").trim();
        if (!singular.isEmpty()) {
            return singular;
        }
        final JsonArray categories = item.getArray("categories");
        if (categories != null) {
            for (final Object category : categories) {
                if (category instanceof JsonObject) {
                    final String name = ((JsonObject) category).getString("name", "").trim();
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
        }
        return "";
    }

    public static List<String> tags(final JsonObject item) {
        final Set<String> result = new LinkedHashSet<>();
        final JsonArray tags = item.getArray("tags");
        if (tags != null) {
            for (final Object tag : tags) {
                if (tag instanceof String && !((String) tag).isBlank()) {
                    result.add(((String) tag).trim());
                }
            }
        }
        return List.copyOf(result);
    }

    @Nullable
    public static Locale language(final JsonObject item) {
        final String language = item.getString("language", "").trim();
        try {
            final Locale locale = new Locale.Builder().setLanguageTag(language).build();
            return locale.getLanguage().isEmpty() || "und".equals(locale.getLanguage())
                    ? null : locale;
        } catch (final IllformedLocaleException e) {
            return null;
        }
    }

    public static ContentAvailability availability(final JsonObject item) {
        if (unavailable(item) || privateContent(item)) {
            return ContentAvailability.UNKNOWN;
        }
        final String status = item.getString("status", "");
        final String privacy = item.getString("privacy", "");
        if ("sub_only".equals(status) || "sub_only".equals(privacy)) {
            return ContentAvailability.MEMBERSHIP;
        }
        return "public".equals(status) || "public".equals(privacy) ? ContentAvailability.AVAILABLE
                : ContentAvailability.UNKNOWN;
    }

    public static boolean listable(final JsonObject item) {
        return !unavailable(item) && !privateContent(item)
                && !(availability(item) == ContentAvailability.MEMBERSHIP
                && KickSettings.getInstance().isSettingEnabled(KickSettings.HIDE_PAID_STREAMS));
    }

    /** Explicit restrictions take precedence over a retained media URL or public alias. */
    public static void requirePublicPlayback(final JsonObject item, final String kind)
            throws ContentNotAvailableException {
        if (item.isEmpty() || unavailable(item)) {
            throw new ContentNotAvailableException("This Kick " + kind + " is unavailable");
        }
        if (privateContent(item)) {
            throw new PrivateContentException("This Kick " + kind + " is private");
        }
        if (availability(item) == ContentAvailability.MEMBERSHIP) {
            throw new PaidContentException("This Kick " + kind
                    + " requires a channel subscription");
        }
    }

    private static boolean unavailable(final JsonObject item) {
        final String status = item.getString("status", "");
        return item.getBoolean("is_banned") || item.getBoolean("is_pruned")
                || item.getBoolean("is_deleted") || item.get("deleted_at") != null
                || "deleted".equals(status) || "pruned".equals(status) || "banned".equals(status);
    }

    private static boolean privateContent(final JsonObject item) {
        return item.getBoolean("is_private") || "private".equals(item.getString("status"))
                || "private".equals(item.getString("privacy"));
    }

    public static long followers(final JsonObject channel) {
        final Object count = channel.get("follower_count") == null
                ? channel.getOrDefault("followersCount", channel.get("followers_count"))
                : channel.get("follower_count");
        if (count instanceof Number) {
            return ((Number) count).longValue();
        }
        if (count instanceof String) {
            try {
                return Long.parseLong((String) count);
            } catch (final NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    public static boolean verified(final JsonObject channel) {
        final Object value = channel.get("verified");
        return Boolean.TRUE.equals(value) || value instanceof JsonObject
                || channel.getBoolean("is_verified");
    }

    public static long viewers(final JsonObject live) {
        // Legacy channel metadata omits visibility. Never infer consent from a numeric count.
        return live.getBoolean("show_view_count", false) ? live.getLong("viewer_count", -1) : -1;
    }

    public static String title(final JsonObject stream) {
        return stream.getString("session_title", stream.getString("title", ""));
    }

    public static long startTime(final JsonObject stream) throws ParsingException {
        final DateWrapper date = date(
                stream.getString("start_time", stream.getString("created_at")));
        if (date == null) {
            throw new ParsingException("Kick broadcast has no start time");
        }
        return date.offsetDateTime().toEpochSecond();
    }

    public static String liveUrl(final JsonObject channel, final JsonObject stream)
            throws ParsingException {
        return KickUrl.BASE + "/" + KickUrl.slug(channel.getString("slug", ""))
                + "?live=" + startTime(stream);
    }

    public static DateWrapper date(final String value) throws ParsingException {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.indexOf('T') >= 0) {
            return DateWrapper.fromOffsetDateTime(value);
        }
        try {
            return new DateWrapper(LocalDateTime.parse(value.replace(' ', 'T'))
                    .toInstant(ZoneOffset.UTC));
        } catch (final DateTimeParseException e) {
            throw new ParsingException("Invalid Kick stream date", e);
        }
    }

    private static final class NotFoundException extends ContentNotAvailableException {
        private NotFoundException() {
            super("This Kick content does not exist");
        }
    }
}
