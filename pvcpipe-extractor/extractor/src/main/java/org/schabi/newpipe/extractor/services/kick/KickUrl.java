package org.schabi.newpipe.extractor.services.kick;

import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
            final String query = uri.getRawQuery();
            if (query != null) {
                for (final String parameter : query.split("&")) {
                    final String key = URLDecoder.decode(parameter.split("=", 2)[0],
                            StandardCharsets.UTF_8);
                    if ("clip".equalsIgnoreCase(key) || "clipId".equalsIgnoreCase(key)) {
                        throw new ParsingException("Kick clips are not supported");
                    }
                }
            }
            // Never decode encoded separators, dots or other ambiguous path characters.
            final String path = uri.getRawPath();
            if (path == null || !path.startsWith("/")) {
                throw new ParsingException("Missing Kick URL path");
            }
            return uri;
        } catch (final URISyntaxException | NullPointerException e) {
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

    public static long liveTime(final String url) throws ParsingException {
        return liveTime(parse(url));
    }

    private static long liveTime(final URI uri) throws ParsingException {
        long timestamp = 0;
        final String query = uri.getRawQuery();
        if (query == null) {
            return timestamp;
        }
        for (final String parameter : query.split("&")) {
            final String[] pair = parameter.split("=", 2);
            final String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            if ("live".equalsIgnoreCase(key)) {
                if (timestamp != 0 || pair.length != 2) {
                    throw new ParsingException("Invalid Kick live broadcast identity");
                }
                final String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (!LIVE_TIME.matcher(value).matches()) {
                    throw new ParsingException("Invalid Kick live broadcast timestamp");
                }
                try {
                    timestamp = Long.parseLong(value);
                } catch (final NumberFormatException e) {
                    throw new ParsingException("Invalid Kick live broadcast timestamp", e);
                }
            }
        }
        return timestamp;
    }

    /** Reads the optional website seek offset without decoding query parameter boundaries. */
    public static long vodTime(final String url) throws ParsingException {
        final String query = parse(url).getRawQuery();
        if (query == null) {
            return 0;
        }
        for (final String parameter : query.split("&")) {
            final String[] pair = parameter.split("=", 2);
            if ("t".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                if (pair.length != 2) {
                    return 0;
                }
                final String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
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
        liveTime(uri);
        final String[] parts = parts(uri);
        if (parts.length == 1 || parts.length == 2
                && ("videos".equals(parts[1]) || "about".equals(parts[1]))) {
            return slug(parts[0]);
        }
        throw new ParsingException("Not a Kick channel URL");
    }

    public static String streamId(final String url) throws ParsingException {
        final URI uri = parse(url);
        final String[] parts = parts(uri);
        final long timestamp = liveTime(uri);
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
        throw new ParsingException("Not a Kick stream URL");
    }

    public static String streamUrl(final String id) throws ParsingException {
        return BASE + "/" + streamId(BASE + "/" + id);
    }

    public static boolean isVod(final String id) {
        return id.indexOf('/') >= 0;
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
