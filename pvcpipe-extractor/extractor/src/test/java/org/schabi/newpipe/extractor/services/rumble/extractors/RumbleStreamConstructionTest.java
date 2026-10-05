package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;
import static org.schabi.newpipe.extractor.stream.Stream.ID_UNKNOWN;

@Tag("offline")
class RumbleStreamConstructionTest {
    private static final String MASTER_URL = "https://cdn.example/master.m3u8";

    @Test
    void preservesProgressiveFormatsAndMetadata() throws Exception {
        final StreamExtractor extractor = extractor(
                "{\"mp4\":{\"720\":{\"url\":\"https://cdn.example/video.mp4\","
                        + "\"meta\":{\"h\":720,\"bitrate\":1500}}},"
                        + "\"webm\":[{\"url\":\"https://cdn.example/video.webm\","
                        + "\"meta\":{\"h\":480}}],"
                        + "\"other\":[{\"url\":\"https://cdn.example/video.other\"}]}",
                null);
        final List<VideoStream> streams = extractor.getVideoStreams();
        assertEquals(3, streams.size());
        final VideoStream mp4 = streamWithContent(streams, "https://cdn.example/video.mp4");
        assertVideoMetadata(mp4, MediaFormat.MPEG_4, DeliveryMethod.PROGRESSIVE_HTTP,
                "720p@1500k", 1_500_000);
        assertNull(mp4.getManifestUrl());
        final VideoStream webm = streamWithContent(streams, "https://cdn.example/video.webm");
        assertVideoMetadata(webm, MediaFormat.WEBM, DeliveryMethod.PROGRESSIVE_HTTP,
                "480p", 0);
        assertNull(webm.getManifestUrl());
        final VideoStream unknown = streamWithContent(streams, "https://cdn.example/video.other");
        assertVideoMetadata(unknown, null, DeliveryMethod.PROGRESSIVE_HTTP, "unknown", 0);
        assertNull(unknown.getManifestUrl());
    }

    @Test
    void preservesHlsVariantMetadata() throws Exception {
        final StreamExtractor extractor = extractor(hlsFormats(),
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\n"
                        + "variant.m3u8\n");
        final List<VideoStream> streams = extractor.getVideoStreams();
        assertEquals(1, streams.size());
        final VideoStream stream = streams.get(0);
        assertVideoMetadata(stream, MediaFormat.MPEG_4, DeliveryMethod.HLS,
                "720p@1700k", 1_700_000);
        assertEquals("https://cdn.example/variant.m3u8", stream.getContent());
        assertEquals(stream.getContent(), stream.getManifestUrl());
    }

    @Test
    void preservesHlsFallbackWhenTheManifestCannotBeFetched() throws Exception {
        final List<VideoStream> streams = extractor(hlsFormats(), null).getVideoStreams();
        assertEquals(1, streams.size());
        final VideoStream stream = streams.get(0);
        assertVideoMetadata(stream, MediaFormat.MPEG_4, DeliveryMethod.HLS, "auto", 0);
        assertEquals(MASTER_URL, stream.getContent());
        assertEquals(MASTER_URL, stream.getManifestUrl());
    }

    @Test
    void progressiveStreamsTakePrecedenceOverTheHlsFallback() throws Exception {
        final String formats = hlsFormats().substring(0, hlsFormats().length() - 1)
                + ",\"mp4\":[{\"url\":\"https://cdn.example/video.mp4\","
                + "\"meta\":{\"h\":720}}]}";
        final List<VideoStream> streams = extractor(formats, null).getVideoStreams();
        assertEquals(1, streams.size());
        assertVideoMetadata(streams.get(0), MediaFormat.MPEG_4,
                DeliveryMethod.PROGRESSIVE_HTTP, "720p", 0);
    }

    private static String hlsFormats() {
        return "{\"hls\":[{\"url\":\"" + MASTER_URL + "\"}]}";
    }

    private static VideoStream streamWithContent(final List<VideoStream> streams,
                                                 final String content) {
        return streams.stream().filter(stream -> content.equals(stream.getContent()))
                .findFirst().orElseThrow();
    }

    private static void assertVideoMetadata(final VideoStream stream, final MediaFormat format,
                                           final DeliveryMethod delivery, final String resolution,
                                           final int bitrate) {
        assertEquals(ID_UNKNOWN, stream.getId());
        assertTrue(stream.isUrl());
        assertFalse(stream.isVideoOnly());
        assertEquals(format, stream.getFormat());
        assertEquals(delivery, stream.getDeliveryMethod());
        assertEquals(resolution, stream.getResolution());
        assertEquals(bitrate, stream.getBitrate());
    }

    private static StreamExtractor extractor(final String formats, final String manifest)
            throws Exception {
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) throws IOException {
                final String body;
                if (request.url().startsWith("https://rumble.com/embedJS/")) {
                    body = "{\"ua\":" + formats + ",\"cc\":{}}";
                } else if (MASTER_URL.equals(request.url()) && manifest != null) {
                    body = manifest;
                } else {
                    throw new IOException("No fixture for " + request.url());
                }
                return new Response(200, "OK", Map.of(), body, request.url());
            }
        });
        final StreamExtractor extractor = Rumble.getStreamExtractor("https://rumble.com/embed/vtest/");
        extractor.fetchPage();
        return extractor;
    }
}
