package org.schabi.newpipe.extractor.services.bitchute;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.services.bitchute.linkHandler.BitchutePlaylistLinkHandlerFactory;
import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.schabi.newpipe.extractor.ServiceList.Bitchute;

@Tag("offline")
class BitchutePlaylistTest {
    private static final String ID = "ExampleList1";
    private static final String URL = "https://www.bitchute.com/playlist/" + ID + "/";
    private final List<JsonObject> requests = new ArrayList<>();
    private boolean publicPlaylist = true;
    private String page = "{\"video_count\":5,\"videos\":[{\"video\":" + video("ExampleVid01")
            + "},{\"video\":null},{\"video\":" + video("ExampleVid02") + "}]}";

    private static String video(final String id) {
        return "{\"video_id\":\"" + id + "\",\"video_name\":\"Example\","
                + "\"video_url\":\"/video/" + id + "/\",\"duration\":\"1:05\","
                + "\"date_published\":\"2026-10-01T12:00:00Z\","
                + "\"thumbnail_url\":\"https://images.example/video.jpg\","
                + "\"channel\":{\"channel_name\":\"Creator\",\"channel_url\":\"/channel/ExampleChan1/\"}}";
    }

    @BeforeEach
    void setUp() {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                try {
                    final JsonObject body = JsonParser.object().from(
                            new String(request.dataToSend(), StandardCharsets.UTF_8));
                    assertEquals(ID, body.getString("playlist_id"));
                    assertEquals("POST", request.httpMethod());
                    requests.add(body);
                    final String result;
                    if (request.url().equals("https://api.bitchute.com/api/beta/playlist")) {
                        result = "{\"playlist_name\":\"Example list\",\"is_public\":" + publicPlaylist
                                + ",\"profile_name\":\"Owner\",\"video_count\":5,\"thumbnail_url\":null}";
                    } else if (request.url().equals("https://api.bitchute.com/api/beta/playlist/videos")) {
                        result = page;
                    } else {
                        throw new AssertionError(request.url());
                    }
                    return new Response(200, "", Map.of(), result, request.url());
                } catch (final Exception e) {
                    throw new AssertionError(e);
                }
            }
        });
    }

    @Test
    void publicPlaylistUsesActualPageLengthIncludingDeletedRows() throws Exception {
        final PlaylistExtractor extractor = Bitchute.getPlaylistExtractor(URL);
        extractor.fetchPage();
        assertEquals("Example list", extractor.getName());
        assertEquals("Owner", extractor.getUploaderName());
        assertEquals("", extractor.getUploaderUrl());
        assertTrue(extractor.getThumbnails().isEmpty());
        final var first = extractor.getInitialPage();
        assertEquals(2, first.getItems().size());
        assertTrue(first.getErrors().isEmpty(), first.getErrors().toString());
        assertEquals("3", first.getNextPage().getId());
        page = "{\"video_count\":5,\"videos\":[{\"video\":" + video("ExampleVid03")
                + "},{\"video\":" + video("ExampleVid03") + "}]}";
        final var last = extractor.getPage(first.getNextPage());
        assertEquals(2, last.getItems().size(), "Repeated playlist entries retain their order");
        assertNull(last.getNextPage());
        assertEquals(3, requests.get(2).getInt("offset"));
    }

    @Test
    void privatePlaylistStopsBeforeLoadingVideos() throws Exception {
        publicPlaylist = false;
        assertThrows(ContentNotAvailableException.class,
                () -> Bitchute.getPlaylistExtractor(URL).fetchPage());
        assertEquals(1, requests.size());
    }

    @Test
    void malformedPagesAndForeignContinuationNeverMakeRequests() throws Exception {
        final PlaylistExtractor extractor = Bitchute.getPlaylistExtractor(URL);
        for (final Page bad : new Page[]{null, new Page(URL, "-1"), new Page(URL, "nonsense"),
                new Page("https://www.bitchute.com/playlist/AnotherList1/", "1"),
                new Page(URL, "2147483648")}) {
            assertThrows(ParsingException.class, () -> extractor.getPage(bad));
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void emptyPageStopsEvenIfReportedTotalIsStale() throws Exception {
        page = "{\"video_count\":500,\"videos\":[]}";
        assertNull(Bitchute.getPlaylistExtractor(URL).getPage(new Page(URL, "0")).getNextPage());
    }

    @Test
    void absentVideosArrayIsAnError() throws Exception {
        page = "{}";
        assertThrows(ParsingException.class,
                () -> Bitchute.getPlaylistExtractor(URL).getPage(new Page(URL, "0")));
    }

    @Test
    void acceptsOnlyPublicPlaylistLinksOnSupportedHosts() throws Exception {
        final var factory = BitchutePlaylistLinkHandlerFactory.getInstance();
        for (final String host : List.of("bitchute.com", "www.bitchute.com", "old.bitchute.com")) {
            assertEquals(ID, factory.getId("https://" + host + "/playlist/" + ID + "/"));
            assertEquals(ID, factory.getId("https://" + host + "/video/playlist?playlistId=" + ID));
        }
        for (final String bad : List.of("https://bitchute.com.evil/playlist/" + ID,
                "https://bitchute.com@evil.test/playlist/" + ID,
                "https://www.bitchute.com/playlist/favorites/",
                "https://www.bitchute.com/video/playlist?playlistId",
                "https://www.bitchute.com/video/playlist?playlistId=%",
                "https://www.bitchute.com/playlist/" + ID + "/extra",
                "ftp://www.bitchute.com/playlist/" + ID)) {
            assertFalse(factory.onAcceptUrl(bad), bad);
        }
        assertThrows(ParsingException.class, () -> factory.getUrl("../bad"));
        assertFalse(Bitchute.getStreamLHFactory().onAcceptUrl(
                "https://www.bitchute.com/video/playlist?playlistId=" + ID));
    }
}
