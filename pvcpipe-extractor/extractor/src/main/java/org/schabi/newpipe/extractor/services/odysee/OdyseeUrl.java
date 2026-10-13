package org.schabi.newpipe.extractor.services.odysee;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.utils.Utils;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

/**
 * Translates between the three ways Odysee names a claim: the website address
 * ({@code https://odysee.com/@channel:c/video:v}), the LBRY URI the API wants
 * ({@code lbry://@channel#c/video#v}) and the id the extractor keeps, which is the website path
 * without its leading slash ({@code @channel:c/video:v}).
 */
public final class OdyseeUrl {

    public static final String BASE_URL = "https://odysee.com";

    public enum Type {
        STREAM, CHANNEL, PLAYLIST
    }

    private static final Set<String> HOSTS =
            Set.of("odysee.com", "www.odysee.com", "lbry.tv", "open.lbry.com");
    private static final String LBRY_SCHEME = "lbry://";

    // The characters the LBRY protocol does not allow in a claim name.
    private static final String NAME = "[^=&#:$@%?;/\\\\\"<>{}|^~\\[\\]`\\s]+";
    private static final Pattern SEGMENT =
            Pattern.compile("(@?" + NAME + ")(?:[:#]([0-9a-fA-F]{1,40}))?");
    private static final Pattern CLAIM_ID = Pattern.compile("[0-9a-f]{40}");
    private static final String UNRESERVED = "-._~!'()*,@:";

    private final Type type;
    private final String id;

    private OdyseeUrl(final Type type, final String id) {
        this.type = type;
        this.id = id;
    }

    public Type getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    /**
     * Reads a website address.
     *
     * @param urlString an address on one of the Odysee hosts
     * @return what the address points at and its id
     * @throws ParsingException if the address is not a video, channel or playlist address
     */
    @Nonnull
    public static OdyseeUrl parse(final String urlString) throws ParsingException {
        final URL url;
        try {
            url = Utils.stringToURL(urlString);
        } catch (final MalformedURLException e) {
            throw new ParsingException("The given URL is not valid: " + urlString, e);
        }
        if (!Utils.isHTTP(url) || !HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))) {
            throw new ParsingException("URL is not hosted by Odysee: " + urlString);
        }

        final List<String> segments = new ArrayList<>();
        for (final String rawSegment : url.getPath().split("/")) {
            if (!rawSegment.isEmpty()) {
                segments.add(decode(rawSegment));
            }
        }
        if (segments.isEmpty()) {
            throw new ParsingException("URL has no path: " + urlString);
        }

        if ("$".equals(segments.get(0))) {
            return parseSiteRoute(segments, urlString);
        }
        return parseClaimPath(segments, urlString);
    }

    private static OdyseeUrl parseSiteRoute(final List<String> segments, final String urlString)
            throws ParsingException {
        final String route = segments.size() > 1 ? segments.get(1) : "";
        final List<String> rest = segments.subList(Math.min(2, segments.size()), segments.size());
        switch (route) {
            case "playlist":
            case "list":
                if (rest.size() == 1 && CLAIM_ID.matcher(rest.get(0)).matches()) {
                    return new OdyseeUrl(Type.PLAYLIST, rest.get(0));
                }
                break;
            case "embed":
            case "download":
                // The older form of these links is /name/claimId in place of /name:claimId.
                if (rest.size() == 2 && !rest.get(0).startsWith("@")) {
                    return new OdyseeUrl(Type.STREAM,
                            normalize(rest.get(0) + ":" + rest.get(1), urlString));
                }
                if (!rest.isEmpty()) {
                    final OdyseeUrl claim = parseClaimPath(rest, urlString);
                    if (claim.type == Type.STREAM) {
                        return claim;
                    }
                }
                break;
            default:
                break;
        }
        throw new ParsingException("Unsupported Odysee URL: " + urlString);
    }

    private static OdyseeUrl parseClaimPath(final List<String> segments, final String urlString)
            throws ParsingException {
        if (segments.size() == 1) {
            final String claim = normalize(segments.get(0), urlString);
            return new OdyseeUrl(claim.startsWith("@") ? Type.CHANNEL : Type.STREAM, claim);
        }
        if (segments.size() == 2 && segments.get(0).startsWith("@")
                && !segments.get(1).startsWith("@")) {
            return new OdyseeUrl(Type.STREAM, normalize(segments.get(0), urlString)
                    + "/" + normalize(segments.get(1), urlString));
        }
        throw new ParsingException("Unsupported Odysee URL: " + urlString);
    }

    private static String normalize(final String segment, final String urlString)
            throws ParsingException {
        final Matcher matcher = SEGMENT.matcher(segment);
        if (!matcher.matches()) {
            throw new ParsingException("Unsupported Odysee URL: " + urlString);
        }
        return matcher.group(2) == null ? matcher.group(1)
                : matcher.group(1) + ":" + matcher.group(2).toLowerCase(Locale.ROOT);
    }

    private static String decode(final String segment) {
        try {
            // URLDecoder reads "+" as a space, which is only right in a query string.
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            return segment;
        }
    }

    /**
     * @param id a channel or stream id, such as {@code @channel:c/video:v}
     * @return the website address for it
     */
    @Nonnull
    public static String toWebUrl(@Nonnull final String id) {
        final StringBuilder url = new StringBuilder(BASE_URL);
        for (final String segment : id.split("/")) {
            url.append('/').append(encode(segment));
        }
        return url.toString();
    }

    /**
     * @param id a channel or stream id, such as {@code @channel:c/video:v}
     * @return the LBRY URI the API resolves
     */
    @Nonnull
    public static String toLbryUri(@Nonnull final String id) {
        return LBRY_SCHEME + id.replace(':', '#');
    }

    /**
     * @param lbryUri a URI the API returned, such as {@code lbry://@channel#c/video#v}
     * @return the website address for it
     */
    @Nonnull
    public static String lbryUriToWebUrl(@Nonnull final String lbryUri) {
        final String path = lbryUri.startsWith(LBRY_SCHEME)
                ? lbryUri.substring(LBRY_SCHEME.length()) : lbryUri;
        return toWebUrl(path.replace('#', ':'));
    }

    @Nonnull
    public static String playlistUrl(@Nonnull final String claimId) {
        return BASE_URL + "/$/playlist/" + claimId;
    }

    private static String encode(final String segment) {
        final StringBuilder out = new StringBuilder();
        for (final byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            final char c = (char) (b & 0xff);
            if (c < 0x80 && (Character.isLetterOrDigit(c) || UNRESERVED.indexOf(c) >= 0)) {
                out.append(c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
            }
        }
        return out.toString();
    }
}
