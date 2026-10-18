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
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class KickApi {
    public static final String FEATURED =
            "https://web.kick.com/api/v1/livestreams/featured?language=en";
    private static final Map<String, List<String>> HEADERS = Map.of(
            "Accept", List.of("application/json"),
            "Referer", List.of(KickUrl.BASE + "/"),
            "Origin", List.of(KickUrl.BASE));

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
            throw new ContentNotAvailableException("This Kick content does not exist or expired");
        }
        if (status < 200 || status >= 300) {
            throw new IOException("Kick API returned HTTP " + status);
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
            throw new ParsingException("Invalid Kick HLS URL");
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
     * Older shared links contain the legacy UUID; the current resource uses a new UUID.
     * Try the channel-scoped resource first, and only a missing resource permits the legacy API.
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
        final JsonObject data = child(legacy, "livestream");
        data.put("id", data.getString("vod_id", uuid));
        data.put("title", title(data));
        data.put("duration", data.getLong("duration", 0) / 1000);
        data.put("recording_url", legacy.getString("source", ""));
        data.put("views", legacy.getLong("views", -1));
        data.put("status", legacy.getString("status", ""));
        data.put("is_private", legacy.getBoolean("is_private"));
        data.put("is_pruned", legacy.getBoolean("is_pruned"));
        data.put("deleted_at", legacy.get("deleted_at"));
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
        return KickUrl.isHttps(url) ? List.of(new Image(url, Image.HEIGHT_UNKNOWN,
                Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN)) : List.of();
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
