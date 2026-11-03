package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.pvc.PvcCloudFlareChallengeException;
import org.schabi.newpipe.extractor.services.rumble.RumbleApi;
import org.schabi.newpipe.extractor.services.rumble.RumbleCreatorResolver;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleTrendingLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@Tag("offline")
class RumbleWebsiteInteractionTest {
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;
    private Map<String, Long> previousMediaIds;
    private Map<String, String> previousCreatorIds;

    @BeforeEach
    void saveExtractorState() {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        final Map<String, Long> cache = mediaIds();
        synchronized (cache) {
            previousMediaIds = new LinkedHashMap<>(cache);
            cache.clear();
        }
        final Map<String, String> creators = creatorIds();
        synchronized (creators) {
            previousCreatorIds = new LinkedHashMap<>(creators);
            creators.clear();
        }
    }

    @AfterEach
    void restoreExtractorState() {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
        final Map<String, Long> cache = mediaIds();
        synchronized (cache) {
            cache.clear();
            cache.putAll(previousMediaIds);
        }
        final Map<String, String> creators = creatorIds();
        synchronized (creators) {
            creators.clear();
            creators.putAll(previousCreatorIds);
        }
    }

    @Test
    void challengedBrowsePagesRaiseAnActionableErrorInsteadOfShowingNoResults() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader(403,
                "<title>Just a moment...</title>");
        NewPipe.init(downloader);
        for (final String kiosk : List.of(RumbleTrendingLinkHandlerFactory.TRENDING_TODAY,
                RumbleTrendingLinkHandlerFactory.LATEST,
                RumbleTrendingLinkHandlerFactory.EDITOR_PICKS,
                RumbleTrendingLinkHandlerFactory.DEFAULT_TRENDING)) {
            final var extractor = Rumble.getKioskList().getExtractorById(kiosk, null);
            assertThrows(PvcCloudFlareChallengeException.class, extractor::fetchPage);
            assertEquals(extractor.getUrl(), downloader.urls.get(downloader.urls.size() - 1));
            assertFalse(downloader.urls.get(downloader.urls.size() - 1).contains("service.php"));
        }
    }

    @Test
    void challengedSearchAndChannelPaginationDoNotSilentlyEndTheList() throws Exception {
        NewPipe.init(new FixtureDownloader(403, "<title>Just a moment...</title>"));
        final Page next = new Page("https://rumble.com/c/Example?page=2");
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getSearchExtractor("space", List.of(), null).getPage(next));
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getChannelTabExtractorFromId("c/Example",
                        org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.VIDEOS).getPage(next));
    }

    @Test
    void jsonEndpointsReportChallengesBeforeTryingToParseHtmlAsJson() throws Exception {
        NewPipe.init(new FixtureDownloader(403, "<title>Just a moment...</title>"));
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getStreamExtractor("https://rumble.com/embed/vtest/").fetchPage());
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getStreamExtractor("https://rumble.com/shorts/vtest").fetchPage());
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getSuggestionExtractor().suggestionList("space"));
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> shorts().fetchPage());
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> Rumble.getKioskList().getExtractorById(
                        RumbleTrendingLinkHandlerFactory.LIVE, null).fetchPage());
    }

    @Test
    void shortsPaginationCountsAnAdAndStopsOnAnExplicitTerminalFeed() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader(200,
                "{\"data\":{\"items\":[{\"object_type\":\"ad\"}],\"has_more\":true}}");
        NewPipe.init(downloader);
        final var extractor = shorts();
        extractor.fetchPage();
        final var initial = extractor.getInitialPage();
        assertEquals("1", initial.getNextPage().getId());
        assertTrue(initial.getNextPage().getUrl().contains("offset=1&"));
        downloader.body = "{\"data\":{\"items\":[],\"has_more\":false}}";
        final var next = shorts().getPage(initial.getNextPage());
        assertTrue(downloader.urls.get(1).contains("offset=1&"));
        assertTrue(next.getItems().isEmpty());
        assertNull(next.getNextPage());
    }

    @Test
    void embeddedVideoCommentsUseTheEmbedIdWithoutFetchingAnInvalidWatchUrl() throws Exception {
        final MediaFixtureDownloader downloader = new MediaFixtureDownloader(
                "https://rumble.com/embed/vtest123/", Long.parseLong("test123", 36), "{}");
        NewPipe.init(downloader);
        final var extractor = Rumble.getCommentsExtractor("https://rumble.com/embed/u3.vtest123/");
        extractor.fetchPage();
        assertEquals(List.of("https://rumble.com/service.php?video=test123&name=comment.list"),
                downloader.urls);
        assertFalse(extractor.isCommentsDisabled());
        assertTrue(extractor.getInitialPage().getItems().isEmpty());
        assertEquals(1, downloader.urls.size());
    }

    @Test
    void decoratedShortsCommentsUseCanonicalDetailsAndTheInternalVideoId() throws Exception {
        final MediaFixtureDownloader downloader = new MediaFixtureDownloader(
                "https://rumble.com/shorts/v7g09by", 446493256, shortDetails());
        NewPipe.init(downloader);
        final var extractor = Rumble.getCommentsExtractor(
                "https://rumble.com/shorts/v7g09by-decorative-slug.html?x=1#t=12");
        extractor.fetchPage();
        assertEquals("https://rumble.com/service.php?video=7dtweg&name=comment.list",
                extractor.getUrl());
        assertFalse(extractor.isCommentsDisabled());
        assertTrue(extractor.getInitialPage().getItems().isEmpty());
        assertTrue(downloader.urls.stream().anyMatch(
                url -> url.contains("video=7dtweg&name=comment.list")));
        assertTrue(downloader.urls.stream().noneMatch(url -> url.contains("/shorts/")));
    }

    @Test
    void fullWatchCommentsResolveNumericDetailsRatherThanThePublicPermalink() throws Exception {
        final MediaFixtureDownloader downloader = new MediaFixtureDownloader(
                "https://rumble.com/v7hnjfy-full-watch-slug.html?source=fixture", 449259064,
                "{\"id\":449259064,\"url\":"
                        + "\"https://rumble.com/v7hnjfy-full-watch-slug.html\"}");
        NewPipe.init(downloader);
        final var extractor = Rumble.getCommentsExtractor(
                "https://rumble.com/v7hnjfy-full-watch-slug.html?source=fixture#t=10");
        extractor.fetchPage();
        assertEquals("https://rumble.com/service.php?video=7fh6ig&name=comment.list",
                extractor.getUrl());
        assertFalse(extractor.isCommentsDisabled());
        assertTrue(extractor.getInitialPage().getItems().isEmpty());
    }

    @Test
    void shortsPlayFromDetailsEvenWhenTheirHtmlIsChallenged() throws Exception {
        final MediaFixtureDownloader downloader = new MediaFixtureDownloader(
                "https://rumble.com/shorts/v7g09by", 446493256, shortDetails());
        NewPipe.init(downloader);
        final var extractor = Rumble.getStreamExtractor(
                "https://rumble.com/shorts/v7g09by-decorative-slug.html?x=1#t=12");
        extractor.fetchPage();
        assertEquals("Fixture Short", extractor.getName());
        assertEquals("https://rumble.com/shorts/v7g09by", extractor.getUrl());
        assertEquals("Details description", extractor.getDescription().getContent());
        final var streams = extractor.getVideoStreams();
        assertEquals(1, streams.size());
        assertEquals("https://cdn.example/short.mp4?token=preserved", streams.get(0).getContent());
        assertEquals("720p@1250k", streams.get(0).getResolution());
        assertEquals(1250000, streams.get(0).getBitrate());
        assertTrue(extractor.getVideoOnlyStreams().isEmpty());
        assertTrue(extractor.getAudioStreams().isEmpty());
        assertTrue(downloader.urls.stream().noneMatch(url -> url.contains("/shorts/")));
    }

    @Test
    void shortsRelatedFeedExcludesSelfDuplicatesAndNonShortsAndIsCached() throws Exception {
        final MediaFixtureDownloader downloader = new MediaFixtureDownloader(
                "https://rumble.com/shorts/v7g09by", 446493256, shortDetails());
        final String related = relatedShort(446493257, "vnext1", true);
        downloader.feedJson = "{\"data\":{\"items\":["
                + relatedShort(446493256, "v7g09by", true) + ","
                + related + "," + related + ","
                + relatedShort(446493258, "vlong1", false)
                + ",{\"object_type\":\"ad\"}],\"has_more\":false}}";
        NewPipe.init(downloader);
        final var extractor = Rumble.getStreamExtractor("https://rumble.com/shorts/v7g09by");
        extractor.fetchPage();
        assertTrue(downloader.urls.stream().noneMatch(url -> url.contains("shorts.feed")));
        final var relatedItems = extractor.getRelatedItems();
        assertEquals(1, relatedItems.getItems().size());
        assertEquals("https://rumble.com/shorts/vnext1", relatedItems.getItems().get(0).getUrl());
        assertTrue(relatedItems.getErrors().isEmpty(), relatedItems.getErrors().toString());
        assertSame(relatedItems, extractor.getRelatedItems());
        downloader.feedJson = null;
        assertEquals(1, extractor.getRelatedItems().getItems().size());
    }

    @Test
    void shortsRejectMissingOrEmptyPlayableSources() throws Exception {
        for (final String sources : List.of("", ",\"videos\":null", ",\"videos\":[]",
                ",\"videos\":[{\"url\":\"\"}]", ",\"videos\":[{}]")) {
            NewPipe.init(new MediaFixtureDownloader("https://rumble.com/shorts/v7g09by",
                    446493256, "{\"id\":446493256,"
                            + "\"url\":\"https://rumble.com/shorts/v7g09by\"" + sources + "}"));
            assertThrows(ContentNotAvailableException.class,
                    () -> Rumble.getStreamExtractor("https://rumble.com/shorts/v7g09by").fetchPage());
        }
    }

    @Test
    void shortsRelatedFeedRequiresAnItemsArrayAndPreservesApiFailures() throws Exception {
        for (final String body : List.of("{\"data\":{}}", "{\"data\":{\"items\":{}}}",
                "{\"error\":\"Feed refused\"}")) {
            final MediaFixtureDownloader downloader = new MediaFixtureDownloader(
                    "https://rumble.com/shorts/v7g09by", 446493256, shortDetails());
            downloader.feedJson = body;
            NewPipe.init(downloader);
            final var extractor = Rumble.getStreamExtractor("https://rumble.com/shorts/v7g09by");
            extractor.fetchPage();
            assertThrows(org.schabi.newpipe.extractor.exceptions.ExtractionException.class,
                    extractor::getRelatedItems);
        }
    }

    @Test
    void shortsDescriptionUsesExplicitEmptyDetailsBeforeProcessedDescription() throws Exception {
        for (final String description : List.of("", "Fallback description")) {
            final String details = shortDetails().replace("\"description\":\"Details description\",",
                    description.isEmpty() ? "\"description\":\"\"," : "");
            NewPipe.init(new MediaFixtureDownloader("https://rumble.com/shorts/v7g09by",
                    446493256, details));
            final var extractor = Rumble.getStreamExtractor("https://rumble.com/shorts/v7g09by");
            extractor.fetchPage();
            assertEquals(description, extractor.getDescription().getContent());
        }
    }

    @Test
    void shortsDetailsExposeMetadataWithoutRequiringWatchHtml() throws Exception {
        final String details = shortDetails().replace("\"by\":{",
                "\"upload_date\":\"2026-10-31T08:15:30+00:00\",\"duration\":42,"
                        + "\"views\":5000000000,\"is_age_restricted\":true,"
                        + "\"rumble_votes\":{\"num_votes_up\":123,\"num_votes_down\":4},"
                        + "\"thumb\":\"https://cdn.example/cover.jpg\",\"by\":{"
                        + "\"thumb\":\"https://cdn.example/avatar.jpg\","
                        + "\"followers\":9876,\"subscribers\":99,\"verified_badge\":true,");
        final MediaFixtureDownloader downloader = new MediaFixtureDownloader(
                "https://rumble.com/shorts/v7g09by", 446493256, details);
        NewPipe.init(downloader);
        final var extractor = Rumble.getStreamExtractor("https://rumble.com/shorts/v7g09by");
        extractor.fetchPage();
        assertEquals(StreamType.VIDEO_STREAM, extractor.getStreamType());
        assertEquals(18, extractor.getAgeLimit());
        assertEquals("2026-10-31T08:15:30+00:00", extractor.getTextualUploadDate());
        assertEquals(Instant.parse("2026-10-31T08:15:30Z"),
                extractor.getUploadDate().getInstant());
        assertFalse(extractor.getUploadDate().isApproximation());
        assertEquals(42, extractor.getLength());
        assertEquals(5000000000L, extractor.getViewCount());
        assertEquals(123, extractor.getLikeCount());
        assertEquals(4, extractor.getDislikeCount());
        final Image cover = extractor.getThumbnails().get(0);
        assertEquals("https://cdn.example/cover.jpg", cover.getUrl());
        assertEquals(Image.HEIGHT_UNKNOWN, cover.getHeight());
        assertEquals(Image.WIDTH_UNKNOWN, cover.getWidth());
        assertEquals("Example", extractor.getUploaderName());
        assertEquals("https://rumble.com/c/Example", extractor.getUploaderUrl());
        assertEquals("https://cdn.example/avatar.jpg",
                extractor.getUploaderAvatars().get(0).getUrl());
        assertTrue(extractor.isUploaderVerified());
        assertEquals(9876, extractor.getUploaderSubscriberCount());
        assertEquals(1, extractor.getVideoStreams().size());
        assertTrue(downloader.urls.stream().noneMatch(url -> url.contains("/shorts/")));
    }

    @Test
    void shortsAbsentAndNullMetadataPreservePlaybackAndUnknownDefaults() throws Exception {
        final String originalBy =
                "\"by\":{\"name\":\"Example\",\"url\":\"https://rumble.com/c/Example\"},";
        for (final String optional : List.of("", "\"by\":null,\"thumb\":null,",
                "\"by\":{\"name\":null,\"url\":null,\"thumb\":\"\",\"followers\":null,"
                        + "\"verified_badge\":null},\"thumb\":\"\",\"duration\":null,"
                        + "\"views\":null,\"rumble_votes\":null,\"upload_date\":null,")) {
            final String details = shortDetails().replace(originalBy, optional);
            NewPipe.init(new MediaFixtureDownloader(
                    "https://rumble.com/shorts/v7g09by", 446493256, details));
            final var extractor = Rumble.getStreamExtractor("https://rumble.com/shorts/v7g09by");
            extractor.fetchPage();
            assertEquals("", extractor.getUploaderName());
            assertEquals("", extractor.getUploaderUrl());
            assertTrue(extractor.getThumbnails().isEmpty());
            assertTrue(extractor.getUploaderAvatars().isEmpty());
            assertFalse(extractor.isUploaderVerified());
            assertEquals(-1, extractor.getUploaderSubscriberCount());
            assertEquals(-1, extractor.getLength());
            assertEquals(-1, extractor.getViewCount());
            assertEquals(-1, extractor.getLikeCount());
            assertEquals(-1, extractor.getDislikeCount());
            assertEquals(0, extractor.getAgeLimit());
            assertNull(extractor.getTextualUploadDate());
            assertNull(extractor.getUploadDate());
            assertTrue(extractor.getSubtitlesDefault().isEmpty());
            assertEquals(1, extractor.getVideoStreams().size());
        }
    }

    @Test
    void shortsDetailsCaptionsKeepManualAndGeneratedLanguagesDistinct() throws Exception {
        final String details = shortDetails().replace("\"processed_description\":",
                "\"cc\":[null,{},"
                        + "{\"language_code\":\"en-auto\",\"language\":\"English\","
                        + "\"url\":\"https://cdn.example/automatic.vtt\"},"
                        + "{\"language_code\":\"en\",\"language\":\"English\","
                        + "\"url\":\"https://cdn.example/manual.vtt\"},"
                        + "{\"language_code\":\"en-auto\",\"language\":\"English\","
                        + "\"url\":\"https://cdn.example/automatic.vtt\"},"
                        + "{\"language_code\":\"-auto\",\"url\":\"https://cdn.example/bad.vtt\"},"
                        + "{\"language_code\":\"de\",\"url\":\"\"}],"
                        + "\"processed_description\":");
        NewPipe.init(new MediaFixtureDownloader(
                "https://rumble.com/shorts/v7g09by", 446493256, details));
        final var extractor = Rumble.getStreamExtractor("https://rumble.com/shorts/v7g09by");
        extractor.fetchPage();
        final var subtitles = extractor.getSubtitlesDefault();
        assertEquals(2, subtitles.size());
        assertEquals("en", subtitles.get(0).getLanguageTag());
        assertEquals("en-auto.vtt", subtitles.get(0).getId());
        assertTrue(subtitles.get(0).isAutoGenerated());
        assertEquals("https://cdn.example/automatic.vtt", subtitles.get(0).getContent());
        assertEquals("en", subtitles.get(1).getLanguageTag());
        assertEquals("en.vtt", subtitles.get(1).getId());
        assertFalse(subtitles.get(1).isAutoGenerated());
        assertEquals(subtitles, extractor.getSubtitles(MediaFormat.VTT));
        assertTrue(extractor.getSubtitles(MediaFormat.TTML).isEmpty());
        assertEquals(1, extractor.getVideoStreams().size());
    }

    @Test
    void shortsLiveDetailsUseWatchingNowWithoutRecordedViewsOrTelemetry() throws Exception {
        for (final String watching : List.of("", "\"watching_now\":321,")) {
            final String details = shortDetails().replace("\"processed_description\":",
                    "\"live\":true,\"views\":999," + watching
                            + "\"upload_date\":\"2026-10-31T08:15:30+00:00\","
                            + "\"processed_description\":");
            final MediaFixtureDownloader downloader = new MediaFixtureDownloader(
                    "https://rumble.com/shorts/v7g09by", 446493256, details);
            NewPipe.init(downloader);
            final var extractor = Rumble.getStreamExtractor("https://rumble.com/shorts/v7g09by");
            extractor.fetchPage();
            assertEquals(StreamType.LIVE_STREAM, extractor.getStreamType());
            assertEquals(watching.isEmpty() ? -1 : 321, extractor.getViewCount());
            assertNull(extractor.getUploadDate());
            assertEquals(1, extractor.getVideoStreams().size());
            assertTrue(downloader.urls.stream()
                    .noneMatch(url -> url.contains("video.watching-now")));
        }
    }

    private static String shortDetails() {
        return "{\"id\":446493256,\"url\":\"https://rumble.com/shorts/v7g09by\","
                + "\"title\":\"Fixture Short\",\"description\":\"Details description\","
                + "\"processed_description\":\"Fallback description\","
                + "\"by\":{\"name\":\"Example\",\"url\":\"https://rumble.com/c/Example\"},"
                + "\"videos\":[{\"url\":\"https://cdn.example/short.mp4?token=preserved\","
                + "\"res\":720,\"bitrate_kbps\":1250}]}";
    }

    private static String relatedShort(final long id, final String permalink,
                                       final boolean isShort) {
        return "{\"object_type\":\"video\",\"is_short\":" + isShort
                + ",\"id\":" + id + ",\"permalink_id\":\"" + permalink + "\","
                + "\"url\":\"https://rumble.com/shorts/" + permalink + "\","
                + "\"title\":\"Related Short\",\"by\":{\"name\":\"Example\","
                + "\"type\":\"channel\",\"id\":\"_c1\","
                + "\"url\":\"https://rumble.com/c/Example\"}}";
    }

    @Test
    void commentChallengesFailDuringFetchInsteadOfBeingMarkedDisabled() throws Exception {
        NewPipe.init(new FixtureDownloader(403, "<title>Just a moment...</title>"));
        final var extractor = Rumble.getCommentsExtractor("https://rumble.com/embed/vtest123/");
        assertThrows(PvcCloudFlareChallengeException.class, extractor::fetchPage);
        assertThrows(PvcCloudFlareChallengeException.class, extractor::isCommentsDisabled);
    }

    @Test
    void commentResolutionPreservesChallengeParsingExceptions() throws Exception {
        NewPipe.init(new FixtureDownloader(403, "<title>Just a moment...</title>"));
        for (final String url : List.of("https://rumble.com/v7hnjfy-watch-slug.html",
                "https://rumble.com/shorts/v7g09by")) {
            final var extractor = Rumble.getCommentsExtractor(url);
            assertThrows(PvcCloudFlareChallengeException.class, extractor::getUrl);
        }
    }

    @Test
    void commentResolutionWrapsCheckedTransportFailuresWithTheirCause() throws Exception {
        for (final Exception cause : List.of(new IOException("Offline"),
                new ReCaptchaException("Captcha", "https://rumble.com/"))) {
            NewPipe.init(new Downloader() {
                @Override
                public Response execute(@Nonnull final Request request)
                        throws IOException, ReCaptchaException {
                    if (cause instanceof IOException) {
                        throw (IOException) cause;
                    }
                    throw (ReCaptchaException) cause;
                }
            });
            final var extractor = Rumble.getCommentsExtractor(
                    "https://rumble.com/v7hnjfy-transport-failure.html");
            final ParsingException error = assertThrows(ParsingException.class, extractor::getUrl);
            assertSame(cause, error.getCause());
        }
    }

    @Test
    void malformedCommentContinuationsFailWithoutReusingPreviousCommentsOrImages()
            throws Exception {
        useBodyOnlyComments();
        final String html = "<ul class='comments-1'>" + comment(1, "")
                .replace("<div class='comments-meta'>",
                        "<i class='user-image user-image--img--id-avatar'></i>"
                                + "<div class='comments-meta'>") + "</ul>";
        final byte[] valid = legacyCommentsBody(html,
                "i.user-image--img--id-avatar {background-image: "
                        + "url(https://cdn.example/avatar.png)}");
        for (final String broken : new String[]{null, "not JSON", "[]", "{}",
                "{\"html\":null,\"css_libs\":\"\"}",
                "{\"html\":[],\"css_libs\":\"\"}",
                "{\"html\":\"<ul class='comments-1'></ul>\"}",
                "{\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":null}",
                "{\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":[]}",
                "{\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":{\"css\":3}}",
                "{\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":\"\","
                        + "\"errors\":[{\"message\":\"Unavailable\",\"code\":0}]}",
                "{\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":\"\","
                        + "\"error\":\"Unavailable\"}",
                "{\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":\"\","
                        + "\"data\":{\"success\":false}}",
                "<title>Just a moment</title><script src='/challenge-platform/'></script>"}) {
            final var extractor = (RumbleCommentsExtractor) Rumble.getCommentsExtractor(
                    "https://rumble.com/embed/v7fh6ig/");
            final var first = extractor.getPage(new Page("1", valid));
            assertEquals("1", first.getItems().get(0).getCommentId());
            assertEquals("https://cdn.example/avatar.png",
                    first.getItems().get(0).getUploaderAvatars().get(0).getUrl());
            final byte[] body = broken == null ? null : broken.getBytes(StandardCharsets.UTF_8);
            assertThrows(ExtractionException.class,
                    () -> extractor.getPage(new Page("1", body)), String.valueOf(broken));
            assertNull(extractor.getComments(new int[]{0}));
            assertNull(extractor.getImage(org.jsoup.Jsoup.parse(html).selectFirst("li")));
            assertThrows(ExtractionException.class, extractor::isCommentsDisabled);
            final var empty = extractor.getPage(new Page("1",
                    legacyCommentsBody("<ul class='comments-1'></ul>", "")));
            assertTrue(empty.getItems().isEmpty());
            assertFalse(extractor.isCommentsDisabled());
        }
    }

    @Test
    void legacyCommentStylesheetObjectsAndStringsPreserveAvatarsAndMetadata() throws Exception {
        useBodyOnlyComments();
        final String css = "i.user-image--img--id-avatar {background-image: "
                + "url(https://cdn.example/avatar.png)}";
        final JsonObject stylesheets = new JsonObject();
        stylesheets.put("first", "/* no external fetch */");
        stylesheets.put("second", css);
        final String html = "<ul class='comments-1'>" + comment(1, "")
                .replace("<div class='comments-meta'>",
                        "<i class='user-image user-image--img--id-avatar'></i>"
                                + "<div class='comments-meta'><span class='pinned-text'>Pinned</span>"
                                + "<div class='comments-meta-user-badges'>"
                                + "<img alt='Verified'></div>")
                .replace("class='comments-meta-author'",
                        "class='comments-meta-author comments-meta-author-video-owner'")
                .replace(">1</span>", ">7</span>") + "</ul>";
        final String original = "https://rumble.com/v7hnjfy-comments-original.html#t=1";
        for (final Object stylesheet : List.of(css, stylesheets)) {
            final var extractor = Rumble.getCommentsExtractor(original);
            final var page = extractor.getPage(new Page("1",
                    legacyCommentsBody(html, stylesheet)));
            assertTrue(page.getErrors().isEmpty(), page.getErrors().toString());
            final var item = page.getItems().get(0);
            assertEquals(original, item.getUrl());
            assertTrue(item.isPinned());
            assertTrue(item.isChannelOwner());
            assertTrue(item.isUploaderVerified());
            assertEquals(7, item.getLikeCount());
            assertEquals("https://cdn.example/avatar.png", item.getUploaderAvatars().get(0).getUrl());
        }
    }

    @Test
    void onlyStructurallyValidLegacyEnvelopesCanIndicateDisabledComments() throws Exception {
        useBodyOnlyComments();
        for (final Object stylesheet : List.of("", new JsonObject())) {
            final var extractor = Rumble.getCommentsExtractor("https://rumble.com/embed/v7fh6ig/");
            final var enabled = extractor.getPage(new Page("1",
                    legacyCommentsBody("<ul class='comments-1'></ul>", stylesheet)));
            assertTrue(enabled.getItems().isEmpty());
            assertFalse(extractor.isCommentsDisabled());
            final var disabled = extractor.getPage(new Page("1",
                    legacyCommentsBody("<div>Comments unavailable</div>", stylesheet)));
            assertTrue(disabled.getItems().isEmpty());
            assertTrue(extractor.isCommentsDisabled());
        }
        for (final String body : new String[]{null, "not JSON", "{}",
                "{\"errors\":[{\"message\":\"Unavailable\",\"code\":0}]}",
                "{\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":3}"}) {
            NewPipe.init(new FixtureDownloader(200, body));
            final var extractor = Rumble.getCommentsExtractor("https://rumble.com/embed/v7fh6ig/");
            assertThrows(ExtractionException.class, extractor::fetchPage);
            assertThrows(ExtractionException.class, extractor::isCommentsDisabled);
        }
    }

    private static void useBodyOnlyComments() {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                throw new AssertionError("Body-backed comments must not fetch " + request.url());
            }
        });
    }

    private static byte[] legacyCommentsBody(final String html, final Object css) {
        final JsonObject response = new JsonObject();
        response.put("html", html);
        response.put("css_libs", css);
        response.put("data", null);
        response.put("js_libs", "https://example.invalid/must-not-load.js");
        return JsonWriter.string(response).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void commentsPaginationAndRepliesSurviveFreshExtractors() throws Exception {
        NewPipe.init(new FixtureDownloader(200,
                "<iframe src=\"https://rumble.com/embed/vcommentembed/\"></iframe>"));
        final StringBuilder html = new StringBuilder("<ul class='comments-1'>");
        for (int i = 1; i <= 16; i++) {
            html.append(comment(i, i == 1
                    ? "<div class='comment-replies'><ul class='comments-2'>"
                            + comment(101, "") + "</ul></div>" : ""));
        }
        html.append("</ul>");
        final JsonObject response = new JsonObject();
        response.put("html", html.toString());
        response.put("css_libs", "");
        final byte[] body = JsonWriter.string(response).getBytes(StandardCharsets.UTF_8);
        final var firstExtractor = Rumble.getCommentsExtractor("https://rumble.com/vcomments-test.html");
        final var first = firstExtractor.getPage(new Page("1", body));
        assertEquals(15, first.getItems().size());
        assertTrue(first.getErrors().isEmpty(), first.getErrors().toString());
        assertEquals("1", first.getItems().get(0).getCommentId());
        assertEquals("16", first.getNextPage().getUrl());
        assertEquals("https://rumble.com/vcomments-test.html", first.getItems().get(0).getUrl());
        final var freshExtractor = Rumble.getCommentsExtractor(first.getItems().get(0).getUrl());
        final var last = freshExtractor.getPage(first.getNextPage());
        assertEquals(1, last.getItems().size());
        assertEquals("16", last.getItems().get(0).getCommentId());
        assertFalse(last.hasNextPage());
        final var replies = freshExtractor.getPage(first.getItems().get(0).getReplies());
        assertEquals("101", replies.getItems().get(0).getCommentId());
        assertFalse(replies.hasNextPage());
    }

    private static String comment(final int id, final String replies) {
        return "<li class='comment-item' data-comment-id='" + id + "'>"
                + "<div class='comments-meta'><a class='comments-meta-author' href='/user/Example'>"
                + "Example</a><a class='comments-meta-post-time' "
                + "title='Monday, October 5, 2026 1:00 PM +00'></a></div>"
                + "<p class='comment-text'>Comment " + id + "</p>"
                + "<div class='rumbles-vote'><span class='rumbles-up-votes'>1</span></div>"
                + replies + "</li>";
    }

    @Test
    void channelSearchHandlesBothCardLayoutsAndSkipsUnrelatedArticles() throws Exception {
        NewPipe.init(new FixtureDownloader(200,
                "<style>i.user-image--img--id-0 {background-image: "
                        + "url(https://cdn.example/avatar.jpg);}</style>"
                        + "<article>Advertisement</article>"
                        + "<article class='channel-item'><a class='channel-item--a' href='/c/Legacy'>"
                        + "<i class='user-image user-image--img user-image--img--id-0'></i>"
                        + "<h3 class='channel-item--title'>Legacy"
                        + "<svg class='verification-badge-icon'></svg></h3>"
                        + "<span class='channel-item--subscribers'>1,200 subscribers</span>"
                        + "</a></article>"
                        + "<article><a href='/c/Modern?tracking=value'><h3>"
                        + "<span class='block truncate'>Modern</span>"
                        + "<svg class='verification-badge-icon'><title>Verified</title></svg>"
                        + "</h3></a>"
                        + "<span class='text-sm text-fjord'>2,000 followers</span>"
                        + "<i class='user-image user-image--letter'></i></article>"));
        final var channels = org.schabi.newpipe.extractor.services.DefaultSearchExtractorTest
                .getFilterItem(Rumble,
                        org.schabi.newpipe.extractor.services.rumble.search.filter.RumbleFilters
                                .ID_CF_MAIN_CHANNELS);
        final var mostRecent = org.schabi.newpipe.extractor.services.DefaultSearchExtractorTest
                .getFilterItem(Rumble,
                        org.schabi.newpipe.extractor.services.rumble.search.filter.RumbleFilters
                                .ID_SF_SORT_BY_MOST_RECENT);
        final var extractor = Rumble.getSearchExtractor("space", List.of(channels),
                List.of(mostRecent));
        extractor.fetchPage();
        final var page = extractor.getInitialPage();
        assertEquals(2, page.getItems().size());
        assertTrue(page.getErrors().isEmpty(), page.getErrors().toString());
        final var legacy = (org.schabi.newpipe.extractor.channel.ChannelInfoItem) page.getItems().get(0);
        assertEquals("https://rumble.com/c/Legacy", legacy.getUrl());
        assertEquals(1200, legacy.getSubscriberCount());
        assertTrue(legacy.isVerified());
        assertEquals("https://cdn.example/avatar.jpg", legacy.getThumbnails().get(0).getUrl());
        final var modern = (org.schabi.newpipe.extractor.channel.ChannelInfoItem) page.getItems().get(1);
        assertEquals("https://rumble.com/c/Modern", modern.getUrl());
        assertEquals("Modern", modern.getName());
        assertTrue(modern.isVerified());
        assertEquals(2000, modern.getSubscriberCount());
        assertTrue(modern.getThumbnails().isEmpty());
    }

    @Test
    void channelWithoutABannerReturnsNoImages() throws Exception {
        final CreatorFixtureDownloader downloader =
                new CreatorFixtureDownloader("https://rumble.com/c/Example");
        downloader.channelHtml =
                "<title>Example</title><div data-slug='Example' data-type='channel'></div>";
        NewPipe.init(downloader);
        final var extractor = Rumble.getChannelExtractor("https://rumble.com/c/Example");
        extractor.fetchPage();
        assertEquals("c/Example", extractor.getId());
        assertTrue(extractor.getBanners().isEmpty());
    }

    @Test
    void creatorMetadataUsesJsonAndKeepsPublicIdentityAndTabs() throws Exception {
        for (final String type : List.of("channel", "user")) {
            final String path = type.equals("channel") ? "c" : "user";
            final String typedId = type.equals("channel") ? "_c1330192" : "_uAlphanum";
            final String url = "https://rumble.com/" + path + "/MetadataCreator";
            final String identity = "\"type\":\"" + type + "\",\"id\":\"" + typedId
                    + "\",\"slug\":\"MetadataCreator\",\"url\":\"" + url + "\"";
            final CreatorFixtureDownloader downloader = new CreatorFixtureDownloader(url);
            downloader.searchBody = "{\"data\":{\"items\":[{" + identity + "}]}}";
            downloader.metadataBody = "{\"data\":{" + identity
                    + ",\"title\":\"Creator title\",\"thumb\":\"https://cdn.example/avatar.jpg\""
                    + ",\"backsplash\":\"https://cdn.example/banner.jpg\""
                    + ",\"description\":\"Creator description\",\"followers\":12345"
                    + ",\"verified_badge\":true}}";
            NewPipe.init(downloader);
            final var extractor = Rumble.getChannelExtractor(url);
            extractor.fetchPage();
            assertEquals(path + "/MetadataCreator", extractor.getId());
            assertEquals(url, extractor.getUrl());
            assertEquals(url, extractor.getOriginalUrl());
            assertEquals("Creator title", extractor.getName());
            assertEquals("https://cdn.example/avatar.jpg", extractor.getAvatars().get(0).getUrl());
            assertEquals("https://cdn.example/banner.jpg", extractor.getBanners().get(0).getUrl());
            assertEquals("Creator description", extractor.getDescription());
            assertEquals(12345, extractor.getSubscriberCount());
            assertTrue(extractor.isVerified());
            assertEquals(List.of(url + "/livestreams", url + "/videos"),
                    extractor.getTabs().stream().map(tab -> tab.getUrl()).sorted()
                            .collect(java.util.stream.Collectors.toList()));
            for (final var tab : extractor.getTabs()) {
                assertEquals(path + "/MetadataCreator", tab.getId());
            }
        }
    }

    @Test
    void absentOrNullCreatorMetadataDoesNotInventImagesOrCounts() throws Exception {
        final String url = "https://rumble.com/c/OptionalCreator";
        final String identity = "\"type\":\"channel\",\"id\":\"_c1001\","
                + "\"url\":\"" + url + "\",\"slug\":\"OptionalCreator\"";
        final CreatorFixtureDownloader downloader = new CreatorFixtureDownloader(url);
        downloader.searchBody = "{\"data\":{\"items\":[{" + identity + "}]}}";
        for (final String optional : List.of("", ",\"thumb\":null,\"backsplash\":\"\","
                + "\"description\":null,\"followers\":null,\"verified_badge\":null")) {
            downloader.metadataBody = "{\"data\":{" + identity
                    + ",\"title\":\"Optional creator\"" + optional + "}}";
            NewPipe.init(downloader);
            final var extractor = Rumble.getChannelExtractor(url);
            extractor.fetchPage();
            assertEquals("Optional creator", extractor.getName());
            assertTrue(extractor.getAvatars().isEmpty());
            assertTrue(extractor.getBanners().isEmpty());
            assertEquals("", extractor.getDescription());
            assertEquals(-1, extractor.getSubscriberCount());
            assertFalse(extractor.isVerified());
        }
    }

    @Test
    void exactCreatorSearchMissRetainsHtmlAndAboutMetadata() throws Exception {
        final String url = "https://rumble.com/c/HtmlCreator";
        final CreatorFixtureDownloader downloader = new CreatorFixtureDownloader(url);
        downloader.searchBody = "{\"data\":{\"items\":["
                + "{\"type\":\"user\",\"id\":\"_u123\",\"slug\":\"HtmlCreator\"},"
                + "{\"type\":\"channel\",\"id\":\"_c124\",\"slug\":\"HtmlCreatorExtra\"}]}}";
        downloader.channelHtml = "<title>HTML creator</title>"
                + "<div data-slug='OtherSlug' data-type='channel'></div>"
                + "<a href='/c/HtmlCreator/about'>About</a>";
        downloader.aboutHtml = "<div class='channel-about--description'>"
                + "<p>First paragraph</p><p>Second paragraph</p></div>";
        NewPipe.init(downloader);
        final var extractor = Rumble.getChannelExtractor(url);
        extractor.fetchPage();
        assertEquals("c/HtmlCreator", extractor.getId());
        assertEquals(url, extractor.getUrl());
        assertEquals("First paragraph\n\nSecond paragraph", extractor.getDescription());
        assertEquals(List.of(url, url + "/about"),
                downloader.urls.stream().filter(request -> request.startsWith(url))
                        .collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of(url + "/livestreams", url + "/videos"),
                extractor.getTabs().stream().map(tab -> tab.getUrl()).sorted()
                        .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void creatorApiFailuresNeverFallBackToHtml() throws Exception {
        final String url = "https://rumble.com/c/ApiFailureCreator";
        final String identity = "\"type\":\"channel\",\"id\":\"_c1002\","
                + "\"url\":\"" + url + "\",\"slug\":\"ApiFailureCreator\"";
        for (final boolean failSearch : List.of(true, false)) {
            final CreatorFixtureDownloader downloader = new CreatorFixtureDownloader(url);
            final String error = "{\"errors\":[{\"message\":\"Creator API refused\",\"code\":7}]}";
            downloader.searchBody = failSearch ? error
                    : "{\"data\":{\"items\":[{" + identity + "}]}}";
            downloader.metadataBody = error;
            NewPipe.init(downloader);
            final var extractor = Rumble.getChannelExtractor(url);
            final var failure = assertThrows(ContentNotAvailableException.class,
                    extractor::fetchPage);
            assertTrue(failure.getMessage().contains("Creator API refused"));
        }
    }

    @Test
    void emptyShortsFeedHasNoNextPage() throws Exception {
        NewPipe.init(new FixtureDownloader(200,
                "{\"data\":{\"items\":[],\"has_more\":false}}"));
        final var extractor = shorts();
        extractor.fetchPage();
        assertFalse(extractor.getInitialPage().hasNextPage());
    }

    @Test
    void pagesKeepTheFinalUrlForResolvingRelativeLinks() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader(200,
                "<link rel='next' href='?page=2'><a href='/c/Example/about'>About</a>");
        downloader.latestUrl = "https://rumble.com/c/Example/videos";
        final var doc = RumbleParsingHelper.fetchParseValidate(downloader,
                "https://rumble.com/c/Example");
        assertEquals("https://rumble.com/c/Example/videos?page=2",
                new RumbleCommonCodeTrendingAndSearching(new RumbleSearchTrendingItemsExtractorImpl())
                        .getNewPageIfThereAreMoreThanOnePageResults(1, doc).getUrl());
        assertEquals("https://rumble.com/c/Example/about", doc.selectFirst("a").absUrl("href"));
    }

    @Test
    void otherHttpErrorsCannotMasqueradeAsEmptyResults() {
        for (final int status : new int[]{403, 404, 429, 500, 503}) {
            final FixtureDownloader downloader = new FixtureDownloader(status,
                    "<title>Unavailable</title>");
            assertThrows(ContentNotAvailableException.class,
                    () -> RumbleParsingHelper.fetchResponse(downloader, "https://rumble.com/"));
        }
    }

    @Test
    void serviceUrlsEncodeNamesKeysAndValuesWithoutImplicitApiVersions() {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("api", "8");
        parameters.put("query &", "space/世界?x=1&y=2");
        assertEquals("https://rumble.com/service.php?name=media.details+%2F%26&api=8"
                        + "&query+%26=space%2F%E4%B8%96%E7%95%8C%3Fx%3D1%26y%3D2",
                RumbleApi.serviceUrl("media.details /&", parameters));
        assertEquals("https://rumble.com/service.php?name=comment.list",
                RumbleApi.serviceUrl("comment.list", Map.of()));
    }

    @Test
    void rootEnvelopesRemainUsableButDataConsumersRequireAnObject() throws Exception {
        for (final String body : List.of("{}", "{\"data\":null}",
                "{\"data\":[]}", "{\"data\":\"wrong\"}")) {
            final FixtureDownloader downloader = new FixtureDownloader(200, body);
            RumbleApi.fetchJson(downloader, "https://rumble.com/service.php", null);
            assertThrows(ParsingException.class,
                    () -> RumbleApi.fetchData(downloader, "https://rumble.com/service.php", null),
                    body);
        }
        final FixtureDownloader legacy = new FixtureDownloader(200,
                "{\"data\":null,\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":{}}");
        assertEquals("<ul class='comments-1'></ul>",
                RumbleApi.fetchJson(legacy, "https://rumble.com/service.php", null)
                        .getString("html"));
        final FixtureDownloader empty = new FixtureDownloader(200,
                "{\"errors\":[],\"error\":\"\",\"data\":{\"items\":[]}}");
        assertTrue(RumbleApi.fetchData(empty, "https://rumble.com/service.php", null)
                .getArray("items").isEmpty());
    }

    @Test
    void apiFailuresRetainTheirMessageAndCodeInsteadOfBecomingEmptyResults() {
        for (final String body : List.of(
                "{\"errors\":[{\"code\":0,\"message\":\"Video not found\"}]}",
                "{\"error\":{\"code\":0,\"message\":\"Video not found\"}}",
                "{\"data\":{\"success\":false,"
                        + "\"error\":{\"code\":0,\"message\":\"Video not found\"}}}")) {
            final var error = assertThrows(ContentNotAvailableException.class,
                    () -> RumbleApi.fetchData(new FixtureDownloader(200, body),
                            "https://rumble.com/service.php", null), body);
            assertEquals(ContentNotAvailableException.class, error.getClass());
            assertTrue(error.getMessage().contains("Video not found"));
            assertTrue(error.getMessage().contains("0"));
        }
        for (final String body : List.of("{\"error\":\"Request refused\"}",
                "{\"data\":{\"success\":false,\"error\":\"Request refused\"}}")) {
            final var error = assertThrows(ContentNotAvailableException.class,
                    () -> RumbleApi.fetchJson(new FixtureDownloader(200, body),
                            "https://rumble.com/service.php", null));
            assertTrue(error.getMessage().contains("Request refused"));
        }
        final var unsuccessful = assertThrows(ContentNotAvailableException.class,
                () -> RumbleApi.fetchData(new FixtureDownloader(200,
                        "{\"data\":{\"success\":false}}"),
                        "https://rumble.com/service.php", null));
        assertTrue(unsuccessful.getMessage().contains("success=false"));
    }

    @Test
    void malformedJsonAndEnvelopeTypesCannotMasqueradeAsValidEmptyResults() {
        for (final String body : List.of("not json", "<html>Unavailable</html>", "[]",
                "{} {}", "{\"errors\":{}}", "{\"data\":{\"success\":\"false\"}}")) {
            assertThrows(ParsingException.class,
                    () -> RumbleApi.fetchJson(new FixtureDownloader(200, body),
                            "https://rumble.com/service.php", null), body);
        }
    }

    @Test
    void contentTypeAloneCannotValidateJsonAndMissingContentTypeIsAllowed() throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader(200, "{\"data\":{}}");
        RumbleApi.fetchData(downloader, "https://rumble.com/service.php", null);
        downloader.headers = Map.of("content-type", List.of("application/json; charset=utf-8"));
        RumbleApi.fetchData(downloader, "https://rumble.com/service.php", null);
        downloader.body = "<html>Not JSON</html>";
        assertThrows(ParsingException.class,
                () -> RumbleApi.fetchData(downloader, "https://rumble.com/service.php", null));
        downloader.headers = Map.of("Content-Type", List.of("text/html"));
        assertThrows(ParsingException.class,
                () -> RumbleApi.fetchJson(downloader, "https://rumble.com/service.php", null));
    }

    @Test
    void explicitChallengeHeadersApplyToSuccessfulAndFailedResponses() {
        for (final int status : new int[]{200, 403, 429, 503}) {
            final FixtureDownloader downloader = new FixtureDownloader(status, "{}");
            downloader.headers = Map.of("CF-Mitigated", List.of("challenge"));
            assertThrows(PvcCloudFlareChallengeException.class,
                    () -> RumbleApi.fetchJson(downloader, "https://rumble.com/service.php", null));
        }
    }

    @Test
    void successfulChallengeHtmlIsRecognizedBeforeJsonParsingAndDoesNotPoisonLaterRequests()
            throws Exception {
        final FixtureDownloader downloader = new FixtureDownloader(200,
                "<title>Just a moment...</title><script src='/cdn-cgi/challenge-platform/x'>"
                        + "</script>");
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> RumbleApi.fetchData(downloader, "https://rumble.com/service.php", null));
        downloader.body = "{\"data\":{\"items\":[]}}";
        assertTrue(RumbleApi.fetchData(downloader, "https://rumble.com/service.php", null)
                .getArray("items").isEmpty());
    }

    @Test
    void successfulResponsesNeedBothChallengeMarkersWithinEightKiB() throws Exception {
        for (final String body : List.of("Just a moment", "challenge-platform",
                "x".repeat(8192) + "Just a moment challenge-platform",
                "é".repeat(4096) + "Just a moment challenge-platform")) {
            RumbleParsingHelper.fetchResponse(new FixtureDownloader(200, body),
                    "https://rumble.com/");
            assertThrows(ParsingException.class,
                    () -> RumbleApi.fetchJson(new FixtureDownloader(200, body),
                            "https://rumble.com/service.php", null));
        }
        assertThrows(PvcCloudFlareChallengeException.class,
                () -> RumbleParsingHelper.fetchResponse(new FixtureDownloader(200,
                        "é".repeat(4000) + "JUST A MOMENT CHALLENGE-PLATFORM"),
                        "https://rumble.com/"));
    }

    @Test
    void privateAndOrdinaryHttpRefusalsRetainTheirClassification() {
        assertThrows(PrivateContentException.class,
                () -> RumbleParsingHelper.fetchResponse(new FixtureDownloader(403,
                        "<title>This video is private</title>"), "https://rumble.com/"));
        for (final int status : new int[]{403, 429}) {
            final var error = assertThrows(ContentNotAvailableException.class,
                    () -> RumbleApi.fetchJson(new FixtureDownloader(status,
                            "<title>Unavailable</title>"), "https://rumble.com/service.php", null));
            assertEquals(ContentNotAvailableException.class, error.getClass());
            assertTrue(error.getMessage().contains(Integer.toString(status)));
        }
    }

    private static org.schabi.newpipe.extractor.kiosk.KioskExtractor<?> shorts() throws Exception {
        return Rumble.getKioskList().getExtractorById(RumbleTrendingShortsExtractor.KIOSK_SHORTS, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> mediaIds() {
        try {
            final Field field = RumbleMediaResolver.class.getDeclaredField("VIDEO_IDS_CACHE");
            field.setAccessible(true);
            return (Map<String, Long>) field.get(null);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> creatorIds() {
        try {
            final Field field = RumbleCreatorResolver.class.getDeclaredField("CREATOR_IDS_CACHE");
            field.setAccessible(true);
            return (Map<String, String>) field.get(null);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class CreatorFixtureDownloader extends Downloader {
        private final String publicUrl;
        private final List<String> urls = new ArrayList<>();
        private String searchBody = "{\"data\":{\"items\":[]}}";
        private String metadataBody;
        private String channelHtml;
        private String aboutHtml;

        private CreatorFixtureDownloader(final String publicUrl) {
            this.publicUrl = publicUrl;
        }

        @Override
        public Response execute(@Nonnull final Request request) {
            urls.add(request.url());
            final URI uri = URI.create(request.url());
            final String body;
            if ("/service.php".equals(uri.getPath())) {
                final String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
                assertTrue(query.contains("api=8"));
                if (query.startsWith("name=video_collection.search&")) {
                    assertTrue(query.contains("query=" + publicUrl.substring(
                            publicUrl.lastIndexOf('/') + 1)));
                    assertTrue(query.contains("offset=0"));
                    assertTrue(query.contains("limit=20"));
                    body = searchBody;
                } else if (query.startsWith("name=video_collection.meta&")) {
                    body = metadataBody;
                } else {
                    throw new AssertionError("Unexpected creator API request: " + request.url());
                }
            } else if (publicUrl.equals(request.url())) {
                body = channelHtml;
            } else if ((publicUrl + "/about").equals(request.url())) {
                body = aboutHtml;
            } else {
                throw new AssertionError("Unexpected creator request: " + request.url());
            }
            if (body == null) {
                throw new AssertionError("Unexpected creator transport: " + request.url());
            }
            return new Response(200, "Fixture", Map.of(), body, request.url());
        }
    }

    private static final class MediaFixtureDownloader extends Downloader {
        private final String expectedPublicUrl;
        private final long numericId;
        private final String detailsJson;
        private final List<String> urls = new ArrayList<>();
        private String feedJson = "{\"data\":{\"items\":[],\"has_more\":false}}";

        private MediaFixtureDownloader(final String expectedPublicUrl, final long numericId,
                                       final String detailsJson) {
            this.expectedPublicUrl = expectedPublicUrl;
            this.numericId = numericId;
            this.detailsJson = detailsJson;
        }

        @Override
        public Response execute(@Nonnull final Request request) {
            urls.add(request.url());
            final Map<String, String> query = new LinkedHashMap<>();
            final String rawQuery = URI.create(request.url()).getRawQuery();
            if (rawQuery != null) {
                for (final String parameter : rawQuery.split("&")) {
                    final String[] pair = parameter.split("=", 2);
                    query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            pair.length == 1 ? ""
                                    : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
            final String body;
            if ("media.details".equals(query.get("name"))) {
                assertEquals("8", query.get("api"));
                assertEquals("video.full,extended,with_cc,categories,comments,"
                        + "video.related_video,video.dthumbs", query.get("options"));
                if (query.containsKey("id")) {
                    assertEquals(Long.toString(numericId), query.get("id"));
                } else {
                    assertEquals(expectedPublicUrl, query.get("url"));
                }
                body = "{\"data\":" + detailsJson + "}";
            } else if ("shorts.feed".equals(query.get("name"))) {
                assertEquals("7", query.get("api"));
                assertEquals("0", query.get("offset"));
                assertEquals("10", query.get("limit"));
                assertEquals("video.full,video.related_video", query.get("options"));
                if (feedJson == null) {
                    throw new AssertionError("Related Shorts must be cached");
                }
                body = feedJson;
            } else if ("comment.list".equals(query.get("name"))) {
                assertEquals(Long.toString(numericId, 36), query.get("video"));
                body = "{\"html\":\"<ul class='comments-1'></ul>\",\"css_libs\":\"\"}";
            } else if (URI.create(request.url()).getPath().startsWith("/shorts/")) {
                return new Response(403, "Challenge", Map.of("cf-mitigated",
                        List.of("challenge")), "<title>Just a moment</title>", request.url());
            } else {
                throw new AssertionError("Unexpected request: " + request.url());
            }
            return new Response(200, "Fixture", Map.of(), body, request.url());
        }
    }

    private static final class FixtureDownloader extends Downloader {
        private final int code;
        private String body;
        private String latestUrl;
        private Map<String, List<String>> headers = Map.of();
        private final List<String> urls = new ArrayList<>();

        private FixtureDownloader(final int code, final String body) {
            this.code = code;
            this.body = body;
        }

        @Override
        public Response execute(@Nonnull final Request request) {
            urls.add(request.url());
            return new Response(code, "Fixture", headers, body,
                    latestUrl == null ? request.url() : latestUrl);
        }
    }
}
