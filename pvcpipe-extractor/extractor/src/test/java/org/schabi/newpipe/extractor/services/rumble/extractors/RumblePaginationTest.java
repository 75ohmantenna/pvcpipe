package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class RumblePaginationTest {
    private final RumbleCommonCodeTrendingAndSearching helper =
            new RumbleCommonCodeTrendingAndSearching(new RumbleSearchTrendingItemsExtractorImpl());

    @Test
    void stopsPaginationWhenThereAreNoResults() {
        final Document doc = Jsoup.parse("<link rel='next' href='?page=2'>");
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
    void preservesNextLinkWithoutResolvingOrRebuildingItsUrl() {
        for (final String url : new String[]{"?page=2&sort=date", "/browse?page=2",
                "https://rumble.com/browse?page=2&sort=date"}) {
            final Document doc = Jsoup.parse("<link rel='next' href='" + url + "'>",
                    "https://rumble.com/browse");
            final Page page = helper.getNewPageIfThereAreMoreThanOnePageResults(1, doc);
            assertEquals(url, page.getUrl());
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
        final Document doc = Jsoup.parse("<link rel='next' href='?page=2'>");

        final var streamPage = shared.extractAndGetStreamInfoItemsFromPage(doc);
        assertTrue(streamPage.getItems().isEmpty());
        assertEquals("?page=2", streamPage.getNextPage().getUrl());

        final var mixedPage = shared.extractAndGetInfoItemsFromPage(doc);
        assertTrue(mixedPage.getItems().isEmpty());
        assertEquals("?page=2", mixedPage.getNextPage().getUrl());
    }
}
