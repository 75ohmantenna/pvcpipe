package org.schabi.newpipe.extractor.services.rumble;

import com.grack.nanojson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@Tag("offline")
class RumbleEmbedCacheTest {
    private static final String WATCH =
            "https://rumble.com/v7hnjfy-sci-fi-movie-or-space-history.html";
    private static final String BARE = "https://rumble.com/v7hnjfy";
    private static final long NUMERIC_ID = 449259064L;
    private Map<String, Long> cache;
    private Map<String, Long> previousCache;
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void isolateState() throws Exception {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        final Field field = RumbleMediaResolver.class.getDeclaredField("VIDEO_IDS_CACHE");
        field.setAccessible(true);
        cache = (Map<String, Long>) field.get(null);
        synchronized (cache) {
            previousCache = new LinkedHashMap<>(cache);
            cache.clear();
        }
    }

    @AfterEach
    void restoreState() {
        synchronized (cache) {
            cache.clear();
            cache.putAll(previousCache);
        }
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @Test
    void fullWatchUsesOriginalSlugAndPublishesDurableUrl() throws Exception {
        final Fixture downloader = new Fixture(video(NUMERIC_ID, WATCH));
        final var resolved = resolve(downloader, WATCH + "?feature=example#t=12");
        assertEquals(NUMERIC_ID, resolved.getNumericId());
        assertEquals("v7fh6ig", resolved.getEmbedId());
        assertEquals(WATCH, resolved.getPublicUrl());
        assertNotNull(resolved.getDetails());
        assertNull(resolved.getDocument());
        assertEquals(WATCH + "?feature=example", downloader.parameters().get("url"));
        assertEquals("8", downloader.parameters().get("api"));
        assertEquals("video.full,extended,with_cc,categories,comments,video.related_video,"
                + "video.dthumbs", downloader.parameters().get("options"));
        assertFalse(downloader.parameters().containsKey("id"));
    }

    @Test
    void decoratedShortUsesCanonicalPathForDetailsAndComments() throws Exception {
        final String shorts = "https://rumble.com/shorts/v7hnjfy";
        final Fixture downloader = new Fixture(video(NUMERIC_ID, shorts));
        assertEquals("v7fh6ig", RumbleMediaResolver.resolveEmbedId(downloader,
                link(shorts + "-decorative-slug?next=/embed/vother#t=12"), null));
        assertEquals(shorts, downloader.parameters().get("url"));
        assertEquals(shorts, resolve(downloader, shorts + "-other?x=1#t=2").getPublicUrl());
        assertEquals(Long.toString(NUMERIC_ID), downloader.parameters().get("id"));
    }

    @Test
    void directEmbedDoesNotRequireDetailsOrHtml() throws Exception {
        final Fixture downloader = new Fixture("not JSON");
        final var resolved = resolve(downloader, "https://rumble.com/embed/abc.v7fh6ig/");
        assertEquals(NUMERIC_ID, resolved.getNumericId());
        assertEquals("v7fh6ig", resolved.getEmbedId());
        assertNull(resolved.getDetails());
        assertNull(resolved.getPublicUrl());
        assertTrue(downloader.requests.isEmpty());
        for (final String invalid : List.of("v0000", "vzzzzzzzzzzzzzzzzzz")) {
            assertThrows(ParsingException.class,
                    () -> resolve(downloader, "https://rumble.com/embed/" + invalid));
        }
    }

    @Test
    void cachedCommentsAvoidMetadataButPlaybackRefreshesAndChecksIt() throws Exception {
        final Fixture downloader = new Fixture(video(NUMERIC_ID, WATCH));
        resolve(downloader, WATCH);
        downloader.requests.clear();
        downloader.body = "not JSON";
        assertEquals("v7fh6ig", RumbleMediaResolver.resolveEmbedId(downloader, link(BARE), null));
        assertTrue(downloader.requests.isEmpty());
        downloader.body = video(NUMERIC_ID + 1, WATCH);
        assertThrows(ParsingException.class, () -> resolve(downloader, BARE));
        assertEquals(Long.toString(NUMERIC_ID), downloader.parameters().get("id"));
        downloader.body = video(NUMERIC_ID, WATCH);
        assertEquals(NUMERIC_ID, resolve(downloader, BARE).getNumericId());
    }

    @Test
    void strictNumericIdentitiesNeverBecomeEmbedIds() throws Exception {
        for (final String id : List.of("null", "0", "-1", "1.5", "9223372036854775808",
                "1e100", "\"449259064\"", "true", "{}")) {
            final Fixture downloader = new Fixture("{\"data\":{\"id\":" + id
                    + ",\"url\":\"" + WATCH + "\"}}");
            assertThrows(ParsingException.class, () -> resolve(downloader, WATCH), id);
        }
        for (final String malformed : List.of("{}", "{\"data\":[]}",
                "{\"data\":{\"url\":\"" + WATCH + "\"}}", "{\"data\":null}")) {
            assertThrows(ParsingException.class, () -> resolve(new Fixture(malformed), WATCH));
        }
        final Fixture good = new Fixture(video(NUMERIC_ID, WATCH));
        assertEquals("v7fh6ig", resolve(good, WATCH).getEmbedId());
        assertEquals(WATCH, good.parameters().get("url"));
    }

    @Test
    void responsePublicIdentityMustMatchBeforeAnyAliasesAreRemembered() throws Exception {
        for (final String body : List.of(video(NUMERIC_ID, "https://rumble.com/vother-another.html"),
                "{\"data\":{\"id\":449259064,\"url\":\"" + WATCH
                        + "\",\"permalink_id\":\"vother\"}}",
                "{\"data\":{\"id\":449259064}}",
                video(NUMERIC_ID, "https://example.com/v7hnjfy-slug.html"),
                video(NUMERIC_ID, "https://rumble.com/embed/v7fh6ig"))) {
            assertThrows(ParsingException.class, () -> resolve(new Fixture(body), WATCH));
        }
        final Fixture good = new Fixture(video(NUMERIC_ID, WATCH));
        resolve(good, WATCH);
        assertEquals(WATCH, good.parameters().get("url"));
    }

    @Test
    void listingRegistrationRejectsConflictsAtomicallyAndSkipsMissingId() throws Exception {
        RumbleMediaResolver.rememberVideo(json("{\"url\":\"" + WATCH + "\"}"));
        for (final String id : List.of("null", "0", "-1", "1.5", "9223372036854775808",
                "\"449259064\"")) {
            assertThrows(ParsingException.class, () -> RumbleMediaResolver.rememberVideo(
                    json("{\"id\":" + id + ",\"url\":\"" + WATCH + "\"}")));
        }
        RumbleMediaResolver.rememberVideo(json(video(NUMERIC_ID, WATCH)).getObject("data"));
        final String otherSlug = BARE + "-another-slug.html";
        assertThrows(ParsingException.class, () -> RumbleMediaResolver.rememberVideo(
                json(video(NUMERIC_ID + 1, otherSlug)).getObject("data")));
        final Fixture downloader = new Fixture("malformed");
        assertEquals("v7fh6ig", RumbleMediaResolver.resolveEmbedId(downloader, link(WATCH), null));
        assertTrue(downloader.requests.isEmpty());
        assertEquals("v7fh6ig", RumbleMediaResolver.resolveEmbedId(
                downloader, link(otherSlug), null));
        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    void boundedAccessOrderedAliasesDoNotMakeFullUrlDependOnCache() throws Exception {
        RumbleMediaResolver.rememberVideo(json(video(NUMERIC_ID, WATCH)).getObject("data"));
        for (int i = 0; i < 64; i++) {
            RumbleMediaResolver.rememberVideo(json(video(i + 1,
                    "https://rumble.com/vcache" + i)).getObject("data"));
        }
        final Fixture downloader = new Fixture(video(NUMERIC_ID, WATCH));
        assertEquals("v7fh6ig", resolve(downloader, WATCH).getEmbedId());
        assertEquals(WATCH, downloader.parameters().get("url"));
        // A recently used alias survives insertion when the oldest entry does not.
        RumbleMediaResolver.resolveEmbedId(downloader, link("https://rumble.com/vcache2"), null);
        RumbleMediaResolver.rememberVideo(json(video(1000,
                "https://rumble.com/vnewest")).getObject("data"));
        downloader.requests.clear();
        assertEquals("v3", RumbleMediaResolver.resolveEmbedId(downloader,
                link("https://rumble.com/vcache2"), null));
        assertTrue(downloader.requests.isEmpty());
        assertThrows(ParsingException.class, () -> RumbleMediaResolver.resolveEmbedId(
                downloader, link("https://rumble.com/vcache3"), null));
        assertEquals(List.of("https://rumble.com/vcache3"), downloader.requests);
    }

    @Test
    void apiFailuresAndPremiumDenialAreNotHtmlFallbacksOrCached() throws Exception {
        for (final String body : List.of("{\"errors\":[{\"message\":\"Video not found\"}]}",
                "{\"data\":{\"success\":false,\"error\":\"Private video\"}}")) {
            final Fixture downloader = new Fixture(body);
            assertThrows(ContentNotAvailableException.class, () -> resolve(downloader, WATCH));
            assertTrue(downloader.requests.stream().allMatch(url -> url.contains("media.details")));
        }
        final Fixture premium = new Fixture(video(NUMERIC_ID, WATCH).replace("}}",
                ",\"availability\":\"premium\",\"has_access_to_premium\":false}}"));
        assertThrows(PaidContentException.class, () -> resolve(premium, WATCH));
        premium.body = video(NUMERIC_ID, WATCH).replace("}}",
                ",\"availability\":\"public\",\"visibility\":\"private\","
                        + "\"has_access_to_premium\":false}}");
        assertEquals(NUMERIC_ID, resolve(premium, WATCH).getNumericId());
        assertEquals(WATCH, premium.parameters().get("url"));
        premium.body = video(NUMERIC_ID, WATCH).replace("}}",
                ",\"availability\":\"premium\",\"has_access_to_premium\":true}}");
        assertEquals(NUMERIC_ID, resolve(premium, WATCH).getNumericId());
    }

    @Test
    void coldBareFetchesHtmlOnceAndAcceptsOnlyMatchingFullCanonical() throws Exception {
        final Fixture downloader = new Fixture("<link rel='canonical' href='" + WATCH + "'>"
                + "<script>Rumble('play', {video: 'v7fh6ig'});</script>");
        final var resolved = resolve(downloader, BARE);
        assertEquals("v7fh6ig", resolved.getEmbedId());
        assertEquals(WATCH, resolved.getPublicUrl());
        assertNotNull(resolved.getDocument());
        assertNull(resolved.getDetails());
        assertEquals(List.of(BARE), downloader.requests);
    }

    @Test
    void coldBareIgnoresUnrelatedCanonicalAndKeepsRefusalsActionable() throws Exception {
        final Fixture unrelated = new Fixture("<link rel='canonical' "
                + "href='https://rumble.com/vother-other.html'>"
                + "<iframe src='https://rumble.com/embed/v7fh6ig'></iframe>");
        assertNull(resolve(unrelated, BARE).getPublicUrl());
        synchronized (cache) {
            cache.clear();
        }
        final Fixture refused = new Fixture("<title>Forbidden</title>");
        refused.status = 403;
        assertThrows(ContentNotAvailableException.class, () -> resolve(refused, BARE));
        assertEquals(List.of(BARE), refused.requests);
        refused.status = 200;
        refused.body = "<title>Just a moment</title><script src='/challenge-platform/'></script>";
        assertThrows(PvcCloudFlareChallengeException.class, () -> resolve(refused, BARE));
        refused.body = "<title>No player</title>";
        assertThrows(ParsingException.class, () -> resolve(refused, BARE));
        refused.body = "<iframe src='https://rumble.com/embed/v0000'></iframe>";
        assertThrows(ParsingException.class, () -> resolve(refused, BARE));
    }

    @Test
    void pureHtmlExtractionReturnsFullInternalIdWithoutCaching() throws Exception {
        assertEquals("v7fh6ig", RumbleParsingHelper.extractEmbedVideoId(BARE,
                "<iframe src='https://rumble.com/embed/v7fh6ig'></iframe>"));
        assertEquals("vother", RumbleParsingHelper.extractEmbedVideoId(BARE,
                "<script>Rumble(\"play\", {video: 'vother'});</script>"));
    }

    @Test
    void normalExtractorChecksNumericEmbedVidAndOnlyThenPublishesFullUrl() throws Exception {
        final Fixture downloader = new Fixture(video(NUMERIC_ID, WATCH));
        NewPipe.init(downloader);
        final var extractor = Rumble.getStreamExtractor(WATCH);
        assertEquals(BARE, extractor.getUrl());
        downloader.embedBody = "{\"vid\":449259065,\"ua\":{},\"cc\":{}}";
        assertThrows(ParsingException.class, extractor::fetchPage);
        assertEquals(BARE, extractor.getUrl());
        for (final String invalid : List.of("null", "\"449259064\"", "449259064.5",
                "9223372036854775808")) {
            downloader.embedBody = "{\"vid\":" + invalid + ",\"ua\":{},\"cc\":{}}";
            assertThrows(ParsingException.class, () -> Rumble.getStreamExtractor(WATCH).fetchPage());
        }
        downloader.embedBody = "{\"vid\":449259064,\"ua\":{},\"cc\":{}}";
        final var valid = Rumble.getStreamExtractor(WATCH);
        valid.fetchPage();
        assertEquals(WATCH, valid.getUrl());
        assertTrue(downloader.requests.contains(
                "https://rumble.com/embedJS/u3/?request=video&ver=2&v=v7fh6ig"));
        assertFalse(downloader.requests.stream().anyMatch(url -> url.contains("v=v7hnjfy")));
    }

    private static RumbleMediaResolver.ResolvedMedia resolve(final Downloader downloader,
                                                              final String url) throws Exception {
        return RumbleMediaResolver.resolve(downloader, link(url), null);
    }

    private static LinkHandler link(final String url) throws ParsingException {
        return RumbleStreamLinkHandlerFactory.getInstance().fromUrl(url);
    }

    private static JsonObject json(final String body) throws ParsingException {
        return JsonUtils.toJsonObject(body);
    }

    private static String video(final long id, final String url) {
        return "{\"data\":{\"id\":" + id + ",\"url\":\"" + url + "\"}}";
    }

    private static final class Fixture extends Downloader {
        private String body;
        private String embedBody = "{\"ua\":{},\"cc\":{}}";
        private int status = 200;
        private final List<String> requests = new ArrayList<>();

        private Fixture(final String body) {
            this.body = body;
        }

        @Override
        public Response execute(@Nonnull final Request request) throws IOException {
            requests.add(request.url());
            final String responseBody = request.url().startsWith("https://rumble.com/embedJS/")
                    ? embedBody : body;
            return new Response(status, "Fixture", Map.of(), responseBody, request.url());
        }

        private Map<String, String> parameters() {
            final Map<String, String> result = new LinkedHashMap<>();
            final String query = URI.create(requests.get(requests.size() - 1)).getRawQuery();
            for (final String parameter : query.split("&")) {
                final String[] pair = parameter.split("=", 2);
                result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            return result;
        }
    }
}
