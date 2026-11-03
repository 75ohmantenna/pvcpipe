package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.rumble.RumbleCreatorResolver;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleTrendingLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.rumble.settings.RumbleSettings;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;

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
class RumblePaginationTest {
    private static final List<String> JSON_KIOSKS = List.of(
            RumbleTrendingShortsExtractor.KIOSK_SHORTS, RumbleTrendingLinkHandlerFactory.LIVE);
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;
    private boolean previousHidePremium;
    private Map<String, Long> previousMedia;
    private Map<String, String> previousCreators;

    @BeforeEach
    void saveState() throws Exception {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        previousHidePremium = ServiceList.Rumble.getServiceSettings()
                .isSettingEnabled(RumbleSettings.HIDE_PREMIUM_STREAMS);
        ServiceList.Rumble.getServiceSettings().setting(true, RumbleSettings.HIDE_PREMIUM_STREAMS);
        previousMedia = clearCache(mediaIds());
        previousCreators = clearCache(creatorIds());
    }

    @AfterEach
    void restoreState() throws Exception {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
        ServiceList.Rumble.getServiceSettings().setting(previousHidePremium,
                RumbleSettings.HIDE_PREMIUM_STREAMS);
        restoreCache(mediaIds(), previousMedia);
        restoreCache(creatorIds(), previousCreators);
    }

    private final RumbleCommonCodeTrendingAndSearching helper =
            new RumbleCommonCodeTrendingAndSearching(new RumbleSearchTrendingItemsExtractorImpl());

    @Test
    void stopsPaginationWhenThereAreNoResults() {
        final Document doc = Jsoup.parse("<link rel='next' href='?page=2'>",
                "https://rumble.com/browse");
        assertNull(helper.getNewPageIfThereAreMoreThanOnePageResults(0, doc));
        assertNull(helper.getNewPageIfThereAreMoreThanOnePageResults(0, null));
    }

    @Test
    void stopsPaginationForMissingOrEmptyNextLinks() {
        for (final String html : new String[]{"", "<link rel='next'>",
                "<link rel='next' href=''>", "<link rel='prev' href='?page=1'>"}) {
            assertNull(helper.getNewPageIfThereAreMoreThanOnePageResults(1, Jsoup.parse(html)));
        }
    }

    @Test
    void resolvesNextLinksAgainstTheCurrentPage() {
        final String[][] links = {
                {"?page=2&sort=date", "https://rumble.com/browse?page=2&sort=date"},
                {"/browse?page=2", "https://rumble.com/browse?page=2"},
                {"https://rumble.com/browse?page=2&sort=date",
                        "https://rumble.com/browse?page=2&sort=date"}
        };
        for (final String[] link : links) {
            final Document doc = Jsoup.parse("<link rel='next' href='" + link[0] + "'>",
                    "https://rumble.com/browse");
            final Page page = helper.getNewPageIfThereAreMoreThanOnePageResults(1, doc);
            assertEquals(link[1], page.getUrl());
        }
    }

    @Test
    void keepsPaginationWhenCollectorsFilterEveryItem() throws Exception {
        final RumbleItemsExtractorImpl itemsExtractor = new RumbleSearchTrendingItemsExtractorImpl() {
            @Override
            public List<StreamInfoItemExtractor> extractStreamItems(final Document doc) {
                return List.of(new RumbleSearchVideoStreamInfoItemExtractor(
                        "Filtered", "https://rumble.com/v123-filtered.html", List.of(),
                        null, null, null, null, null, null, false, true));
            }
        };
        final RumbleCommonCodeTrendingAndChannel shared = new RumbleCommonCodeTrendingAndChannel(
                ServiceList.Rumble.getServiceId(), itemsExtractor);
        final Document doc = Jsoup.parse("<link rel='next' href='?page=2'>",
                "https://rumble.com/browse");

        final var streamPage = shared.extractAndGetStreamInfoItemsFromPage(doc);
        assertTrue(streamPage.getItems().isEmpty());
        assertEquals("https://rumble.com/browse?page=2", streamPage.getNextPage().getUrl());

        final var mixedPage = shared.extractAndGetInfoItemsFromPage(doc);
        assertTrue(mixedPage.getItems().isEmpty());
        assertEquals("https://rumble.com/browse?page=2", mixedPage.getNextPage().getUrl());
    }

    @Test
    void apiPagesAdvanceByRawRowsAndWorkOnFreshExtractors() throws Exception {
        for (final String kiosk : JSON_KIOSKS) {
            final ApiDownloader downloader = new ApiDownloader(kiosk);
            downloader.body = envelope(video(1, false) + ",{\"object_type\":\"ad\"},"
                    + "{\"object_type\":\"video\",\"title\":\"Missing URL\"},"
                    + video(2, false) + ",{\"object_type\":\"unknown\"}", true);
            NewPipe.init(downloader);
            final var extractor = kiosk(kiosk);
            extractor.fetchPage();
            if (RumbleTrendingLinkHandlerFactory.LIVE.equals(kiosk)) {
                assertEquals("https://rumble.com/browse/live", extractor.getUrl());
            }
            final var first = extractor.getInitialPage();
            assertEquals(2, first.getItems().size());
            assertFalse(first.getErrors().isEmpty());
            assertEquals("5", first.getNextPage().getId());
            final String smallLimitUrl = first.getNextPage().getUrl()
                    .replace("limit=10", "limit=3").replace("limit=20", "limit=3");
            downloader.body = envelope(video(3, false) + "," + video(4, false) + ","
                    + video(5, false) + "," + video(6, false) + "," + video(7, false), true);
            final var second = kiosk(kiosk).getPage(new Page(smallLimitUrl, "5"));
            assertEquals(5, second.getItems().size());
            assertEquals("10", second.getNextPage().getId());
            assertEquals(smallLimitUrl.replace("offset=5", "offset=10"),
                    second.getNextPage().getUrl());
            downloader.body = envelope("", false);
            final var terminal = kiosk(kiosk).getPage(second.getNextPage());
            assertTrue(terminal.getItems().isEmpty());
            assertNull(terminal.getNextPage());
            assertEquals(List.of("0", "5", "10"), downloader.offsets);
        }
    }

    @Test
    void apiPaginationSurvivesFilteringEveryRow() throws Exception {
        for (final String kiosk : JSON_KIOSKS) {
            final ApiDownloader downloader = new ApiDownloader(kiosk);
            downloader.body = envelope(video(1, true) + "," + video(2, true), true);
            NewPipe.init(downloader);
            final var extractor = kiosk(kiosk);
            extractor.fetchPage();
            final var first = extractor.getInitialPage();
            assertTrue(first.getItems().isEmpty());
            assertEquals("2", first.getNextPage().getId());
            downloader.body = envelope("", false);
            assertNull(kiosk(kiosk).getPage(first.getNextPage()).getNextPage());
        }
    }

    @Test
    void shortsRequireBooleanHasMoreAndRejectEmptyContinuations() throws Exception {
        for (final String body : List.of("{\"data\":{\"items\":[]}}",
                "{\"data\":{\"items\":[],\"has_more\":null}}",
                "{\"data\":{\"items\":[],\"has_more\":\"true\"}}", envelope("", true))) {
            final ApiDownloader downloader = new ApiDownloader(JSON_KIOSKS.get(0));
            NewPipe.init(downloader);
            final var extractor = kiosk(JSON_KIOSKS.get(0));
            extractor.fetchPage();
            final Page next = extractor.getInitialPage().getNextPage();
            downloader.body = body;
            assertThrows(ParsingException.class, () -> extractor.getPage(next));
            assertThrows(ParsingException.class, () -> kiosk(JSON_KIOSKS.get(0)).fetchPage());
        }
    }

    @Test
    void shortsFalseHasMoreEndsEvenWithRows() throws Exception {
        final ApiDownloader downloader = new ApiDownloader(JSON_KIOSKS.get(0));
        downloader.body = envelope(video(1, false), false);
        NewPipe.init(downloader);
        final var extractor = kiosk(JSON_KIOSKS.get(0));
        extractor.fetchPage();
        final var page = extractor.getInitialPage();
        assertEquals(1, page.getItems().size());
        assertNull(page.getNextPage());
    }

    @Test
    void liveUsesRawPagesWithoutRequiringHasMoreAndDispatchesByPageUrl() throws Exception {
        final String live = RumbleTrendingLinkHandlerFactory.LIVE;
        final ApiDownloader downloader = new ApiDownloader(live);
        downloader.body = "{\"data\":{\"items\":[" + video(1, false) + "]}}";
        NewPipe.init(downloader);
        final var extractor = kiosk(live);
        extractor.fetchPage();
        final Page next = extractor.getInitialPage().getNextPage();
        assertEquals("1", next.getId());
        downloader.body = "{\"data\":{\"items\":[]}}";
        final var terminal = kiosk(RumbleTrendingLinkHandlerFactory.DEFAULT_TRENDING).getPage(next);
        assertTrue(terminal.getItems().isEmpty());
        assertNull(terminal.getNextPage());
        assertEquals(List.of("0", "1"), downloader.offsets);
    }

    @Test
    void apiErrorsAreNotEmptySuccessfulKioskPages() throws Exception {
        for (final String kiosk : JSON_KIOSKS) {
            final ApiDownloader downloader = new ApiDownloader(kiosk);
            NewPipe.init(downloader);
            final var extractor = kiosk(kiosk);
            extractor.fetchPage();
            final Page next = extractor.getInitialPage().getNextPage();
            downloader.body = "{\"error\":\"Listing refused\"}";
            final var failure = assertThrows(
                    org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException.class,
                    () -> extractor.getPage(next));
            assertTrue(failure.getMessage().contains("Listing refused"));
            assertThrows(
                    org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException.class,
                    () -> kiosk(kiosk).fetchPage());
        }
    }

    @Test
    void malformedApiEnvelopesCannotReusePreviousItems() throws Exception {
        for (final String kiosk : JSON_KIOSKS) {
            for (final String invalid : List.of("{}", "{\"data\":[]}",
                    "{\"data\":{}}", "{\"data\":{\"items\":{}}}",
                    "{\"data\":{\"items\":null}}", "not json")) {
                final ApiDownloader downloader = new ApiDownloader(kiosk);
                NewPipe.init(downloader);
                final var extractor = kiosk(kiosk);
                extractor.fetchPage();
                final Page next = extractor.getInitialPage().getNextPage();
                downloader.body = invalid;
                assertThrows(ParsingException.class, () -> extractor.getPage(next));
                assertThrows(ParsingException.class, () -> kiosk(kiosk).getPage(next));
                assertThrows(ParsingException.class, () -> kiosk(kiosk).fetchPage());
            }
        }
    }

    @Test
    void invalidApiPagesFailWithoutFetchingOrReusingInitialItems() throws Exception {
        for (final String kiosk : JSON_KIOSKS) {
            final ApiDownloader downloader = new ApiDownloader(kiosk);
            NewPipe.init(downloader);
            final var extractor = kiosk(kiosk);
            extractor.fetchPage();
            assertThrows(ParsingException.class, () -> extractor.getPage(null));
            for (final Page invalid : List.of(new Page(""), new Page(List.of("id")),
                    new Page("not a URL", "1"),
                    new Page(extractor.getInitialPage().getNextPage().getUrl(), "-1"),
                    new Page(extractor.getInitialPage().getNextPage().getUrl(), "other"))) {
                assertThrows(ParsingException.class, () -> extractor.getPage(invalid));
            }
            assertEquals(List.of("0"), downloader.offsets);
        }
    }

    private static KioskExtractor<StreamInfoItem> kiosk(final String id) throws Exception {
        return ServiceList.Rumble.getKioskList().getExtractorById(id, null);
    }

    private static String video(final int id, final boolean premium) {
        return "{\"object_type\":\"video\",\"id\":" + id
                + ",\"url\":\"https://rumble.com/vtest" + id + "-fixture.html\","
                + "\"title\":\"Fixture " + id + "\",\"availability\":\""
                + (premium ? "premium" : "free") + "\",\"visibility\":\"public\"}";
    }

    private static String envelope(final String rows, final boolean more) {
        return "{\"data\":{\"items\":[" + rows + "],\"has_more\":" + more + "}}";
    }

    private static <T> Map<String, T> clearCache(final Map<String, T> cache) {
        synchronized (cache) {
            final Map<String, T> previous = new LinkedHashMap<>(cache);
            cache.clear();
            return previous;
        }
    }

    private static <T> void restoreCache(final Map<String, T> cache, final Map<String, T> previous) {
        synchronized (cache) {
            cache.clear();
            cache.putAll(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> mediaIds() throws Exception {
        final Field field = RumbleMediaResolver.class.getDeclaredField("VIDEO_IDS_CACHE");
        field.setAccessible(true);
        return (Map<String, Long>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> creatorIds() throws Exception {
        final Field field = RumbleCreatorResolver.class.getDeclaredField("CREATOR_IDS_CACHE");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    private static final class ApiDownloader extends Downloader {
        private final String kiosk;
        private final List<String> offsets = new ArrayList<>();
        private String body = envelope(video(1, false), true);

        private ApiDownloader(final String kiosk) {
            this.kiosk = kiosk;
        }

        @Override
        public Response execute(@Nonnull final Request request) {
            final URI uri = URI.create(request.url());
            assertEquals("/service.php", uri.getPath());
            final Map<String, String> query = new LinkedHashMap<>();
            for (final String parameter : uri.getRawQuery().split("&")) {
                final String[] pair = parameter.split("=", 2);
                query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            final boolean shorts = RumbleTrendingShortsExtractor.KIOSK_SHORTS.equals(kiosk);
            assertEquals(shorts ? "shorts.feed" : "video_collection.live", query.get("name"));
            assertEquals(shorts ? "7" : "8", query.get("api"));
            assertEquals(shorts ? "video.full,video.related_video" : "video.full",
                    query.get("options"));
            if (!shorts) {
                assertEquals("0", query.get("front"));
            }
            assertTrue(List.of(shorts ? "10" : "20", "3").contains(query.get("limit")));
            assertEquals(List.of("application/json"), request.headers().get("Accept"));
            assertTrue(request.headers().keySet().stream()
                    .noneMatch(key -> key.equalsIgnoreCase("Cookie")));
            offsets.add(query.get("offset"));
            return new Response(200, "Fixture", Map.of(), body, request.url());
        }
    }
}
