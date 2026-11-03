package org.schabi.newpipe.extractor.services.rumble;

import com.grack.nanojson.JsonObject;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/** Resolves public Rumble identities without retaining metadata or playback URLs. */
public final class RumbleMediaResolver {
    private static final int CACHE_SIZE = 64;
    private static final Map<String, Long> VIDEO_IDS_CACHE =
            new LinkedHashMap<String, Long>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Long> eldest) {
                    return size() > CACHE_SIZE;
                }
            };
    private static final String DETAILS_OPTIONS =
            "video.full,extended,with_cc,categories,comments,video.related_video,video.dthumbs";
    private static final Pattern EMBED_ID = Pattern.compile("v[0-9a-zA-Z]+");
    private static final Pattern FULL_WATCH_PATH = Pattern.compile("/v[0-9a-zA-Z]+-.+");

    private RumbleMediaResolver() {
    }

    public static ResolvedMedia resolve(final Downloader downloader, final LinkHandler link,
                                        @Nullable final Localization localization)
            throws IOException, ReCaptchaException, ParsingException {
        return resolve(downloader, link, localization, false);
    }

    public static String resolveEmbedId(final Downloader downloader, final LinkHandler link,
                                        @Nullable final Localization localization)
            throws IOException, ReCaptchaException, ParsingException {
        return resolve(downloader, link, localization, true).getEmbedId();
    }

    private static ResolvedMedia resolve(final Downloader downloader, final LinkHandler link,
                                         @Nullable final Localization localization,
                                         final boolean identityOnly)
            throws IOException, ReCaptchaException, ParsingException {
        final String canonical = link.getUrl();
        if (canonical.startsWith(RumbleStreamLinkHandlerFactory.BASE_URL + "/embed/")) {
            return new ResolvedMedia(decodeEmbedId(link.getId()), link.getId(), null, null, null);
        }
        final String original = stripFragment(link.getOriginalUrl());
        final boolean shorts = canonical.startsWith(
                RumbleStreamLinkHandlerFactory.BASE_URL + "/shorts/");
        final String publicInput = shorts ? canonical : original;
        final Long cached;
        synchronized (VIDEO_IDS_CACHE) {
            final Long exact = VIDEO_IDS_CACHE.get(publicInput);
            cached = exact == null ? VIDEO_IDS_CACHE.get(canonical) : exact;
        }
        if (cached != null && identityOnly) {
            return new ResolvedMedia(cached, embedId(cached), null, null, null);
        }
        if (cached != null || shorts || isFullWatch(publicInput)) {
            final Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("api", "8");
            parameters.put(cached == null ? "url" : "id",
                    cached == null ? publicInput : Long.toString(cached));
            parameters.put("options", DETAILS_OPTIONS);
            final JsonObject details = RumbleApi.fetchData(downloader,
                    RumbleApi.serviceUrl("media.details", parameters), localization);
            final long numericId = positiveId(details);
            if (cached != null && cached != numericId) {
                throw new ParsingException(
                        "Rumble media.details ID does not match cached identity");
            }
            final String returnedUrl = validateVideo(details, link.getId());
            checkPremium(details);
            register(numericId, publicInput, canonical, returnedUrl);
            final String durableUrl = returnedUrl != null && isDurable(returnedUrl)
                    ? returnedUrl : isDurable(publicInput) ? publicInput : null;
            return new ResolvedMedia(numericId, embedId(numericId), durableUrl, details, null);
        }

        // Cold legacy bare links retain the existing single HTML request and challenge handling.
        final Document document = RumbleParsingHelper.fetchParseValidate(
                downloader, canonical, null, localization);
        final String internalId = RumbleParsingHelper.extractEmbedVideoId(
                canonical, document.toString());
        final long numericId = decodeEmbedId(internalId);
        String publicUrl = null;
        for (final Element element : document.select("link[rel=canonical][href]")) {
            final String candidate = element.absUrl("href");
            try {
                final LinkHandler publicLink = publicLink(candidate);
                if (link.getId().equals(publicLink.getId()) && isFullWatch(candidate)) {
                    publicUrl = stripFragment(candidate);
                    break;
                }
            } catch (final ParsingException ignored) {
                // An unrelated or malformed canonical link cannot change the public identity.
            }
        }
        register(numericId, publicInput, canonical, publicUrl);
        return new ResolvedMedia(numericId, internalId, publicUrl, null, document);
    }

    public static void rememberVideo(final JsonObject video) throws ParsingException {
        if (!video.containsKey("id")) {
            return;
        }
        final long numericId = positiveId(video);
        final String publicUrl = validateVideo(video, null);
        if (publicUrl == null) {
            throw new ParsingException("Rumble listing has no public URL");
        }
        final LinkHandler link = publicLink(publicUrl);
        register(numericId, publicUrl, link.getUrl());
    }

    private static long positiveId(final JsonObject object) throws ParsingException {
        final Number number = JsonUtils.getNumber(object, "id");
        try {
            final long value = number instanceof Long || number instanceof Integer
                    ? number.longValue() : new BigDecimal(number.toString()).longValueExact();
            if (value > 0) {
                return value;
            }
        } catch (final ArithmeticException | NumberFormatException e) {
            throw new ParsingException("Invalid Rumble numeric media ID: " + number, e);
        }
        throw new ParsingException("Rumble numeric media ID must be positive: " + number);
    }

    private static long decodeEmbedId(final String id) throws ParsingException {
        try {
            if (id == null || !EMBED_ID.matcher(id).matches()) {
                throw new NumberFormatException("Invalid internal ID");
            }
            final long numericId = Long.parseLong(id.substring(1), 36);
            if (numericId > 0) {
                return numericId;
            }
        } catch (final NumberFormatException e) {
            throw new ParsingException("Invalid Rumble embed ID: " + id, e);
        }
        throw new ParsingException("Rumble embed ID must be positive: " + id);
    }

    private static String embedId(final long numericId) {
        return "v" + Long.toString(numericId, 36);
    }

    @Nullable
    private static String validateVideo(final JsonObject video, @Nullable final String expectedId)
            throws ParsingException {
        String publicUrl = null;
        String publicId = expectedId;
        if (video.containsKey("url")) {
            publicUrl = stripFragment(JsonUtils.getString(video, "url"));
            final LinkHandler returned = publicLink(publicUrl);
            if (publicId != null && !publicId.equals(returned.getId())) {
                throw new ParsingException("Rumble response public URL does not match input");
            }
            publicId = returned.getId();
        }
        if (video.containsKey("permalink_id")) {
            final String permalinkId = JsonUtils.getString(video, "permalink_id");
            final String validatedId = RumbleStreamLinkHandlerFactory.getInstance()
                    .getId(RumbleStreamLinkHandlerFactory.BASE_URL + "/" + permalinkId);
            if (!permalinkId.equals(validatedId)
                    || publicId != null && !publicId.equals(permalinkId)) {
                throw new ParsingException("Rumble response permalink does not match public URL");
            }
        } else if (publicUrl == null) {
            throw new ParsingException("Rumble response has no public identity");
        }
        return publicUrl;
    }

    private static LinkHandler publicLink(final String url) throws ParsingException {
        final LinkHandler link = RumbleStreamLinkHandlerFactory.getInstance().fromUrl(url);
        if (link.getUrl().contains("/embed/")) {
            throw new ParsingException("Expected Rumble public watch or Short URL, not embed URL");
        }
        return link;
    }

    private static void checkPremium(final JsonObject details) throws PaidContentException {
        if ("premium".equals(details.getString("availability"))
                && !Boolean.TRUE.equals(details.get("has_access_to_premium"))) {
            throw new PaidContentException("Rumble video requires premium access");
        }
    }

    private static void register(final long numericId, final String... urls)
            throws ParsingException {
        final Set<String> aliases = new LinkedHashSet<>();
        for (final String url : urls) {
            if (url != null) {
                final String full = stripFragment(url);
                final LinkHandler link = publicLink(full);
                aliases.add(full);
                aliases.add(link.getUrl());
            }
        }
        synchronized (VIDEO_IDS_CACHE) {
            // Check every alias before modifying any, including when two requests race.
            for (final String alias : aliases) {
                final Long previous = VIDEO_IDS_CACHE.get(alias);
                if (previous != null && previous != numericId) {
                    throw new ParsingException("Conflicting Rumble numeric identity for " + alias);
                }
            }
            for (final String alias : aliases) {
                VIDEO_IDS_CACHE.put(alias, numericId);
            }
        }
    }

    private static String stripFragment(final String url) {
        final int fragment = url.indexOf('#');
        return fragment < 0 ? url : url.substring(0, fragment);
    }

    private static boolean isFullWatch(final String url) {
        final String path = URI.create(url).getPath();
        return path != null && FULL_WATCH_PATH.matcher(path).matches();
    }

    private static boolean isDurable(final String url) {
        return URI.create(url).getPath().startsWith("/shorts/") || isFullWatch(url);
    }

    public static final class ResolvedMedia {
        private final long numericId;
        private final String embedId;
        @Nullable
        private final String publicUrl;
        @Nullable
        private final JsonObject details;
        @Nullable
        private final Document document;

        private ResolvedMedia(final long numericId, final String embedId,
                              @Nullable final String publicUrl, @Nullable final JsonObject details,
                              @Nullable final Document document) {
            this.numericId = numericId;
            this.embedId = embedId;
            this.publicUrl = publicUrl;
            this.details = details;
            this.document = document;
        }

        public long getNumericId() {
            return numericId;
        }

        public String getEmbedId() {
            return embedId;
        }

        @Nullable
        public String getPublicUrl() {
            return publicUrl;
        }

        @Nullable
        public JsonObject getDetails() {
            return details;
        }

        @Nullable
        public Document getDocument() {
            return document;
        }
    }
}
