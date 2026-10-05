package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.Page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
