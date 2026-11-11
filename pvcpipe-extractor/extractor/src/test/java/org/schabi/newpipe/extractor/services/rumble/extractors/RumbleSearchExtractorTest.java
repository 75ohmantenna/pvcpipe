package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;
import org.schabi.newpipe.extractor.services.DefaultSearchExtractorTest;
import org.schabi.newpipe.extractor.services.rumble.RumbleApi;
import org.schabi.newpipe.extractor.services.rumble.RumbleCreatorResolver;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.services.rumble.search.filter.RumbleFilters;
import org.schabi.newpipe.extractor.services.rumble.settings.RumbleSettings;
import org.schabi.newpipe.extractor.utils.Utils;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@Tag("offline")
class RumbleSearchExtractorTest {
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;
    private boolean previousHidePremium;
    private Map<String, Long> previousMediaIds;
    private Map<String, String> previousCreatorIds;
    private FixtureDownloader downloader;

    @BeforeEach
    void saveState() throws Exception {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        previousHidePremium = Rumble.getServiceSettings()
                .isSettingEnabled(RumbleSettings.HIDE_PREMIUM_STREAMS);
        Rumble.getServiceSettings().setting(false, RumbleSettings.HIDE_PREMIUM_STREAMS);
        previousMediaIds = snapshotAndClear(mediaIds());
        previousCreatorIds = snapshotAndClear(creatorIds());
        downloader = new FixtureDownloader();
        NewPipe.init(downloader);
    }

    @AfterEach
    void restoreState() throws Exception {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
        Rumble.getServiceSettings().setting(previousHidePremium,
                RumbleSettings.HIDE_PREMIUM_STREAMS);
        restore(mediaIds(), previousMediaIds);
        restore(creatorIds(), previousCreatorIds);
    }

    @Test
    void defaultVideoSearchUsesApiAndRetainsPublicSearchUrls() throws Exception {
        downloader.apiBody = parameters -> {
            assertEquals("media.search", parameters.get("name"));
            assertEquals("8", parameters.get("api"));
            assertEquals("space & science", parameters.get("query"));
            assertEquals("video.full", parameters.get("options"));
            assertEquals("0", parameters.get("offset"));
            assertEquals("20", parameters.get("limit"));
            assertFalse(parameters.containsKey("sort"));
            assertFalse(parameters.containsKey("duration"));
            assertFalse(parameters.containsKey("video_type"));
            return videos(video(1), false);
        };
        final SearchExtractor extractor = search(RumbleFilters.ID_CF_MAIN_VIDEOS);
        assertTrue(extractor.getUrl().contains("/search/video?q=space+%26+science"));
        final var page = fetch(extractor);
        assertEquals("https://rumble.com/v0001-fixture.html", page.getItems().get(0).getUrl());
        assertNull(page.getNextPage());
    }

    @Test
    void sortAndDurationAreApiParametersNotContentTypes() throws Exception {
        final int[] sorts = {RumbleFilters.ID_SF_SORT_BY_MOST_RECENT,
                RumbleFilters.ID_SF_SORT_BY_RUMBLES, RumbleFilters.ID_SF_SORT_BY_VIEWS};
        final String[] expected = {"date", "rumbles", "views"};
        for (int i = 0; i < sorts.length; i++) {
            final String expectedSort = expected[i];
            for (final int duration : new int[]{RumbleFilters.ID_SF_DURATION_LONG,
                    RumbleFilters.ID_SF_DURATION_SHORT}) {
                downloader.apiBody = parameters -> {
                    assertEquals(expectedSort, parameters.get("sort"));
                    assertEquals(duration == RumbleFilters.ID_SF_DURATION_LONG ? "long" : "short",
                            parameters.get("duration"));
                    assertFalse(parameters.containsKey("video_type"));
                    return videos("", false);
                };
                assertTrue(fetch(search(RumbleFilters.ID_CF_MAIN_VIDEOS,
                        sorts[i], duration, RumbleFilters.ID_SF_UPLOAD_DATE_ALL))
                        .getItems().isEmpty());
            }
        }
    }

    @Test
    void rawFiveRowsAdvanceRequestedThreeAndFreshExtractorUsesWholeContinuation()
            throws Exception {
        downloader.apiBody = parameters -> {
            assertEquals("3", parameters.get("limit"));
            assertEquals("views", parameters.get("sort"));
            assertEquals("short", parameters.get("duration"));
            assertEquals("space & science", parameters.get("query"));
            assertEquals("8", parameters.get("api"));
            assertEquals("video.full", parameters.get("options"));
            return "0".equals(parameters.get("offset"))
                    ? videos(video(1) + "," + video(2) + "," + video(3) + ","
                            + video(4) + "," + video(5), true)
                    : videos(video(6), false);
        };
        final var first = search(RumbleFilters.ID_CF_MAIN_VIDEOS).getPage(apiPage(
                "media.search", 0, "3", Map.of("sort", "views", "duration", "short")));
        assertEquals(5, first.getItems().size());
        assertEquals("5", first.getNextPage().getId());
        assertEquals("5", query(first.getNextPage().getUrl()).get("offset"));
        final var next = search(RumbleFilters.ID_CF_MAIN_CHANNELS).getPage(first.getNextPage());
        assertEquals("https://rumble.com/v0006-fixture.html", next.getItems().get(0).getUrl());
        assertNull(next.getNextPage());
    }

    @Test
    void filteringEveryPremiumRowStillAdvancesByRawCount() throws Exception {
        Rumble.getServiceSettings().setting(true, RumbleSettings.HIDE_PREMIUM_STREAMS);
        downloader.apiBody = parameters -> videos(video(1).replace("\"title\"",
                "\"availability\":\"premium\",\"title\"") + ","
                + video(2).replace("\"title\"",
                "\"availability\":\"premium\",\"title\""), true);
        final var page = fetch(search(RumbleFilters.ID_CF_MAIN_VIDEOS));
        assertTrue(page.getItems().isEmpty());
        assertTrue(page.getErrors().isEmpty());
        assertEquals("2", page.getNextPage().getId());
    }

    @Test
    void unknownAndBrokenRowsCountWithoutDroppingLaterResults() throws Exception {
        downloader.apiBody = parameters -> videos("{\"object_type\":\"ad\"},null,"
                + "{\"object_type\":\"video\",\"title\":\"Missing URL\"}," + video(4) + ","
                + video(5).replace("\"title\"", "\"duration\":\"bad\",\"title\""), true);
        final var page = fetch(search(RumbleFilters.ID_CF_MAIN_VIDEOS));
        assertEquals(2, page.getItems().size());
        assertEquals("https://rumble.com/v0004-fixture.html", page.getItems().get(0).getUrl());
        assertFalse(page.getErrors().isEmpty());
        assertEquals("5", page.getNextPage().getId());
    }

    @Test
    void hasMoreIsRequiredBooleanAndCannotDescribeAnEmptyPage() {
        for (final String body : List.of("{\"data\":{\"items\":[]}}",
                "{\"data\":{\"items\":[],\"has_more\":null}}",
                "{\"data\":{\"items\":[],\"has_more\":1}}",
                "{\"data\":{\"items\":[],\"has_more\":\"false\"}}",
                videos("", true))) {
            downloader.apiBody = parameters -> body;
            assertThrows(ParsingException.class,
                    () -> fetch(search(RumbleFilters.ID_CF_MAIN_VIDEOS)), body);
        }
    }

    @Test
    void falseHasMoreEndsBothEmptyAndNonemptyPages() throws Exception {
        for (final String rows : List.of("", video(1))) {
            downloader.apiBody = parameters -> videos(rows, false);
            assertNull(fetch(search(RumbleFilters.ID_CF_MAIN_VIDEOS)).getNextPage());
        }
    }

    @Test
    void malformedRequiredArraysAndApiErrorsAreNotEmptySuccess() {
        for (final String body : List.of("{}", "{\"data\":[]}",
                "{\"data\":{\"has_more\":false}}",
                "{\"data\":{\"items\":null,\"has_more\":false}}",
                "{\"data\":{\"items\":{},\"has_more\":false}}", "not json")) {
            downloader.apiBody = parameters -> body;
            assertThrows(ParsingException.class,
                    () -> fetch(search(RumbleFilters.ID_CF_MAIN_VIDEOS)), body);
        }
        downloader.apiBody = parameters -> "{\"errors\":[{\"message\":\"Unavailable\"}]}";
        assertThrows(ContentNotAvailableException.class,
                () -> fetch(search(RumbleFilters.ID_CF_MAIN_VIDEOS)));
    }

    @Test
    void creatorsUseApiEightTypedIdentitiesAndFreshContinuationUntilEmpty() throws Exception {
        downloader.apiBody = parameters -> {
            assertEquals("video_collection.search", parameters.get("name"));
            assertEquals("8", parameters.get("api"));
            assertFalse(parameters.containsKey("options"));
            return "0".equals(parameters.get("offset"))
                    ? "{\"data\":{\"items\":[" + creator("channel", "_c123", true) + ","
                            + creator("user", "_uAlpha9", false) + "]}}"
                    : "{\"data\":{\"items\":[]}}";
        };
        final SearchExtractor extractor = search(RumbleFilters.ID_CF_MAIN_CHANNELS);
        assertTrue(extractor.getUrl().contains("/search/channel?q="));
        final var first = fetch(extractor);
        assertEquals(2, first.getItems().size());
        final ChannelInfoItem channel = (ChannelInfoItem) first.getItems().get(0);
        assertEquals("https://rumble.com/c/SharedSlug", channel.getUrl());
        assertEquals("Title", channel.getName());
        assertTrue(channel.getThumbnails().isEmpty());
        final ChannelInfoItem user = (ChannelInfoItem) first.getItems().get(1);
        assertEquals("https://rumble.com/user/SharedSlug", user.getUrl());
        assertEquals("2", first.getNextPage().getId());
        final var last = search(RumbleFilters.ID_CF_MAIN_VIDEOS).getPage(first.getNextPage());
        assertTrue(last.getItems().isEmpty());
        assertNull(last.getNextPage());
    }

    @Test
    void repeatedCreatorPageStopsWithoutAppendingDuplicatesAcrossFreshExtractors()
            throws Exception {
        final String channel = creator("channel", "_c123", true);
        final String user = creator("user", "_uAlpha9", false);
        downloader.apiBody = parameters -> "{\"data\":{\"items\":["
                + ("0".equals(parameters.get("offset")) ? channel + "," + user
                        : user + "," + channel.replace("Title", "Updated title")) + "]}}";
        final var first = fetch(search(RumbleFilters.ID_CF_MAIN_CHANNELS));
        assertEquals("2", first.getNextPage().getId());

        final var repeated = search(RumbleFilters.ID_CF_MAIN_CHANNELS)
                .getPage(first.getNextPage());

        assertTrue(repeated.getItems().isEmpty());
        assertTrue(repeated.getErrors().isEmpty());
        assertNull(repeated.getNextPage());
        assertEquals(List.of("0", "2"), downloader.urls.stream()
                .map(url -> query(url).get("offset")).toList());
    }

    @Test
    void partiallyOverlappingCreatorPagesRetainNewItemsAndContinue()
            throws Exception {
        final String channel = creator("channel", "_c123", true);
        final String user = creator("user", "_uAlpha9", false);
        final String other = creator("channel", "_c456", true)
                .replace("SharedSlug", "OtherSlug");
        downloader.apiBody = parameters -> "{\"data\":{\"items\":["
                + ("0".equals(parameters.get("offset")) ? channel + "," + user
                        : user + "," + other) + "]}}";
        final var first = fetch(search(RumbleFilters.ID_CF_MAIN_CHANNELS));
        final var second = search(RumbleFilters.ID_CF_MAIN_CHANNELS)
                .getPage(first.getNextPage());

        assertEquals(List.of("https://rumble.com/user/SharedSlug",
                "https://rumble.com/c/OtherSlug"), second.getItems().stream()
                .map(InfoItem::getUrl).toList());
        assertTrue(second.getErrors().isEmpty());
        assertEquals("4", second.getNextPage().getId());
    }

    @Test
    void unreadableCreatorRowsDoNotFalselyTriggerRepeatedPageGuard() throws Exception {
        final String channel = creator("channel", "_c123", true);
        downloader.apiBody = parameters -> "{\"data\":{\"items\":[" + channel
                + ("0".equals(parameters.get("offset")) ? "" : ",42") + "]}}";
        final var first = fetch(search(RumbleFilters.ID_CF_MAIN_CHANNELS));
        final var second = search(RumbleFilters.ID_CF_MAIN_CHANNELS)
                .getPage(first.getNextPage());

        assertEquals(1, second.getItems().size());
        assertFalse(second.getErrors().isEmpty());
        assertEquals("3", second.getNextPage().getId());
        assertNull(second.getNextPage().getIds());
    }

    @Test
    void malformedCreatorsDoNotStopPaginationOrLaterCreators() throws Exception {
        downloader.apiBody = parameters -> "{\"data\":{\"items\":["
                + creator("channel", "_uWrong", true) + ","
                + creator("user", "_uValid", false) + "]}}";
        final var page = fetch(search(RumbleFilters.ID_CF_MAIN_CHANNELS));
        assertEquals(1, page.getItems().size());
        assertFalse(page.getErrors().isEmpty());
        assertEquals("2", page.getNextPage().getId());
    }

    @Test
    void creatorSearchNeverEmitsOrRequestsOffsetsBeyondDepthLimit() throws Exception {
        downloader.apiBody = parameters -> "{\"data\":{\"items\":["
                + creator("channel", "_c123", true) + "]}}";
        final SearchExtractor extractor = search(RumbleFilters.ID_CF_MAIN_CHANNELS);
        final var below = extractor.getPage(apiPage("video_collection.search", 9998, "20", Map.of()));
        assertEquals("9999", below.getNextPage().getId());
        final var bound = search(RumbleFilters.ID_CF_MAIN_VIDEOS).getPage(below.getNextPage());
        assertNull(bound.getNextPage());
        final int requests = downloader.urls.size();
        assertThrows(ParsingException.class, () -> extractor.getPage(
                apiPage("video_collection.search", 10001, "20", Map.of())));
        assertEquals(requests, downloader.urls.size());
    }

    @Test
    void dateRestrictedVideoSearchKeepsExactFilteredHtmlAndFreshPagination() throws Exception {
        final SearchExtractor extractor = search(RumbleFilters.ID_CF_MAIN_VIDEOS,
                RumbleFilters.ID_SF_UPLOAD_DATE_TODAY, RumbleFilters.ID_SF_SORT_BY_VIEWS,
                RumbleFilters.ID_SF_DURATION_SHORT);
        downloader.htmlUrl = extractor.getUrl();
        assertTrue(downloader.htmlUrl.contains("date=today"));
        assertTrue(downloader.htmlUrl.contains("sort=views"));
        assertTrue(downloader.htmlUrl.contains("duration=short"));
        final var page = fetch(extractor);
        assertTrue(page.getItems().isEmpty());
        assertEquals(List.of(downloader.htmlUrl), downloader.urls);
        final Page next = new Page(downloader.htmlUrl + "&page=2");
        downloader.htmlUrl = next.getUrl();
        assertTrue(search(RumbleFilters.ID_CF_MAIN_VIDEOS).getPage(next).getItems().isEmpty());
        assertEquals(next.getUrl(), downloader.urls.get(1));
    }

    @Test
    void staleChannelSortAndDurationSelectionsKeepFilteredHtml() throws Exception {
        for (final int filter : new int[]{RumbleFilters.ID_SF_SORT_BY_VIEWS,
                RumbleFilters.ID_SF_DURATION_LONG, RumbleFilters.ID_SF_UPLOAD_DATE_LAST_WEEK}) {
            final SearchExtractor extractor = search(RumbleFilters.ID_CF_MAIN_CHANNELS, filter);
            downloader.htmlUrl = extractor.getUrl();
            assertTrue(fetch(extractor).getItems().isEmpty());
            assertEquals(downloader.htmlUrl, downloader.urls.get(downloader.urls.size() - 1));
        }
    }

    @Test
    void unknownFiltersAndContentFailRatherThanReturningEmptyPages() {
        final String url = "https://rumble.com/search/video?q=space";
        final FilterItem unknown = new FilterItem(999, LibraryStringIds.SEARCH_FILTERS_ALL);
        for (final SearchQueryHandler handler : List.of(
                new SearchQueryHandler(url, url, "space", List.of(unknown), List.of()),
                new SearchQueryHandler(url, url, "space", List.of(), List.of(unknown)))) {
            assertThrows(ParsingException.class,
                    () -> fetch(new RumbleSearchExtractor(Rumble, handler)));
        }
        assertTrue(downloader.urls.isEmpty());
    }

    @Test
    void invalidPagesCannotExposePreviouslyFetchedResults() throws Exception {
        downloader.apiBody = parameters -> videos(video(1), false);
        final SearchExtractor extractor = search(RumbleFilters.ID_CF_MAIN_VIDEOS);
        assertEquals(1, fetch(extractor).getItems().size());
        for (final Page page : new Page[]{null, new Page(""), new Page("not a URI")}) {
            assertThrows(ParsingException.class, () -> extractor.getPage(page));
            assertThrows(ParsingException.class, extractor::getInitialPage);
        }
        assertThrows(ParsingException.class, () -> extractor.getPage(
                new Page("https://rumble.com/service.php?name=unknown&offset=0", "0")));
        assertThrows(ParsingException.class, () -> extractor.getPage(
                new Page("https://rumble.com/service.php?name=media.search&offset=bad", "0")));
    }

    private static SearchExtractor search(final int content, final int... filters) throws Exception {
        final List<FilterItem> selected = new ArrayList<>();
        for (final int filter : filters) {
            selected.add(DefaultSearchExtractorTest.getFilterItem(Rumble, filter));
        }
        return Rumble.getSearchExtractor("space & science", List.of(
                DefaultSearchExtractorTest.getFilterItem(Rumble, content)), selected);
    }

    private static InfoItemsPage<InfoItem> fetch(final SearchExtractor extractor) throws Exception {
        extractor.fetchPage();
        return extractor.getInitialPage();
    }

    private static String videos(final String rows, final boolean more) {
        return "{\"data\":{\"items\":[" + rows + "],\"has_more\":" + more + "}}";
    }

    private static String video(final int id) {
        return "{\"object_type\":\"video\",\"url\":\"https://rumble.com/v000" + id
                + "-fixture.html\",\"title\":\"Video " + id + "\"}";
    }

    private static String creator(final String type, final String id, final boolean url) {
        return "{\"type\":\"" + type + "\",\"id\":\"" + id
                + "\",\"slug\":\"SharedSlug\",\"title\":\"Title\""
                + (url ? ",\"url\":\"https://rumble.com/c/SharedSlug\"" : "") + "}";
    }

    private static Page apiPage(final String name, final int offset, final String limit,
                                final Map<String, String> extra) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("api", "8");
        parameters.put("query", "space & science");
        if ("media.search".equals(name)) {
            parameters.put("options", "video.full");
        }
        parameters.put("offset", Integer.toString(offset));
        parameters.put("limit", limit);
        parameters.putAll(extra);
        return new Page(RumbleApi.serviceUrl(name, parameters), Integer.toString(offset));
    }

    private static Map<String, String> query(final String url) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        for (final String pair : URI.create(url).getRawQuery().split("&")) {
            final String[] parts = pair.split("=", 2);
            parameters.put(Utils.decodeUrlUtf8(parts[0]), Utils.decodeUrlUtf8(parts[1]));
        }
        return parameters;
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

    private static <T> Map<String, T> snapshotAndClear(final Map<String, T> cache) {
        synchronized (cache) {
            final Map<String, T> copy = new LinkedHashMap<>(cache);
            cache.clear();
            return copy;
        }
    }

    private static <T> void restore(final Map<String, T> cache, final Map<String, T> previous) {
        synchronized (cache) {
            cache.clear();
            cache.putAll(previous);
        }
    }

    private static final class FixtureDownloader extends Downloader {
        private final List<String> urls = new ArrayList<>();
        private Function<Map<String, String>, String> apiBody = parameters -> videos("", false);
        private String htmlUrl;

        @Override
        public Response execute(@Nonnull final Request request) {
            urls.add(request.url());
            final String body;
            if ("/service.php".equals(URI.create(request.url()).getPath())) {
                body = apiBody.apply(query(request.url()));
            } else {
                assertEquals(htmlUrl, request.url(), "Unexpected HTML request");
                body = "<html><title>Search</title></html>";
            }
            return new Response(200, "OK", Map.of(), body, request.url());
        }
    }
}
