package org.schabi.newpipe.extractor.services.bitchute.extractor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import javax.annotation.Nonnull;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.schabi.newpipe.extractor.ServiceList.Bitchute;

@Tag("offline")
class BitchuteLiveTest {
    private static final String URL = "https://media.example/live/manifest.m3u8?token=example";

    @Test
    void liveVideoUsesEmbedManifestInsteadOfPlaceholderMp4() throws Exception {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                final String body;
                if (request.url().endsWith("/beta9/video")) {
                    body = "{\"video_name\":\"Live example\",\"state_id\":\"live\",\"duration\":\"0:00\"}";
                } else if (request.url().contains("/embed/")) {
                    body = "<script>var media_url = '" + URL + "';</script>";
                } else if (request.url().endsWith("/counts")) {
                    body = "{}";
                } else if (request.url().endsWith("/videos")) {
                    body = "{\"videos\":[]}";
                } else {
                    throw new AssertionError("Unexpected request: " + request.url());
                }
                return new Response(200, "", Map.of(), body, request.url());
            }
        });
        final var extractor = Bitchute.getStreamExtractor("https://www.bitchute.com/video/ExampleLive1/");
        extractor.fetchPage();
        assertEquals(StreamType.LIVE_STREAM, extractor.getStreamType());
        assertEquals(-1, extractor.getLength());
        assertNull(extractor.getUploadDate());
        assertEquals(URL, extractor.getHlsUrl());
        assertEquals(DeliveryMethod.HLS, extractor.getVideoStreams().get(0).getDeliveryMethod());
    }

    @Test
    void noUsableManifestProducesUnavailableError() {
        for (final String body : new String[]{"<html>Unavailable</html>",
                "var media_url='https://media.example/placeholder.mp4';",
                "var media_url='javascript:alert(1)';",
                "var media_url='https://user@media.example/live.m3u8';"}) {
            assertThrows(ContentNotAvailableException.class,
                    () -> BitchuteStreamExtractor.parseLiveMedia(body));
        }
    }

    @Test
    void liveKioskHandlesNullPublicationDatesAndEndsAfterSnapshot() throws Exception {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                assertEquals("https://api.bitchute.com/api/beta9/cache/livestreams", request.url());
                return new Response(200, "", Map.of(), "{\"videos\":[{\"state_id\":\"live\","
                        + "\"video_name\":\"Live\",\"video_url\":\"/video/ExampleLive1/\","
                        + "\"duration\":\"0:00\",\"date_published\":null,"
                        + "\"thumbnail_url\":\"https://images.example/live.jpg\","
                        + "\"channel\":{\"channel_name\":\"Example\","
                        + "\"channel_url\":\"/channel/ExampleChan1/\"}}]}", request.url());
            }
        });
        final var kiosk = Bitchute.getKioskList().getExtractorById("live", null);
        kiosk.fetchPage();
        final var page = kiosk.getInitialPage();
        assertEquals(1, page.getItems().size());
        assertTrue(page.getErrors().isEmpty(), page.getErrors().toString());
        assertEquals(StreamType.LIVE_STREAM, ((org.schabi.newpipe.extractor.stream.StreamInfoItem) page.getItems().get(0))
                .getStreamType());
        assertNull(page.getNextPage());
    }
}
