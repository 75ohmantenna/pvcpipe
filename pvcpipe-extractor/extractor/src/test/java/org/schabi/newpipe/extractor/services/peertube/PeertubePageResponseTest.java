package org.schabi.newpipe.extractor.services.peertube;

import com.grack.nanojson.JsonParserException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.services.peertube.extractors.PeertubePlaylistExtractor;
import org.schabi.newpipe.extractor.services.peertube.extractors.PeertubeSearchExtractor;
import org.schabi.newpipe.extractor.services.peertube.extractors.PeertubeTrendingExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.PeerTube;

@Tag("offline")
class PeertubePageResponseTest {
    private static final String URL = "https://pt.example/api/v1/videos?sort=-views&start=0&count=12";
    private static final String UUID = "96b0ee2b-a5a7-4794-8769-58d8ccb79ab7";
    private static final String VIDEO = "{\"uuid\":\"" + UUID + "\",\"name\":\"Video\","
            + "\"duration\":120,\"views\":42,\"publishedAt\":\"2026-09-17T00:00:00Z\","
            + "\"thumbnailPath\":\"/thumbnail.jpg\",\"account\":{\"name\":\"alice\","
            + "\"host\":\"pt.example\",\"displayName\":\"Alice\"},"
            + "\"embedUrl\":\"https://origin.example/videos/embed/" + UUID + "\","
            + "\"embedPath\":\"/videos/embed/" + UUID + "\"}";

    @ParameterizedTest
    @EnumSource(Mode.class)
    void extractsItemsAndPreservesPagination(final Mode mode) throws Exception {
        final String item = mode == Mode.PLAYLIST ? "{\"video\":" + VIDEO + "}" : VIDEO;
        final FixedDownloader downloader = new FixedDownloader(
                response("{\"total\":25,\"data\":[" + item + "]}"));
        final ListExtractor<?> extractor = extractor(mode, downloader);
        final ListExtractor.InfoItemsPage<?> page = extractor.getPage(new Page(URL));

        assertEquals(List.of(URL), downloader.requests);
        assertTrue(page.getErrors().isEmpty(), page.getErrors().toString());
        assertEquals(1, page.getItems().size());
        final StreamInfoItem stream = assertInstanceOf(StreamInfoItem.class, page.getItems().get(0));
        final String itemBaseUrl = mode == Mode.SEPIA ? "https://origin.example" : "https://pt.example";
        assertEquals(itemBaseUrl + "/videos/watch/" + UUID, stream.getUrl());
        assertEquals("Video", stream.getName());
        assertEquals(120, stream.getDuration());
        assertEquals(42, stream.getViewCount());
        assertEquals(itemBaseUrl + "/thumbnail.jpg", stream.getThumbnails().get(0).getUrl());
        assertEquals(URL.replace("start=0", "start=12"), page.getNextPage().getUrl());

        extractor.getPage(page.getNextPage());
        assertEquals(List.of(URL, URL.replace("start=0", "start=12")), downloader.requests);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void acceptsEmptyResultLists(final Mode mode) throws Exception {
        final ListExtractor.InfoItemsPage<?> page = extractor(mode,
                new FixedDownloader(response("{\"total\":0,\"data\":[]}")))
                .getPage(new Page(URL));
        assertTrue(page.getItems().isEmpty());
        assertTrue(page.getErrors().isEmpty());
        assertNull(page.getNextPage());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void preservesUnavailableResponseErrors(final Mode mode) {
        for (final Response response : new Response[]{null, response(null), response(""),
                response(" \n\t ")}) {
            final ExtractionException error = assertThrows(ExtractionException.class,
                    () -> extractor(mode, new FixedDownloader(response)).getPage(new Page(URL)));
            assertEquals(ExtractionException.class, error.getClass());
            assertEquals("Unable to get PeerTube " + mode.infoType + " info", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void preservesMalformedJsonErrorsAndCauses(final Mode mode) {
        for (final String body : new String[]{"{", "[]", "null"}) {
            final ParsingException error = assertThrows(ParsingException.class,
                    () -> extractor(mode, new FixedDownloader(response(body))).getPage(new Page(URL)));
            assertEquals("Could not parse json data for " + mode.infoType + " info",
                    error.getMessage());
            assertInstanceOf(JsonParserException.class, error.getCause());
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void leavesApiValidationErrorsUnwrapped(final Mode mode) {
        final ContentNotAvailableException error = assertThrows(ContentNotAvailableException.class,
                () -> extractor(mode, new FixedDownloader(response("{\"error\":\"Unavailable\"}")))
                        .getPage(new Page(URL)));
        assertEquals("Unavailable", error.getMessage());
        assertNull(error.getCause());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void validatesPageBeforeDownloading(final Mode mode) {
        final FixedDownloader downloader = new FixedDownloader(response("{}"));
        final ListExtractor<?> extractor = extractor(mode, downloader);
        for (final Page page : new Page[]{null, new Page("")}) {
            final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> extractor.getPage(page));
            assertEquals("Page doesn't contain an URL", error.getMessage());
        }
        assertTrue(downloader.requests.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void leavesDownloadFailuresUnwrapped(final Mode mode) {
        final IOException failure = new IOException("Network failed");
        final Downloader downloader = new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) throws IOException {
                throw failure;
            }
        };
        assertSame(failure, assertThrows(IOException.class,
                () -> extractor(mode, downloader).getPage(new Page(URL))));
    }

    private static ListExtractor<?> extractor(final Mode mode, final Downloader downloader) {
        NewPipe.init(downloader);
        final ListLinkHandler handler = new ListLinkHandler(URL, URL, "id", List.of(), List.of());
        switch (mode) {
            case TRENDING:
                return new PeertubeTrendingExtractor(PeerTube, handler, "Trending");
            case PLAYLIST:
                return new PeertubePlaylistExtractor(PeerTube, handler);
            case SEARCH:
                return new PeertubeSearchExtractor(PeerTube, new SearchQueryHandler(handler));
            case SEPIA:
                return new PeertubeSearchExtractor(PeerTube, new SearchQueryHandler(handler), true);
            default:
                throw new IllegalArgumentException(mode.name());
        }
    }

    private static Response response(final String body) {
        return new Response(200, "OK", Map.of(), body, URL);
    }

    private static final class FixedDownloader extends Downloader {
        private final Response response;
        private final List<String> requests = new ArrayList<>();

        private FixedDownloader(final Response response) {
            this.response = response;
        }

        @Override
        public Response execute(@Nonnull final Request request) throws IOException, ReCaptchaException {
            requests.add(request.url());
            return response;
        }
    }

    private enum Mode {
        TRENDING("kiosk"), PLAYLIST("playlist"), SEARCH("search"), SEPIA("search");

        private final String infoType;

        Mode(final String infoType) {
            this.infoType = infoType;
        }
    }
}
