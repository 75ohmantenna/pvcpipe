package org.schabi.newpipe.extractor.services.youtube;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class YoutubePostLiveDurationTest {
    @Test
    void usesMillisecondsForNumericAndFallbackDurations() throws Exception {
        assertDuration(Map.of("x-head-time-millis", List.of("480000"),
                "x-head-seqnum", List.of("96")), 1);
        assertDuration(Map.of("X-Head-Time-Millis", List.of("invalid"),
                "X-Head-Seqnum", List.of("96")), 480);
        assertDuration(Map.of("X-Head-Seqnum", List.of("96")), 480);
    }

    @Test
    void rejectsMissingSegmentCountAndOverflowingFallback() {
        assertThrows(CreationException.class, () -> manifest(Map.of(), 480));
        assertThrows(CreationException.class, () ->
                manifest(Map.of("X-Head-Seqnum", List.of("96")), Long.MAX_VALUE));
    }

    private static void assertDuration(final Map<String, List<String>> headers,
                                       final long fallback) throws Exception {
        final String manifest = manifest(headers, fallback);
        assertTrue(manifest.contains("mediaPresentationDuration=\"PT480.000S\""));
        assertTrue(manifest.contains("timescale=\"1000\""));
        assertTrue(manifest.contains("d=\"5000\""));
    }

    private static String manifest(final Map<String, List<String>> headers,
                                   final long fallback) throws Exception {
        final Downloader previous = NewPipe.getDownloader();
        final String url = "https://video.invalid/videoplayback?c=ANDROID&fixture=" + UUID.randomUUID();
        try {
            NewPipe.init(new Downloader() {
                @Override
                public Response execute(final Request request) {
                    assertEquals("GET", request.httpMethod());
                    assertEquals(url + "&sq=0&rn=0", request.url());
                    return new Response(200, "synthetic", headers, "", request.url());
                }
            }, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry());
            final ItagItem itag = ItagItem.getItag(137);
            itag.setCodec("avc1.640028");
            itag.setBitrate(1_000_000);
            itag.setWidth(1920);
            itag.setHeight(1080);
            return YoutubePostLiveStreamDvrDashManifestCreator.fromPostLiveStreamDvrStreamingUrl(
                    url, itag, 5, fallback);
        } finally {
            NewPipe.init(previous, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry());
        }
    }
}
