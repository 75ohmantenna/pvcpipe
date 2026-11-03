package org.schabi.newpipe.extractor.services.rumble;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/** Resolves public creator URLs to typed API IDs without retaining creator metadata. */
public final class RumbleCreatorResolver {
    private static final int CACHE_SIZE = 64;
    private static final Pattern CHANNEL_ID = Pattern.compile("_c[0-9]+");
    private static final Pattern USER_ID = Pattern.compile("_u[0-9a-zA-Z]+");
    private static final Map<String, String> CREATOR_IDS_CACHE =
            new LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, String> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    private RumbleCreatorResolver() {
    }

    public static void rememberCreator(final JsonObject creator) throws ParsingException {
        final CreatorIdentity identity = identity(creator, false);
        register(identity.publicUrl, identity.typedId);
    }

    @Nullable
    public static JsonObject resolveMetadata(final Downloader downloader,
                                              final String publicChannelUrl,
                                              @Nullable final Localization localization)
            throws IOException, ReCaptchaException, ParsingException {
        final PublicCreator expected = publicCreator(publicChannelUrl);
        String typedId;
        synchronized (CREATOR_IDS_CACHE) {
            typedId = CREATOR_IDS_CACHE.get(expected.url);
        }
        if (typedId == null) {
            final Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("api", "8");
            parameters.put("query", expected.slug);
            parameters.put("offset", "0");
            parameters.put("limit", "20");
            final JsonObject search = RumbleApi.fetchData(downloader,
                    RumbleApi.serviceUrl("video_collection.search", parameters), localization);
            final JsonArray items = JsonUtils.getArray(search, "items");
            for (final Object item : items) {
                if (!(item instanceof JsonObject)) {
                    throw new ParsingException("Rumble creator search item is not an object");
                }
                final CreatorIdentity candidate = identity((JsonObject) item, false);
                if (!expected.url.equals(candidate.publicUrl)) {
                    continue;
                }
                if (typedId != null && !typedId.equals(candidate.typedId)) {
                    throw new ParsingException("Ambiguous Rumble creator identity for "
                            + expected.url);
                }
                typedId = candidate.typedId;
            }
            if (typedId == null) {
                return null;
            }
        }

        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("api", "8");
        parameters.put("id", typedId);
        final JsonObject metadata = RumbleApi.fetchData(downloader,
                RumbleApi.serviceUrl("video_collection.meta", parameters), localization);
        final CreatorIdentity returned = identity(metadata, true);
        if (!typedId.equals(returned.typedId) || !expected.url.equals(returned.publicUrl)) {
            throw new ParsingException("Rumble creator metadata does not match requested identity");
        }
        register(expected.url, typedId);
        return metadata;
    }

    private static CreatorIdentity identity(final JsonObject creator, final boolean requireUrl)
            throws ParsingException {
        final String type = JsonUtils.getString(creator, "type");
        final String typedId = JsonUtils.getString(creator, "id");
        final String prefix;
        if ("channel".equals(type) && CHANNEL_ID.matcher(typedId).matches()) {
            prefix = "c/";
        } else if ("user".equals(type) && USER_ID.matcher(typedId).matches()) {
            prefix = "user/";
        } else {
            throw new ParsingException("Invalid Rumble creator type/typed ID: " + type
                    + "/" + typedId);
        }

        final String slug = creator.get("slug") == null ? null
                : JsonUtils.getString(creator, "slug");
        if (slug != null) {
            validateSlug(slug);
        }
        final PublicCreator publicIdentity;
        if (creator.get("url") != null) {
            publicIdentity = publicCreator(JsonUtils.getString(creator, "url"));
            if (!publicIdentity.id.startsWith(prefix)
                    || slug != null && !slug.equals(publicIdentity.slug)) {
                throw new ParsingException("Rumble creator URL does not match type/slug");
            }
        } else if (!requireUrl && slug != null) {
            publicIdentity = publicCreator("https://rumble.com/" + prefix + encodeSlug(slug));
        } else {
            throw new ParsingException("Rumble creator has no public URL");
        }
        return new CreatorIdentity(typedId, publicIdentity.url);
    }

    private static PublicCreator publicCreator(final String url) throws ParsingException {
        final LinkHandler link = RumbleChannelLinkHandlerFactory.getInstance().fromUrl(url);
        final String id = link.getId();
        final int separator = id.indexOf('/');
        final String slug;
        try {
            slug = URI.create(link.getUrl()).getPath().substring(separator + 2);
        } catch (final IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new ParsingException("Invalid Rumble creator URL: " + url, e);
        }
        validateSlug(slug);
        final String canonicalId = id.substring(0, separator + 1) + encodeSlug(slug);
        return new PublicCreator(canonicalId, slug, "https://rumble.com/" + canonicalId);
    }

    private static void validateSlug(final String slug) throws ParsingException {
        if (slug.isEmpty() || slug.equals(".") || slug.equals("..")) {
            throw new ParsingException("Empty or invalid Rumble creator slug");
        }
        for (int i = 0; i < slug.length(); i++) {
            final char character = slug.charAt(i);
            if (character == '/' || character == '\\' || character == '?' || character == '#'
                    || Character.isISOControl(character)) {
                throw new ParsingException("Rumble creator slug is not a single path segment");
            }
        }
    }

    private static String encodeSlug(final String slug) {
        return Utils.encodeUrlUtf8(slug).replace("+", "%20");
    }

    private static void register(final String publicUrl, final String typedId)
            throws ParsingException {
        synchronized (CREATOR_IDS_CACHE) {
            final String existing = CREATOR_IDS_CACHE.get(publicUrl);
            if (existing != null && !existing.equals(typedId)) {
                throw new ParsingException("Conflicting Rumble creator identity for " + publicUrl);
            }
            CREATOR_IDS_CACHE.put(publicUrl, typedId);
        }
    }

    private static final class CreatorIdentity {
        private final String typedId;
        private final String publicUrl;

        private CreatorIdentity(final String typedId, final String publicUrl) {
            this.typedId = typedId;
            this.publicUrl = publicUrl;
        }
    }

    private static final class PublicCreator {
        private final String id;
        private final String slug;
        private final String url;

        private PublicCreator(final String id, final String slug, final String url) {
            this.id = id;
            this.slug = slug;
            this.url = url;
        }
    }
}
