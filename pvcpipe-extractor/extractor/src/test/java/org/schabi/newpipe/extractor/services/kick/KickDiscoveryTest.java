package org.schabi.newpipe.extractor.services.kick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Kick;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.search.filter.LibraryStringIds;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.List;

@Tag("offline")
class KickDiscoveryTest {
    private KickFixtures downloader;

    @BeforeEach
    void setUp() {
        downloader = new KickFixtures();
        NewPipe.init(downloader);
    }

    @Test
    void enrichedSearchReturnsLiveBeforeChannelsAndFiltersRequestedTypes() throws Exception {
        final String live = JsonWriter.string(live("example", "Live result"));
        final String channel = JsonWriter.string(KickFixtures.channelData(false));
        downloader.on("https://search.kick.com/api/v1/search/enriched?query=a%26b",
                "{\"data\":{\"livestreams\":[" + live + "],\"channels\":[" + channel + "]}}")
                .on("https://search.kick.com/api/v1/channels/search?query=a%26b&limit=50",
                        "{\"data\":[" + channel + "]}");
        final var all = search("a&b", KickSearchFilters.ALL).getInitialPage();
        assertEquals(2, all.getItems().size());
        assertTrue(all.getItems().get(0) instanceof StreamInfoItem);
        assertTrue(all.getItems().get(1) instanceof ChannelInfoItem);
        assertEquals(-1, ((StreamInfoItem) all.getItems().get(0)).getViewCount());
        assertEquals(List.of("Live result"), search("a&b", KickSearchFilters.LIVE)
                .getInitialPage().getItems().stream().map(item -> item.getName())
                .collect(java.util.stream.Collectors.toList()));
        assertTrue(search("a&b", KickSearchFilters.CHANNELS).getInitialPage().getItems()
                .get(0) instanceof ChannelInfoItem);
        assertFalse(Page.isValid(all.getNextPage()));
    }

    @Test
    void blankQueriesAvoidRequestsAndRequestedMalformedListsFail() throws Exception {
        assertTrue(search(" ", KickSearchFilters.ALL).getInitialPage().getItems().isEmpty());
        assertTrue(downloader.requests.isEmpty());
        downloader.on("https://search.kick.com/api/v1/search/enriched?query=x",
                "{\"data\":{\"livestreams\":[],\"channels\":null}}");
        assertThrows(ParsingException.class,
                () -> search("x", KickSearchFilters.ALL).getInitialPage());
        assertTrue(search("x", KickSearchFilters.LIVE).getInitialPage().getItems().isEmpty());
        downloader.on("https://search.kick.com/api/v1/search/enriched?query=x",
                "{\"data\":{\"channels\":[]}}");
        assertThrows(ParsingException.class,
                () -> search("x", KickSearchFilters.LIVE).getInitialPage());
    }

    @Test
    void discoveryUsesOpaqueAfterAndLanguageScopedPagesAndStopsRepeatedCursor() throws Exception {
        final String cursor = "opaque+/=&next";
        final String scope = KickApi.livestreamsUrl("fr", 24, null, null);
        downloader.on(scope, discovery("first", cursor)).on(
                KickApi.livestreamsUrl("fr", 24, null, cursor), discovery("second", cursor));
        final KioskExtractor<StreamInfoItem> kiosk = Kick.getKioskList().getDefaultKioskExtractor();
        kiosk.forceLocalization(new Localization("FR", "CA"));
        final var first = kiosk.getInitialPage();
        assertEquals("first", first.getItems().get(0).getName());
        assertEquals(scope, first.getNextPage().getUrl());
        final var second = kiosk.getPage(first.getNextPage());
        assertEquals("second", second.getItems().get(0).getName());
        assertFalse(Page.isValid(second.getNextPage()));
        assertTrue(downloader.requests.get(1).url().endsWith(
                "&after=opaque%2B%2F%3D%26next"));
        final int count = downloader.requests.size();
        assertTrue(kiosk.getPage(first.getNextPage()).getItems().isEmpty());
        for (final Page hostile : List.of(new Page("https://evil.example/", cursor),
                new Page(KickApi.livestreamsUrl("en", 24, null, null), cursor),
                new Page(scope, " "))) {
            assertThrows(ParsingException.class, () -> kiosk.getPage(hostile));
        }
        assertEquals(count, downloader.requests.size());
    }

    private static SearchExtractor search(final String query, final int filter) throws Exception {
        final LibraryStringIds label = filter == KickSearchFilters.ALL
                ? LibraryStringIds.SEARCH_FILTERS_ALL : filter == KickSearchFilters.LIVE
                ? LibraryStringIds.SEARCH_FILTERS_LIVE : LibraryStringIds.SEARCH_FILTERS_CHANNELS;
        return Kick.getSearchExtractor(Kick.getSearchQHFactory().fromQuery(query,
                List.of(new FilterItem(filter, label)), List.of()));
    }

    private static JsonObject live(final String slug, final String title) {
        final JsonObject channel = KickFixtures.channelData(true);
        channel.put("slug", slug);
        final JsonObject live = channel.getObject("livestream");
        live.put("title", title);
        live.remove("session_title");
        channel.remove("livestream");
        live.put("channel", channel);
        return live;
    }

    private static String discovery(final String title, final String cursor) {
        return "{\"data\":{\"livestreams\":[" + JsonWriter.string(live("example", title))
                + "],\"pagination\":{\"next_cursor\":\"" + cursor + "\"}}}";
    }
}
