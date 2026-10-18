package org.schabi.newpipe.player.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

public class CastInlineManifestTest {
    @Test
    public void wholeInlineManifestCannotEvictItsFirstSegmentWhenPublishing() throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final String first = server.register(segmentUrl("video", 0), "");
            final String last = server.register(segmentUrl("video", 8191), "");

            assertThrows(IOException.class, () -> prepareLoad(server, 8192, 0));

            assertRouteRemainsAvailable(first);
            assertRouteRemainsAvailable(last);
        }
    }

    @Test
    public void wholeInlineManifestAtCapacityRetainsItsPublishedRoutes() throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final CastMedia media = prepareLoad(server, 8191, 0);

            assertPlaylistRemainsAvailable(media.url(), 8191);
        }
    }

    @Test
    public void pairedInlineSourcesCannotEvictReferencesWhenPublishingTheirMaster()
            throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final String video = server.register(segmentUrl("video", 0), "");
            final String audio = server.register(segmentUrl("audio", 0), "");

            assertThrows(IOException.class, () -> prepareLoad(server, 4095, 4095));

            assertRouteRemainsAvailable(video);
            assertRouteRemainsAvailable(audio);
        }
    }

    @Test
    public void completePairedInlineLoadAtCapacityRetainsBothSourcesAndMaster() throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final CastMedia media = prepareLoad(server, 4095, 4094);
            final String master = body(request(media.url(), "GET"));
            final var audio = Pattern.compile("URI=\"([^\"]+)\"").matcher(master);
            assertTrue(audio.find());
            final String video = segmentUrls(master).get(0);

            assertPlaylistRemainsAvailable(video, 4095);
            assertPlaylistRemainsAvailable(audio.group(1), 4094);
        }
    }

    private static CastMedia prepareLoad(final CastMediaServer server, final int videoSegments,
                                         final int audioSegments) throws Exception {
        final StreamInfo info = new StreamInfo(ServiceList.Rumble.getServiceId(),
                "https://example.com/inline", "https://example.com/inline",
                StreamType.VIDEO_STREAM, "inline", "inline", 0);
        final VideoStream video = new VideoStream.Builder().setId("video")
                .setContent(inline("video", videoSegments), false)
                .setMediaFormat(MediaFormat.MPEG_4).setDeliveryMethod(DeliveryMethod.HLS)
                .setResolution("720p").setIsVideoOnly(audioSegments > 0).build();
        final List<AudioStream> audio = audioSegments == 0 ? List.of()
                : List.of(new AudioStream.Builder().setId("audio")
                .setContent(inline("audio", audioSegments), false)
                .setMediaFormat(MediaFormat.M4A).setDeliveryMethod(DeliveryMethod.HLS)
                .setAverageBitrate(128).setAudioTrackId("audio").build());
        if (audioSegments == 0) {
            info.setVideoStreams(List.of(video));
        } else {
            info.setVideoOnlyStreams(List.of(video));
            info.setAudioStreams(audio);
        }
        final StreamInfoTag tag = StreamInfoTag.of(info, List.of(video), 0, audio,
                audioSegments == 0 ? -1 : 0);
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        try (CastMediaResolver resolver = new CastMediaResolver(context)) {
            final CastMedia media = resolver.resolve(info, tag, server);
            assertEquals(CastManifest.HLS, media.mimeType());
            return media;
        }
    }

    private static String inline(final String track, final int segments) {
        final StringBuilder source = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:1\n");
        for (int i = 0; i < segments; i++) {
            source.append("#EXTINF:1,\n").append(segmentUrl(track, i)).append('\n');
        }
        source.append("#EXT-X-ENDLIST\n");
        final String playlist = source.toString();
        assertTrue(playlist.getBytes(StandardCharsets.UTF_8).length < 8 * 1024 * 1024);
        return playlist;
    }

    private static String segmentUrl(final String track, final int index) {
        return "https://cdn.example/" + track + "/" + index + ".m4s";
    }

    private static void assertPlaylistRemainsAvailable(final String url, final int count)
            throws Exception {
        final String playlist = body(request(url, "GET"));
        assertTrue(playlist.startsWith("#EXTM3U\n"));
        assertTrue(playlist.contains("#EXT-X-ENDLIST"));
        assertTrue(playlist.getBytes(StandardCharsets.UTF_8).length < 8 * 1024 * 1024);
        final List<String> segments = segmentUrls(playlist);
        assertEquals(count, segments.size());
        assertRouteRemainsAvailable(segments.get(0));
        assertRouteRemainsAvailable(segments.get(segments.size() - 1));
    }

    private static List<String> segmentUrls(final String playlist) {
        return playlist.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
    }

    private static void assertRouteRemainsAvailable(final String url) throws Exception {
        // OPTIONS exercises the real capability lookup without contacting a network origin.
        assertTrue(request(url, "OPTIONS").startsWith("HTTP/1.1 204"));
    }

    private static String request(final String url, final String method) throws Exception {
        final URI uri = URI.create(url);
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((method + " " + uri.getRawPath()
                    + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String body(final String response) {
        assertTrue(response.startsWith("HTTP/1.1 200"));
        return response.substring(response.indexOf("\r\n\r\n") + 4);
    }
}
