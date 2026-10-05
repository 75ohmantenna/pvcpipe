package org.schabi.newpipe.extractor.services.bitchute.extractor;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Bitchute;

@Tag("offline")
class BitchuteKioskPaginationTest {
    @ParameterizedTest
    @CsvSource({
            "Popular, popular, true",
            "Suggested, popular, true",
            "Trending Today, trending-day, false",
            "Trending This Week, trending-week, false",
            "Trending This Month, trending-month, false",
            "unknown, trending-day, false"
    })
    void preservesCategorySelectionAndPagination(final String id, final String selection,
                                                final boolean hasNextPage) throws Exception {
        final KioskDownloader downloader = new KioskDownloader(false);
        NewPipe.init(downloader);
        final BitchuteTrendingKioskExtractor extractor = extractor(id);

        final var first = extractor.getInitialPage();
        assertEquals(selection, downloader.payload.getString("selection"));
        assertEquals(0, downloader.payload.getInt("offset"));
        assertEquals(20, downloader.payload.getInt("limit"));
        assertTrue(downloader.payload.getBoolean("advertisable"));
        assertEquals(1, first.getItems().size());
        assertTrue(first.getErrors().isEmpty());
        assertEquals(hasNextPage, first.hasNextPage());
        if (hasNextPage) {
            assertEquals(extractor.getUrl(), first.getNextPage().getUrl());
            assertEquals("1", first.getNextPage().getId());
            final var second = extractor.getPage(first.getNextPage());
            assertEquals(20, downloader.payload.getInt("offset"));
            assertEquals(selection, downloader.payload.getString("selection"));
            assertEquals("2", second.getNextPage().getId());
        }
    }

    @ParameterizedTest
    @CsvSource({"Popular", "Suggested", "Trending Today", "Trending This Week",
            "Trending This Month"})
    void emptyResultsStopPagination(final String id) throws Exception {
        NewPipe.init(new KioskDownloader(true));
        final var page = extractor(id).getInitialPage();
        assertTrue(page.getItems().isEmpty());
        assertFalse(page.hasNextPage());
    }

    @Test
    void invalidPageNumberFailsBeforeMakingARequest() {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                throw new AssertionError("Unexpected request: " + request.url());
            }
        });
        assertThrows(NumberFormatException.class,
                () -> extractor("Popular").getPage(new Page("url", "invalid")));
    }

    private static BitchuteTrendingKioskExtractor extractor(final String id) {
        final String url = "https://www.bitchute.com/";
        return new BitchuteTrendingKioskExtractor(Bitchute,
                new ListLinkHandler(url, url, id, List.of(), List.of()), id);
    }

    private static final class KioskDownloader extends Downloader {
        private final boolean empty;
        private JsonObject payload;

        KioskDownloader(final boolean empty) {
            this.empty = empty;
        }

        @Override
        public Response execute(@Nonnull final Request request) {
            assertEquals("https://api.bitchute.com/api/beta9/videos", request.url());
            assertEquals("POST", request.httpMethod());
            try {
                payload = JsonParser.object().from(
                        new String(request.dataToSend(), StandardCharsets.UTF_8));
            } catch (final Exception exception) {
                throw new AssertionError(exception);
            }
            final String body = empty ? "{\"videos\":[]}" : "{\"videos\":[{"
                    + "\"channel\":{\"channel_name\":\"Channel\",\"channel_url\":\"/channel/test/\"},"
                    + "\"video_name\":\"Video\",\"video_url\":\"/video/test/\","
                    + "\"thumbnail_url\":\"https://cdn.example/video.jpg\","
                    + "\"duration\":\"00:01:30\",\"view_count\":99,"
                    + "\"date_published\":\"2026-09-17T00:00:00Z\"}]}";
            return new Response(200, "OK", Map.of(), body, request.url());
        }
    }
}
