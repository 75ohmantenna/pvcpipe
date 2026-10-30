package org.schabi.newpipe.extractor.services.kick;

import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates public Kick links before any of their parts enter an API request. */
public final class KickUrl {
    public static final String BASE = "https://kick.com";
    private static final Pattern SLUG = Pattern.compile("@?[a-zA-Z0-9_][a-zA-Z0-9_-]{0,99}");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern LIVE_TIME = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern VOD_TIME = Pattern.compile("[0-9]+");
    private static final Pattern CLIP = Pattern.compile("clip_[A-Za-z0-9_-]+");
    private static final Set<String> RESERVED = Set.of("api", "search", "categories", "category",
            "browse", "following", "subscriptions", "settings", "dashboard", "login", "signup",
            "register", "auth", "logout", "clips", "video", "videos", "terms-of-service",
            "privacy-policy", "community-guidelines", "dmca-policy", "about", "jobs", "help",
            "support", "download", "payments", "moderator", "embed");

    private KickUrl() {
    }

    public static String slug(final String value) throws ParsingException {
        if (value == null || !SLUG.matcher(value).matches()
                || RESERVED.contains(value.toLowerCase(Locale.ROOT))) {
            throw new ParsingException("Invalid Kick channel slug");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public static String uuid(final String value) throws ParsingException {
        if (value == null || !UUID.matcher(value).matches()) {
            throw new ParsingException("Invalid Kick video UUID");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public static String clipId(final String value) throws ParsingException {
        if (value == null || !CLIP.matcher(value).matches()) {
            throw new ParsingException("Invalid Kick clip ID");
        }
        return value;
    }

    public static String clipUrl(final String channelSlug, final String id)
            throws ParsingException {
        return BASE + "/" + slug(channelSlug) + "/clips/" + clipId(id);
    }

    private static URI parse(final String url) throws ParsingException {
        try {
            final URI uri = new URI(url);
            if (!("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    || !("kick.com".equalsIgnoreCase(uri.getHost())
                    || "www.kick.com".equalsIgnoreCase(uri.getHost()))
                    || uri.getRawUserInfo() != null || uri.getPort() != -1) {
                throw new ParsingException("Not a public Kick URL");
            }
            final Map<String, String> selectors = selectors(uri);
            // Never decode encoded separators, dots or other ambiguous path characters.
            final String path = uri.getRawPath();
            if (path == null || !path.startsWith("/")) {
                throw new ParsingException("Missing Kick URL path");
            }
            final String[] parts = parts(uri);
            if (!selectors.isEmpty() && parts.length != 1) {
                throw new ParsingException("Kick selectors require a channel root URL");
            }
            return uri;
        } catch (final URISyntaxException | NullPointerException | IllegalArgumentException e) {
            throw new ParsingException("Invalid Kick URL", e);
        }
    }

    private static String[] parts(final URI uri) {
        // Keep encoded path characters encoded, so they cannot become separators.
        final String path = uri.getRawPath();
        final String trimmed = path.length() > 1 && path.endsWith("/")
                ? path.substring(1, path.length() - 1) : path.substring(1);
        return trimmed.split("/", -1);
    }

    private static String decode(final String value) throws ParsingException {
        try {
            final String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (decoded.indexOf('\uFFFD') >= 0) {
                throw new ParsingException("Invalid Kick query encoding");
            }
            return decoded;
        } catch (final IllegalArgumentException e) {
            throw new ParsingException("Invalid Kick query encoding", e);
        }
    }

    private static Map<String, String> selectors(final URI uri) throws ParsingException {
        final Map<String, String> result = new HashMap<>();
        final String query = uri.getRawQuery();
        if (query == null) {
            return result;
        }
        // Decode each pair independently: encoded ampersands never create selectors.
        for (final String parameter : query.split("&", -1)) {
            final String[] pair = parameter.split("=", 2);
            final String key = decode(pair[0]).toLowerCase(Locale.ROOT);
            final String value = pair.length == 2 ? decode(pair[1]) : "";
            if ("clipid".equals(key)) {
                throw new ParsingException("Unsupported Kick clip selector");
            }
            if ("clip".equals(key) || "live".equals(key)) {
                if (value.isEmpty() || result.putIfAbsent(key, value) != null) {
                    throw new ParsingException("Invalid or duplicate Kick selector");
                }
                if ("clip".equals(key)) {
                    clipId(value);
                } else {
                    parseLiveTime(value);
                }
            }
        }
        if (result.size() > 1) {
            throw new ParsingException("Conflicting Kick clip and live selectors");
        }
        return result;
    }

    private static long parseLiveTime(final String value) throws ParsingException {
        if (!LIVE_TIME.matcher(value).matches()) {
            throw new ParsingException("Invalid Kick live broadcast timestamp");
        }
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            throw new ParsingException("Invalid Kick live broadcast timestamp", e);
        }
    }

    public static long liveTime(final String url) throws ParsingException {
        return liveTime(parse(url));
    }

    private static long liveTime(final URI uri) throws ParsingException {
        final String value = selectors(uri).get("live");
        return value == null ? 0 : parseLiveTime(value);
    }

    /** Reads the optional website seek offset without decoding query parameter boundaries. */
    public static long vodTime(final String url) throws ParsingException {
        final String query = parse(url).getRawQuery();
        if (query == null) {
            return 0;
        }
        for (final String parameter : query.split("&")) {
            final String[] pair = parameter.split("=", 2);
            if ("t".equals(decode(pair[0]))) {
                if (pair.length != 2) {
                    return 0;
                }
                final String value = decode(pair[1]);
                if (!VOD_TIME.matcher(value).matches()) {
                    return 0;
                }
                try {
                    final long seconds = Long.parseLong(value);
                    // Playback converts seconds to both milliseconds and microseconds.
                    return seconds <= Long.MAX_VALUE / 1_000_000 ? seconds : 0;
                } catch (final NumberFormatException e) {
                    // An invalid optional seek must not make otherwise playable content fail.
                    return 0;
                }
            }
        }
        return 0;
    }

    public static String channelId(final String url) throws ParsingException {
        final URI uri = parse(url);
        if (selectors(uri).containsKey("clip")) {
            throw new ParsingException("A Kick clip is not a channel");
        }
        final String[] parts = parts(uri);
        if (parts.length == 1 || parts.length == 2
                && ("videos".equals(parts[1]) || "about".equals(parts[1])
                || "clips".equals(parts[1]))) {
            return slug(parts[0]);
        }
        throw new ParsingException("Not a Kick channel URL");
    }

    public static String streamId(final String url) throws ParsingException {
        final URI uri = parse(url);
        final String[] parts = parts(uri);
        final long timestamp = liveTime(uri);
        final String clip = selectors(uri).get("clip");
        if (clip != null) {
            return slug(parts[0]) + "/clips/" + clipId(clip);
        }
        if (parts.length == 1) {
            return slug(parts[0]) + (timestamp == 0 ? "" : "?live=" + timestamp);
        }
        if (timestamp != 0) {
            throw new ParsingException("A recording URL cannot carry a live broadcast identity");
        }
        if (parts.length == 2 && "video".equals(parts[0])) {
            return "video/" + uuid(parts[1]);
        }
        if (parts.length == 3 && "videos".equals(parts[1])) {
            return slug(parts[0]) + "/videos/" + uuid(parts[2]);
        }
        if (parts.length == 3 && "clips".equals(parts[1])) {
            return slug(parts[0]) + "/clips/" + clipId(parts[2]);
        }
        throw new ParsingException("Not a Kick stream URL");
    }

    public static String streamUrl(final String id) throws ParsingException {
        return BASE + "/" + streamId(BASE + "/" + id);
    }

    public static boolean isVod(final String id) {
        if (id == null) {
            return false;
        }
        final String[] parts = id.split("/", -1);
        try {
            if (parts.length == 2 && "video".equals(parts[0])) {
                uuid(parts[1]);
                return true;
            }
            if (parts.length == 3 && "videos".equals(parts[1])) {
                slug(parts[0]);
                uuid(parts[2]);
                return true;
            }
        } catch (final ParsingException ignored) {
            return false;
        }
        return false;
    }

    public static boolean isClip(final String id) {
        if (id == null) {
            return false;
        }
        final String[] parts = id.split("/", -1);
        try {
            if (parts.length == 3 && "clips".equals(parts[1])) {
                slug(parts[0]);
                clipId(parts[2]);
                return true;
            }
        } catch (final ParsingException ignored) {
            return false;
        }
        return false;
    }

    public static String videoId(final String id) throws ParsingException {
        return uuid(id.substring(id.lastIndexOf('/') + 1));
    }

    public static boolean isHttps(final String url) {
        try {
            final URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getPort() == -1;
        } catch (final URISyntaxException | NullPointerException e) {
            return false;
        }
    }
}
