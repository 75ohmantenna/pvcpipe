package org.schabi.newpipe.extractor.services.kick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Kick;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.CHANNEL_API;
import static org.schabi.newpipe.extractor.services.kick.KickFixtures.CHANNEL_URL;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.utils.PvcPipeExtractorUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Tag("offline")
class KickClipsTabTest {
    private static final String CLIPS_URL = "https://kick.com/example/clips";
    private static final String DEFAULT_URL = CLIPS_URL + "?sort=date&range=all";
    private static final String CLIPS_API =
            "https://web.kick.com/api/v1/channels/42/clips?sort=date&time=all";

    private KickFixtures downloader;
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;

    @BeforeEach
    void setUp() {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        downloader = new KickFixtures();
        NewPipe.init(downloader);
    }

    @AfterEach
    void tearDown() {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @Test
    void clipsAreAnAppendedDistinctTabWithExplicitDefaultsAndRetainedMetadata() throws Exception {
        downloader.channel(false).on(CLIPS_API, response(null, clip("clip_First")));
        final ChannelInfo channel = ChannelInfo.getInfo(Kick, CLIPS_URL);
        assertEquals(CHANNEL_URL, channel.getUrl());
        assertEquals(List.of(ChannelTabs.VIDEOS, ChannelTabs.LIVESTREAMS, ChannelTabs.CLIPS),
                channel.getTabs().stream().map(tab -> tab.getContentFilters().get(0))
                        .collect(Collectors.toList()));
        assertEquals(9, ChannelTabs.ID_CLIPS);
        assertEquals(LibraryStringIds.CHANNEL_TAB_CLIPS, ChannelTabs.CLIPS.getNameId());
        assertFalse(ChannelTabs.CLIPS.equals(ChannelTabs.SHORTS));
        final ListLinkHandler handler = channel.getTabs().get(2);
        assertEquals(DEFAULT_URL, handler.getUrl());
        assertTrue(handler instanceof PvcPipeExtractorUtils.CustomTabListLinkHandler);
        final InfoItemsPage<InfoItem> page = Kick.getChannelTabExtractor(handler).getInitialPage();
        assertEquals(1, page.getItems().size());
        assertTrue(page.getErrors().isEmpty(), page.getErrors().toString());
        final StreamInfoItem item = (StreamInfoItem) page.getItems().get(0);
        assertEquals(CLIPS_URL + "/clip_First", item.getUrl());
        assertEquals(CHANNEL_URL, item.getUploaderUrl());
        assertEquals(StreamType.VIDEO_STREAM, item.getStreamType());
        assertEquals(1, downloader.requests.stream().filter(r -> r.url().equals(CHANNEL_API)).count());
        assertEquals(2, downloader.requests.size());
    }

    @Test
    void allEightFilterChoicesSurviveUrlQueryAndRetainedHandlerReconstruction() throws Exception {
        final Set<String> urls = new HashSet<>();
        for (final String sort : List.of("date", "views")) {
            for (final String range : List.of("day", "week", "month", "all")) {
                final List<FilterItem> filters = KickClipFilters.fromValues(sort, range);
                final String expected = CLIPS_URL + "?sort=" + sort + "&range=" + range;
                final ListLinkHandler fromQuery = KickLinkHandlerFactory.TAB.fromQuery("EXAMPLE",
                        List.of(ChannelTabs.CLIPS), filters);
                final ListLinkHandler fromUrl = KickLinkHandlerFactory.TAB.fromUrl(expected);
                final ListLinkHandler retained = new ListLinkHandler(fromQuery);
                final ListLinkHandler reconstructed = KickLinkHandlerFactory.TAB.fromQuery(
                        retained.getId(), retained.getContentFilters(), retained.getSortFilter());
                for (final ListLinkHandler handler : List.of(fromQuery, fromUrl, reconstructed)) {
                    assertEquals(expected, handler.getUrl());
                    assertEquals("example", handler.getId());
                    assertEquals(List.of(ChannelTabs.CLIPS), handler.getContentFilters());
                    assertEquals(filters, handler.getSortFilter());
                    assertEquals(sort, KickClipFilters.sort(handler.getSortFilter()));
                    assertEquals(range, KickClipFilters.time(handler.getSortFilter()));
                }
                assertTrue(urls.add(expected));
            }
        }
        assertEquals(8, urls.size());
        assertEquals("date", KickClipFilters.sort(List.of()));
        assertEquals("all", KickClipFilters.time(List.of()));
        assertEquals(DEFAULT_URL, KickLinkHandlerFactory.TAB.fromQuery("example",
                List.of(ChannelTabs.CLIPS), List.of()).getUrl());
        assertEquals(DEFAULT_URL, KickLinkHandlerFactory.TAB.fromUrl(CLIPS_URL).getUrl());
    }

    @Test
    void establishedExternalAliasAndEncodedPairsAreCanonicalizedWithoutQueryInjection()
            throws Exception {
        final ListLinkHandler handler = KickLinkHandlerFactory.TAB.fromUrl(
                "https://www.kick.com/EXAMPLE/clips/?sort=view&range=week&ref=share");
        assertEquals(CLIPS_URL + "?sort=views&range=week", handler.getUrl());
        assertEquals(KickClipFilters.fromValues("views", "week"), handler.getSortFilter());
        assertEquals(CLIPS_URL + "?sort=views&range=day", KickLinkHandlerFactory.TAB.fromUrl(
                CLIPS_URL + "?%73ort=%76iews&%72ange=day").getUrl());
        assertEquals(DEFAULT_URL, KickLinkHandlerFactory.TAB.fromUrl(
                CLIPS_URL + "?ref=x%26sort%3Dviews%26range%3Dweek").getUrl());
        assertThrows(ParsingException.class, () -> KickClipFilters.fromValues("view", "week"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?sort=", "?sort", "?range=", "?range", "?sort=popular",
            "?range=year", "?sort=views&sort=date", "?sort=date&sort=date",
            "?range=day&range=week", "?range=all&range=all", "?sort=date&%73ort=views",
            "?range=all&%72ange=day", "?sort=views%26range%3Dweek", "?sort=%FF", "?sort=%"})
    void invalidExplicitFiltersAreRejected(final String query) {
        assertThrows(ParsingException.class, () -> KickLinkHandlerFactory.TAB.fromUrl(
                CLIPS_URL + query));
        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    void unknownAndConflictingFilterObjectsAreRejected() {
        for (final List<FilterItem> filters : List.of(
                List.of(KickClipFilters.SORT_DATE, KickClipFilters.SORT_VIEWS),
                List.of(KickClipFilters.TIME_DAY, KickClipFilters.TIME_WEEK),
                List.of(KickClipFilters.SORT_DATE, KickClipFilters.SORT_DATE),
                List.of(KickClipFilters.TIME_ALL, KickClipFilters.TIME_ALL),
                List.of(new FilterItem(999, LibraryStringIds.SEARCH_FILTERS_VIEWS)))) {
            assertThrows(ParsingException.class, () -> KickClipFilters.sort(filters));
            assertThrows(ParsingException.class, () -> KickClipFilters.time(filters));
            assertThrows(ParsingException.class, () -> KickLinkHandlerFactory.TAB.fromQuery(
                    "example", List.of(ChannelTabs.CLIPS), filters));
        }
    }

    @Test
    void cursorPagesAdvanceWithDistinctItemsThenExhaustWithoutRefetchingChannel() throws Exception {
        downloader.channel(false)
                .on(CLIPS_API, response("next", clip("clip_First")))
                .on(CLIPS_API + "&cursor=next", response("last", clip("clip_Second")))
                .on(CLIPS_API + "&cursor=last", response(null, clip("clip_Third")));
        final ChannelTabExtractor extractor = extractor(DEFAULT_URL);
        final InfoItemsPage<InfoItem> first = extractor.getInitialPage();
        assertPage(first, "clip_First");
        assertTrue(Page.isValid(first.getNextPage()));
        assertEquals(DEFAULT_URL, first.getNextPage().getUrl());
        assertEquals("next", first.getNextPage().getId());
        final InfoItemsPage<InfoItem> second = extractor.getPage(first.getNextPage());
        assertPage(second, "clip_Second");
        final InfoItemsPage<InfoItem> third = extractor.getPage(second.getNextPage());
        assertPage(third, "clip_Third");
        assertNull(third.getNextPage());
        assertEquals(List.of(CHANNEL_API, CLIPS_API, CLIPS_API + "&cursor=next",
                CLIPS_API + "&cursor=last"), downloader.requests.stream()
                .map(r -> r.url()).collect(Collectors.toList()));
    }

    @Test
    void opaqueCursorIsEncodedAsOneApiValueAndRangeIsMappedToTime() throws Exception {
        final String cursor = "A+/=?& cursor%尾";
        final String api = "https://web.kick.com/api/v1/channels/42/clips?sort=views&time=week";
        final String encoded = api + "&cursor=A%2B%2F%3D%3F%26+cursor%25%E5%B0%BE";
        downloader.channel(false).on(api, response(cursor, clip("clip_First")))
                .on(encoded, response(null, clip("clip_Second")));
        final ChannelTabExtractor extractor = extractor(CLIPS_URL + "?sort=views&range=week");
        final InfoItemsPage<InfoItem> initial = extractor.getInitialPage();
        assertEquals(cursor, initial.getNextPage().getId());
        assertPage(extractor.getPage(initial.getNextPage()), "clip_Second");
        assertEquals(encoded, downloader.requests.get(2).url());
    }

    @Test
    void repeatedConsumedCursorTerminatesAndCannotTriggerAnotherRequest() throws Exception {
        downloader.channel(false).on(CLIPS_API, response("repeat", clip("clip_First")))
                .on(CLIPS_API + "&cursor=repeat", response("repeat", clip("clip_Second")));
        final ChannelTabExtractor extractor = extractor(DEFAULT_URL);
        final Page continuation = extractor.getInitialPage().getNextPage();
        final InfoItemsPage<InfoItem> second = extractor.getPage(continuation);
        assertPage(second, "clip_Second");
        assertNull(second.getNextPage());
        final int requests = downloader.requests.size();
        final InfoItemsPage<InfoItem> repeated = extractor.getPage(continuation);
        assertTrue(repeated.getItems().isEmpty());
        assertNull(repeated.getNextPage());
        assertEquals(requests, downloader.requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void blankContinuationEndsPaging(final String cursor) throws Exception {
        downloader.channel(false).on(CLIPS_API, response(cursor, clip("clip_First")));
        assertNull(extractor(DEFAULT_URL).getInitialPage().getNextPage());
    }

    @Test
    void hostileCrossChannelAndCrossFilterPagesAreRejectedBeforeAnyNetworkRequest() throws Exception {
        final ChannelTabExtractor extractor = extractor(DEFAULT_URL);
        final Page[] invalid = {null, new Page(DEFAULT_URL), new Page(DEFAULT_URL, " "),
                new Page("", "next"), new Page("https://evil.example/", "next"),
                new Page("https://kick.com/other/clips?sort=date&range=all", "next"),
                new Page(CLIPS_URL + "?sort=views&range=all", "next"),
                new Page(CLIPS_URL + "?sort=date&range=day", "next"),
                new Page(CLIPS_API, "next"), new Page(CLIPS_URL, "next"),
                new Page(DEFAULT_URL + "&ref=share", "next")};
        for (final Page page : invalid) {
            assertThrows(ParsingException.class, () -> extractor.getPage(page));
        }
        assertTrue(downloader.requests.isEmpty());
    }

    @Test
    void restrictedItemsAreExcludedAndCollectorErrorsRemainVisible() throws Exception {
        final JsonArray clips = new JsonArray();
        clips.add(clip("clip_Visible"));
        for (final String flag : List.of("is_private", "is_pruned", "is_banned", "is_deleted")) {
            final JsonObject restricted = clip("clip_" + flag);
            restricted.put(flag, true);
            clips.add(restricted);
        }
        for (final String status : List.of("private", "deleted", "pruned", "banned")) {
            final JsonObject restricted = clip("clip_" + status);
            restricted.put("status", status);
            clips.add(restricted);
        }
        final JsonObject privateClip = clip("clip_PrivatePrivacy");
        privateClip.put("privacy", "private");
        clips.add(privateClip);
        final JsonObject deleted = clip("clip_DeletedAt");
        deleted.put("deleted_at", "2026-10-20T12:00:00Z");
        clips.add(deleted);
        final JsonObject bannedOwner = clip("clip_BannedOwner");
        bannedOwner.getObject("channel").put("is_banned", true);
        clips.add(bannedOwner);
        final JsonObject malformed = clip("clip_Malformed");
        malformed.remove("id");
        clips.add(malformed);
        downloader.channel(false).on(CLIPS_API, "{\"data\":{\"clips\":"
                + JsonWriter.string(clips) + ",\"cursor\":null}}");
        final InfoItemsPage<InfoItem> page = extractor(DEFAULT_URL).getInitialPage();
        assertEquals(1, page.getItems().size());
        assertEquals(CLIPS_URL + "/clip_Visible", page.getItems().get(0).getUrl());
        assertFalse(page.getErrors().isEmpty());
    }

    @Test
    void videosAndLiveNeverAcceptContinuationPages() throws Exception {
        for (final String url : List.of(CHANNEL_URL, "https://kick.com/example")) {
            final ChannelTabExtractor extractor = extractor(url);
            assertThrows(ParsingException.class, () -> extractor.getPage(new Page(url, "next")));
        }
        assertTrue(downloader.requests.isEmpty());
    }

    private static ChannelTabExtractor extractor(final String url) throws Exception {
        return Kick.getChannelTabExtractor(KickLinkHandlerFactory.TAB.fromUrl(url));
    }

    private static void assertPage(final InfoItemsPage<InfoItem> page, final String id) {
        assertNotNull(page);
        assertEquals(1, page.getItems().size());
        assertEquals(CLIPS_URL + "/" + id, page.getItems().get(0).getUrl());
        assertTrue(page.getErrors().isEmpty(), page.getErrors().toString());
    }

    private static JsonObject clip(final String id) {
        return KickFixtures.json("{\"id\":\"" + id + "\",\"title\":\"Clip title\","
                + "\"duration\":12.5,\"created_at\":\"2026-10-20T12:00:00Z\","
                + "\"views_count\":23,\"likes_count\":4,\"privacy\":\"public\","
                + "\"is_mature\":false,\"thumbnail_url\":\"https://images.example/clip.jpg\","
                + "\"clip_url\":\"https://media.example/clip.mp4\","
                + "\"channel\":{\"id\":42,\"slug\":\"example\"}}");
    }

    private static String response(final String cursor, final JsonObject... items) {
        final JsonArray clips = new JsonArray();
        for (final JsonObject item : items) {
            clips.add(item);
        }
        final JsonObject data = new JsonObject();
        data.put("clips", clips);
        data.put("cursor", cursor);
        final JsonObject response = new JsonObject();
        response.put("data", data);
        return JsonWriter.string(response);
    }
}
