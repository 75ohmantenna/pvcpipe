package org.schabi.newpipe.extractor.services.rumble;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.pvc.PvcCloudFlareChallengeException;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class RumbleCreatorResolverTest {
    private static final String CHANNEL = "https://rumble.com/c/FYMVideos";
    private static final String ID = "_c1330192";
    private Map<String, String> cache;
    private Map<String, String> previousCache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void isolateCache() throws Exception {
        final Field field = RumbleCreatorResolver.class.getDeclaredField("CREATOR_IDS_CACHE");
        field.setAccessible(true);
        cache = (Map<String, String>) field.get(null);
        synchronized (cache) {
            previousCache = new LinkedHashMap<>(cache);
            cache.clear();
        }
    }

    @AfterEach
    void restoreCache() {
        synchronized (cache) {
            cache.clear();
            cache.putAll(previousCache);
        }
    }

    @Test
    void coldResolutionRequiresExactTypeAndCaseSensitiveSlug() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.search = search(
                creator("channel", "_c1", "FYMVideosExtra"),
                creator("user", "_u2og7c4", "FYMVideos"),
                creator("channel", "_c3", "fymvideos"),
                creator("channel", ID, "FYMVideos"));
        final JsonObject metadata = resolve(fixture, "http://www.rumble.com/c/FYMVideos/about?x=1#top");
        assertEquals(ID, metadata.getString("id"));
        assertEquals("Creator title", metadata.getString("title"));
        assertEquals(List.of("video_collection.search", "video_collection.meta"), fixture.names());
        assertEquals(Map.of("name", "video_collection.search", "api", "8", "query", "FYMVideos",
                "offset", "0", "limit", "20"), fixture.requests.get(0));
        assertEquals(ID, fixture.requests.get(1).get("id"));
        assertEquals(ID, cache.get(CHANNEL));
    }

    @Test
    void rememberedByUrlNormalizesAliasesAndRefreshesMetadataWithoutSearch() throws Exception {
        final JsonObject by = creator("channel", ID, "FYMVideos");
        by.remove("slug");
        by.put("url", "http://www.rumble.com/c/FYMVideos/?x=1#top");
        RumbleCreatorResolver.rememberCreator(by);
        final Fixture fixture = new Fixture();
        fixture.search = "API search must not be needed";
        assertEquals(ID, resolve(fixture, CHANNEL).getString("id"));
        fixture.metadata = metadata(creator("channel", ID, "FYMVideos"));
        assertNull(resolve(fixture, CHANNEL + "/videos").getString("title"));
        assertEquals(List.of("video_collection.meta", "video_collection.meta"), fixture.names());
        assertEquals(Map.of(CHANNEL, ID), cache);
    }

    @Test
    void slugOnlyListingAndAlphanumericUserIdsRemainTyped() throws Exception {
        final JsonObject user = creator("user", "_u2og7c4", "NASA+Videos");
        user.remove("url");
        RumbleCreatorResolver.rememberCreator(user);
        final Fixture fixture = new Fixture();
        fixture.metadata = metadata(creator("user", "_u2og7c4", "NASA+Videos"));
        assertEquals("_u2og7c4", resolve(fixture,
                "https://rumble.com/user/NASA%2BVideos").getString("id"));
        assertEquals(List.of("video_collection.meta"), fixture.names());
        assertEquals("_u2og7c4", fixture.requests.get(0).get("id"));
        assertEquals("_u2og7c4", cache.get("https://rumble.com/user/NASA%2BVideos"));
    }

    @Test
    void sameSlugInDifferentCreatorNamespacesDoesNotShareAnIdentity() throws Exception {
        RumbleCreatorResolver.rememberCreator(creator("channel", ID, "FYMVideos"));
        RumbleCreatorResolver.rememberCreator(creator("user", "_u2og7c4", "FYMVideos"));
        final Fixture fixture = new Fixture();
        fixture.metadata = metadata(creator("user", "_u2og7c4", "FYMVideos"));
        assertEquals("_u2og7c4", resolve(fixture,
                "https://rumble.com/user/FYMVideos").getString("id"));
        fixture.metadata = metadata(creator("channel", ID, "FYMVideos"));
        assertEquals(ID, resolve(fixture, CHANNEL).getString("id"));
        assertEquals(2, cache.size());
    }

    @Test
    void approximateAndWrongTypeResultsAreNotChosenAndNoMatchIsNotCached() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.search = search(creator("channel", "_c1", "FYMVideosExtra"),
                creator("user", "_u2og7c4", "FYMVideos"));
        assertNull(resolve(fixture, CHANNEL));
        assertTrue(cache.isEmpty());
        assertEquals(List.of("video_collection.search"), fixture.names());
        fixture.search = search(creator("channel", ID, "FYMVideos"));
        assertEquals(ID, resolve(fixture, CHANNEL).getString("id"));
        assertEquals(List.of("video_collection.search", "video_collection.search",
                "video_collection.meta"), fixture.names());
    }

    @Test
    void emptyExactSearchDoesNotClaimTheChannelIsUnavailable() throws Exception {
        final Fixture fixture = new Fixture();
        assertNull(resolve(fixture, CHANNEL));
        assertNull(resolve(fixture, CHANNEL));
        assertEquals(List.of("video_collection.search", "video_collection.search"), fixture.names());
        assertTrue(cache.isEmpty());
    }

    @Test
    void distinctMatchingIdsAreAmbiguousButRepeatedSameIdentityIsAccepted() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.search = search(creator("channel", ID, "FYMVideos"),
                creator("channel", "_c7", "FYMVideos"));
        assertThrows(ParsingException.class, () -> resolve(fixture, CHANNEL));
        assertTrue(cache.isEmpty());
        assertEquals(List.of("video_collection.search"), fixture.names());
        fixture.search = search(creator("channel", ID, "FYMVideos"),
                creator("channel", ID, "FYMVideos"));
        assertEquals(ID, resolve(fixture, CHANNEL).getString("id"));
    }

    @Test
    void malformedCreatorIdentitiesCannotPopulateTheCache() throws Exception {
        for (final String malformed : List.of(
                "{}", "{\"type\":\"channel\",\"id\":\"_c1\"}",
                "{\"type\":\"channel\",\"id\":\"_uabc\",\"slug\":\"FYMVideos\"}",
                "{\"type\":\"user\",\"id\":\"_c1\",\"slug\":\"FYMVideos\"}",
                "{\"type\":\"channel\",\"id\":\"_cabc\",\"slug\":\"FYMVideos\"}",
                "{\"type\":\"user\",\"id\":\"_u\",\"slug\":\"FYMVideos\"}",
                "{\"type\":\"user\",\"id\":7,\"slug\":\"FYMVideos\"}",
                "{\"type\":\"video\",\"id\":\"_c1\",\"slug\":\"FYMVideos\"}")) {
            assertThrows(ParsingException.class,
                    () -> RumbleCreatorResolver.rememberCreator(json(malformed)), malformed);
        }
        for (final String slug : List.of("", ".", "..", "foo/bar", "foo\\bar", "foo?bar", "foo#bar")) {
            final JsonObject creator = creator("channel", ID, "FYMVideos");
            creator.remove("url");
            creator.put("slug", slug);
            assertThrows(ParsingException.class,
                    () -> RumbleCreatorResolver.rememberCreator(creator), slug);
        }
        assertTrue(cache.isEmpty());
    }

    @Test
    void creatorUrlsAndSlugsMustAgreeWithTypeAndHost() throws Exception {
        for (final String url : List.of("https://example.com/c/FYMVideos",
                "https://rumble.com.evil.test/c/FYMVideos", "https://rumble.com/user/FYMVideos",
                "https://rumble.com/c/Other", "https://rumble.com/c/FYMVideos%2Fother")) {
            final JsonObject invalid = creator("channel", ID, "FYMVideos");
            invalid.put("url", url);
            assertThrows(ParsingException.class,
                    () -> RumbleCreatorResolver.rememberCreator(invalid), url);
        }
        assertTrue(cache.isEmpty());
    }

    @Test
    void conflictsDoNotOverwritePreviouslyRememberedIdentity() throws Exception {
        RumbleCreatorResolver.rememberCreator(creator("channel", ID, "FYMVideos"));
        assertThrows(ParsingException.class, () -> RumbleCreatorResolver.rememberCreator(
                creator("channel", "_c9", "FYMVideos")));
        assertEquals(ID, resolve(new Fixture(), CHANNEL).getString("id"));
        assertEquals(ID, cache.get(CHANNEL));
    }

    @Test
    void wrongMetadataIdentityIsRejectedBeforeCachingSearchMatch() throws Exception {
        final List<JsonObject> invalid = new ArrayList<>();
        invalid.add(creator("channel", "_c9", "FYMVideos"));
        invalid.add(creator("user", "_u2og7c4", "FYMVideos"));
        invalid.add(creator("channel", ID, "Other"));
        final JsonObject noUrl = creator("channel", ID, "FYMVideos");
        noUrl.remove("url");
        invalid.add(noUrl);
        final JsonObject inconsistent = creator("channel", ID, "FYMVideos");
        inconsistent.put("slug", "Other");
        invalid.add(inconsistent);
        for (final JsonObject wrong : invalid) {
            final Fixture fixture = new Fixture();
            fixture.search = search(creator("channel", ID, "FYMVideos"));
            fixture.metadata = metadata(wrong);
            assertThrows(ParsingException.class, () -> resolve(fixture, CHANNEL));
            assertTrue(cache.isEmpty());
            fixture.metadata = metadata(creator("channel", ID, "FYMVideos"));
            assertEquals(ID, resolve(fixture, CHANNEL).getString("id"));
            assertEquals("video_collection.search", fixture.names().get(2));
            synchronized (cache) {
                cache.clear();
            }
        }
    }

    @Test
    void cachedMetadataMismatchAndApiFailureDoNotReplaceTheKnownIdentity() throws Exception {
        RumbleCreatorResolver.rememberCreator(creator("channel", ID, "FYMVideos"));
        final Fixture fixture = new Fixture();
        fixture.metadata = metadata(creator("channel", "_c9", "FYMVideos"));
        assertThrows(ParsingException.class, () -> resolve(fixture, CHANNEL));
        fixture.metadata = "{\"errors\":[{\"message\":\"Creator unavailable\",\"code\":7}]}";
        assertThrows(ContentNotAvailableException.class, () -> resolve(fixture, CHANNEL));
        fixture.metadata = metadata(creator("channel", ID, "FYMVideos"));
        assertEquals(ID, resolve(fixture, CHANNEL).getString("id"));
        assertEquals(List.of("video_collection.meta", "video_collection.meta",
                "video_collection.meta"), fixture.names());
    }

    @Test
    void malformedSearchAndApiErrorsRemainFailuresRatherThanNoMatch() throws Exception {
        for (final String response : List.of("not JSON", "{}", "{\"data\":null}",
                "{\"data\":{}}", "{\"data\":{\"items\":{}}}",
                "{\"data\":{\"items\":[null]}}", "{\"data\":{\"items\":[{}]}}")) {
            final Fixture fixture = new Fixture();
            fixture.search = response;
            assertThrows(ParsingException.class, () -> resolve(fixture, CHANNEL), response);
            assertTrue(cache.isEmpty());
        }
        final Fixture fixture = new Fixture();
        fixture.search = "{\"errors\":[{\"message\":\"Search refused\",\"code\":0}]}";
        assertTrue(assertThrows(ContentNotAvailableException.class,
                () -> resolve(fixture, CHANNEL)).getMessage().contains("Search refused"));
        assertTrue(cache.isEmpty());
    }

    @Test
    void malformedMetadataDoesNotCacheAnOtherwiseExactSearchIdentity() throws Exception {
        for (final String response : List.of("not JSON", "{}", "{\"data\":null}",
                "{\"data\":[]}", "{\"data\":{}}")) {
            final Fixture fixture = new Fixture();
            fixture.search = search(creator("channel", ID, "FYMVideos"));
            fixture.metadata = response;
            assertThrows(ParsingException.class, () -> resolve(fixture, CHANNEL), response);
            assertTrue(cache.isEmpty());
        }
        final Fixture fixture = new Fixture();
        fixture.search = search(creator("channel", ID, "FYMVideos"));
        fixture.metadata = "{\"errors\":[{\"message\":\"Metadata refused\",\"code\":0}]}";
        assertThrows(ContentNotAvailableException.class, () -> resolve(fixture, CHANNEL));
        assertTrue(cache.isEmpty());
    }

    @Test
    void challengedOrFailedApiNeverFallsBackToHtmlOrPoisonsCache() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.search = "<html>Just a moment challenge-platform</html>";
        fixture.headers = Map.of("cf-mitigated", List.of("challenge"));
        assertThrows(PvcCloudFlareChallengeException.class, () -> resolve(fixture, CHANNEL));
        assertTrue(cache.isEmpty());
        fixture.headers = Map.of();
        fixture.failure = new IOException("Offline");
        assertThrows(IOException.class, () -> resolve(fixture, CHANNEL));
        fixture.failure = null;
        fixture.search = search(creator("channel", ID, "FYMVideos"));
        assertEquals(ID, resolve(fixture, CHANNEL).getString("id"));
        assertTrue(fixture.names().stream().allMatch(name -> name.startsWith("video_collection.")));
    }

    @Test
    void cacheIsBoundedAccessOrderedAndEvictionStillAllowsColdResolution() throws Exception {
        for (int i = 0; i < 64; i++) {
            RumbleCreatorResolver.rememberCreator(creator("channel", "_c" + (i + 1), "Creator" + i));
        }
        final Fixture fixture = new Fixture();
        fixture.metadata = metadata(creator("channel", "_c1", "Creator0"));
        assertEquals("_c1", resolve(fixture, "https://rumble.com/c/Creator0").getString("id"));
        RumbleCreatorResolver.rememberCreator(creator("channel", "_c65", "Creator64"));
        assertEquals(64, cache.size());
        assertTrue(cache.containsKey("https://rumble.com/c/Creator0"));
        assertFalse(cache.containsKey("https://rumble.com/c/Creator1"));
        fixture.requests.clear();
        fixture.search = search(creator("channel", "_c2", "Creator1"));
        fixture.metadata = metadata(creator("channel", "_c2", "Creator1"));
        assertEquals("_c2", resolve(fixture, "https://rumble.com/c/Creator1").getString("id"));
        assertEquals(List.of("video_collection.search", "video_collection.meta"), fixture.names());
        assertEquals(64, cache.size());
        assertTrue(cache.values().stream().allMatch(id -> id.matches("_c[0-9]+")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void creatorEvictionCannotEvictOrReuseMediaIdentities() throws Exception {
        final Field field = RumbleMediaResolver.class.getDeclaredField("VIDEO_IDS_CACHE");
        field.setAccessible(true);
        final Map<String, Long> mediaCache = (Map<String, Long>) field.get(null);
        final Map<String, Long> previousMediaCache;
        synchronized (mediaCache) {
            previousMediaCache = new LinkedHashMap<>(mediaCache);
            mediaCache.clear();
        }
        try {
            RumbleMediaResolver.rememberVideo(json("{\"id\":449259064,"
                    + "\"url\":\"https://rumble.com/v7hnjfy-title.html\"}"));
            for (int i = 0; i < 65; i++) {
                RumbleCreatorResolver.rememberCreator(
                        creator("channel", "_c" + (i + 1), "v7hnjfy" + i));
            }
            final Fixture fixture = new Fixture();
            assertEquals("v7fh6ig", RumbleMediaResolver.resolveEmbedId(fixture,
                    RumbleStreamLinkHandlerFactory.getInstance()
                            .fromUrl("https://rumble.com/v7hnjfy-title.html"), null));
            assertTrue(fixture.requests.isEmpty());
            assertEquals(64, cache.size());
        } finally {
            synchronized (mediaCache) {
                mediaCache.clear();
                mediaCache.putAll(previousMediaCache);
            }
        }
    }

    @Test
    void invalidPublicInputFailsBeforeAnyRequest() {
        final Fixture fixture = new Fixture();
        for (final String url : List.of("https://example.com/c/FYMVideos",
                "https://rumble.com/v7hnjfy-title.html", "https://rumble.com/c/",
                "https://rumble.com/c/FYMVideos%2Fother")) {
            assertThrows(ParsingException.class, () -> resolve(fixture, url), url);
        }
        assertTrue(fixture.requests.isEmpty());
    }

    private static JsonObject resolve(final Fixture downloader, final String url) throws Exception {
        return RumbleCreatorResolver.resolveMetadata(downloader, url, Localization.DEFAULT);
    }

    private static JsonObject creator(final String type, final String id, final String slug) {
        final JsonObject creator = new JsonObject();
        creator.put("type", type);
        creator.put("id", id);
        creator.put("slug", slug);
        creator.put("url", "https://rumble.com/" + (type.equals("channel") ? "c/" : "user/")
                + slug.replace("+", "%2B"));
        return creator;
    }

    private static JsonObject json(final String value) throws ParsingException {
        return JsonUtils.toJsonObject(value);
    }

    private static String search(final JsonObject... creators) {
        final List<String> objects = new ArrayList<>();
        for (final JsonObject creator : creators) {
            objects.add(JsonWriter.string(creator));
        }
        return "{\"data\":{\"items\":[" + String.join(",", objects) + "]}}";
    }

    private static String metadata(final JsonObject creator) {
        return "{\"data\":" + JsonWriter.string(creator) + "}";
    }

    private static final class Fixture extends Downloader {
        private String search = search();
        private String metadata;
        private IOException failure;
        private Map<String, List<String>> headers = Map.of();
        private final List<Map<String, String>> requests = new ArrayList<>();

        private Fixture() {
            final JsonObject creator = creator("channel", ID, "FYMVideos");
            creator.put("title", "Creator title");
            metadata = metadata(creator);
        }

        @Override
        public Response execute(@Nonnull final Request request) throws IOException {
            final URI uri = URI.create(request.url());
            if (!"rumble.com".equals(uri.getHost()) || !"/service.php".equals(uri.getPath())) {
                throw new AssertionError("Unexpected HTML/fallback request: " + request.url());
            }
            final Map<String, String> parameters = new LinkedHashMap<>();
            for (final String parameter : uri.getRawQuery().split("&")) {
                final String[] pair = parameter.split("=", 2);
                parameters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            requests.add(parameters);
            assertEquals("8", parameters.get("api"));
            if (failure != null) {
                throw failure;
            }
            final String name = parameters.get("name");
            final String body;
            if ("video_collection.search".equals(name)) {
                body = search;
            } else if ("video_collection.meta".equals(name)) {
                assertTrue(parameters.get("id").matches("_c[0-9]+|_u[0-9a-zA-Z]+"),
                        "Public slugs must never be sent as metadata IDs");
                body = metadata;
            } else {
                throw new AssertionError("Unexpected API: " + name);
            }
            return new Response(200, "Fixture", headers, body, request.url());
        }

        private List<String> names() {
            final List<String> names = new ArrayList<>();
            for (final Map<String, String> request : requests) {
                names.add(request.get("name"));
            }
            return names;
        }
    }
}
