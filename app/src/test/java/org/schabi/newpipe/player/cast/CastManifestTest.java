package org.schabi.newpipe.player.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

public class CastManifestTest {
    @Test
    public void absolutePreservesSignedResourceAndResolvesRelativeResources() throws Exception {
        final String signed = "https://cdn.example/video.mp4?signature=abc&clen=100";
        assertEquals(signed, CastManifest.absolute(signed, ""));
        final String escaped = "https://cdn.example/video%2505.mp4?signature=%250abc%2Bvalue";
        assertEquals(escaped, CastManifest.absolute(escaped, ""));
        assertEquals("https://cdn.example/segment%2505.m4s?token=%250abc",
                CastManifest.absolute(signed, "segment%2505.m4s?token=%250abc"));
        assertEquals("https://cdn.example/segment.m4s", CastManifest.absolute(
                "https://cdn.example/live/master.m3u8?signature=abc", "../segment.m4s"));
        assertEquals("https://cdn.example/$Number%05d$.m4s", CastManifest.absolute(
                "https://cdn.example/manifest.mpd", "$Number%05d$.m4s"));
        assertThrows(IOException.class, () -> CastManifest.absolute(null, "segment.m4s"));
        assertThrows(IOException.class, () -> CastManifest.absolute(signed, "file:///etc/passwd"));
        assertThrows(IOException.class, () -> CastManifest.absolute(signed,
                "https://user:password@cdn.example/video"));
    }

    @Test
    public void queryOnlyReferencesRetainTheExactManifestPath() throws Exception {
        final String base = "https://cdn.example/live%2Fid/master.m3u8?signature=old";
        assertEquals("https://cdn.example/live%2Fid/master.m3u8?signature=new%2Bvalue",
                CastManifest.absolute(base, "?signature=new%2Bvalue"));
        assertEquals("https://cdn.example/live%2Fid/master.m3u8?",
                CastManifest.absolute(base, "?"));
        final List<String> urls = new ArrayList<>();
        CastManifest.hls("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n?variant=video\n",
                base, (url, mime) -> {
                    urls.add(url);
                    return "http://phone/playlist";
                });
        assertEquals(List.of("https://cdn.example/live%2Fid/master.m3u8?variant=video"), urls);
    }

    @Test
    public void hlsRewritesPlaylistsKeysInitializationAndRangedSegments() throws Exception {
        final List<String> urls = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        final String rewritten = CastManifest.hls("#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio/list.m3u8\"\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"100@0\"\n"
                + "#EXT-X-BYTERANGE:1000@100\nsegment.m4s\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo.m3u8\n",
                "https://cdn.example/live/master.m3u8", (url, mime) -> {
                    urls.add(url);
                    types.add(mime);
                    return "http://phone/token/" + urls.size();
                });
        assertEquals(List.of("https://cdn.example/live/audio/list.m3u8",
                "https://cdn.example/live/key.bin", "https://cdn.example/live/init.mp4",
                "https://cdn.example/live/segment.m4s", "https://cdn.example/live/video.m3u8"),
                urls);
        assertEquals(List.of(CastManifest.HLS, "", "", "", CastManifest.HLS), types);
        assertTrue(rewritten.contains("BYTERANGE=\"100@0\""));
        assertTrue(rewritten.contains("#EXT-X-BYTERANGE:1000@100"));
        assertFalse(rewritten.contains("https://cdn.example"));
    }

    @Test
    public void dashMaterializesInheritedTemplateAtRepresentationBase() throws Exception {
        final List<String> urls = new ArrayList<>();
        final String rewritten = CastManifest.dash("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"
                + "<BaseURL>https://cdn.example/root/</BaseURL><Period><AdaptationSet>"
                + "<SegmentTemplate timescale=\"1000\""
                + " initialization=\"$RepresentationID$/init.mp4\""
                + " media=\"$RepresentationID$/$Number%05d$.m4s\"><SegmentTimeline>"
                + "<S d=\"2000\" r=\"3\"/></SegmentTimeline></SegmentTemplate>"
                + "<Representation id=\"v1\" bandwidth=\"1000\"><BaseURL>video/</BaseURL>"
                + "</Representation></AdaptationSet></Period></MPD>",
                "https://origin.example/manifest.mpd", (url, mime) -> {
                    urls.add(url);
                    return "http://phone/token/" + urls.size();
                });
        assertEquals(List.of("https://cdn.example/root/video/v1/$Number%05d$.m4s",
                "https://cdn.example/root/video/v1/init.mp4"), urls);
        assertTrue(rewritten.contains("timescale=\"1000\""));
        assertTrue(rewritten.contains("SegmentTimeline"));
        assertTrue(rewritten.contains("d=\"2000\""));
        assertFalse(rewritten.contains("https://cdn.example"));
    }

    @Test
    public void inheritedSegmentListRetainsEveryOverridingSegment() throws Exception {
        final List<String> urls = new ArrayList<>();
        final String rewritten = CastManifest.dash("<MPD><Period><AdaptationSet>"
                + "<SegmentList timescale=\"1000\" duration=\"2000\">"
                + "<Initialization sourceURL=\"init.mp4\"/>"
                + "<SegmentURL media=\"inherited.m4s\"/></SegmentList>"
                + "<Representation id=\"v\"><SegmentList startNumber=\"2\">"
                + "<SegmentURL media=\"first.m4s\"/><SegmentURL media=\"second.m4s\"/>"
                + "</SegmentList></Representation></AdaptationSet></Period></MPD>",
                "https://cdn.example/manifest.mpd", (url, mime) -> {
                    urls.add(url);
                    return "http://phone/" + urls.size();
                });
        assertEquals(List.of("https://cdn.example/init.mp4",
                "https://cdn.example/first.m4s", "https://cdn.example/second.m4s"), urls);
        assertTrue(rewritten.contains("timescale=\"1000\""));
        assertTrue(rewritten.contains("duration=\"2000\""));
        assertTrue(rewritten.contains("startNumber=\"2\""));
        assertFalse(rewritten.contains("inherited.m4s"));
    }

    @Test
    public void implicitSegmentListRangesFetchTheProxiedRepresentationResource() throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final String media = server.content("0123456789", "video/mp4");
            final String rewritten = CastManifest.dash("<MPD><Period><AdaptationSet>"
                    + "<Representation id=\"video\"><BaseURL>" + media + "</BaseURL>"
                    + "<SegmentList><Initialization range=\"0-2\"/>"
                    + "<SegmentURL mediaRange=\"3-5\"/></SegmentList>"
                    + "</Representation></AdaptationSet></Period></MPD>",
                    "https://cdn.example/manifest.mpd", server);
            final String manifest = server.content(rewritten, CastManifest.DASH);

            assertSegmentListRanges(manifest, "012", "345");
        }
    }

    @Test
    public void inheritedImplicitSegmentListUsesTheInheritedMediaResource() throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final String media = server.content("abcdefghij", "video/mp4");
            final URI mediaUri = URI.create(media);
            final String rewritten = CastManifest.dash("<MPD><BaseURL>"
                    + mediaUri.resolve("/") + "</BaseURL><Period><AdaptationSet><BaseURL>"
                    + mediaUri.getRawPath().substring(1) + "</BaseURL>"
                    + "<SegmentList timescale=\"1\" duration=\"1\">"
                    + "<Initialization range=\"0-2\"/><SegmentURL mediaRange=\"3-5\"/>"
                    + "</SegmentList><Representation id=\"video\"/>"
                    + "</AdaptationSet></Period></MPD>",
                    "https://cdn.example/manifest.mpd", server);
            final String manifest = server.content(rewritten, CastManifest.DASH);

            assertSegmentListRanges(manifest, "abc", "def");
        }
    }

    @Test
    public void explicitInitializationAndImplicitMediaKeepTheirSeparateResources()
            throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final String media = server.content("0123456789", "video/mp4");
            final String initialization = server.content("abcdefghij", "video/mp4");
            final String rewritten = CastManifest.dash("<MPD><Period><AdaptationSet>"
                    + "<Representation id=\"video\"><BaseURL>" + media + "</BaseURL>"
                    + "<SegmentList><Initialization sourceURL=\"" + initialization
                    + "\" range=\"0-2\"/><SegmentURL mediaRange=\"3-5\"/></SegmentList>"
                    + "</Representation></AdaptationSet></Period></MPD>",
                    "https://cdn.example/manifest.mpd", server);
            final String manifest = server.content(rewritten, CastManifest.DASH);

            assertSegmentListRanges(manifest, "abc", "345");
        }
    }

    @Test
    public void implicitInitializationAndExplicitMediaKeepTheirSeparateResources()
            throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final String initialization = server.content("abcdefghij", "video/mp4");
            final String media = server.content("0123456789", "video/mp4");
            final String rewritten = CastManifest.dash("<MPD><Period><AdaptationSet>"
                    + "<Representation id=\"video\"><BaseURL>" + initialization + "</BaseURL>"
                    + "<SegmentList><Initialization range=\"0-2\"/>"
                    + "<SegmentURL media=\"" + media + "\" mediaRange=\"3-5\"/></SegmentList>"
                    + "</Representation></AdaptationSet></Period></MPD>",
                    "https://cdn.example/manifest.mpd", server);
            final String manifest = server.content(rewritten, CastManifest.DASH);

            assertSegmentListRanges(manifest, "abc", "345");
        }
    }

    @Test
    public void pairedDashRetainsBothAudioAndVideoIndexRanges() throws Exception {
        final String video = track("video/mp4", "v", "http://phone/video", "100-200");
        final String audio = track("audio/mp4", "a", "http://phone/audio", "50-99");
        final String merged = CastManifest.mergeDash(video, audio);
        assertTrue(merged.contains("video/mp4"));
        assertTrue(merged.contains("audio/mp4"));
        assertTrue(merged.contains("http://phone/video"));
        assertTrue(merged.contains("http://phone/audio"));
        assertTrue(merged.contains("indexRange=\"100-200\""));
        assertTrue(merged.contains("indexRange=\"50-99\""));
    }

    @Test
    public void externalXmlAndAmbiguousBaseAlternativesAreRejected() {
        assertThrows(IOException.class, () -> CastManifest.dash("<!DOCTYPE MPD ["
                + "<!ENTITY x SYSTEM 'file:///etc/passwd'>]><MPD>&x;</MPD>",
                "https://cdn.example/manifest.mpd", (url, mime) -> url));
        assertThrows(IOException.class, () -> CastManifest.dash("<MPD>"
                + "<BaseURL>https://one.example/</BaseURL>"
                + "<BaseURL>https://two.example/</BaseURL></MPD>",
                "https://cdn.example/manifest.mpd", (url, mime) -> url));
    }

    private static void assertSegmentListRanges(final String manifest, final String initialization,
                                                final String segment) throws Exception {
        final String response = request(manifest, "");
        assertTrue(response.startsWith("HTTP/1.1 200"));
        final String text = response.substring(response.indexOf("\r\n\r\n") + 4);
        final var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(text)));
        final Element representation = (Element) document.getElementsByTagName("Representation")
                .item(0);
        final var bases = representation.getElementsByTagName("BaseURL");
        final URI base = bases.getLength() == 0 ? URI.create(manifest)
                : URI.create(manifest).resolve(bases.item(0).getTextContent());
        final Element init = (Element) representation.getElementsByTagName("Initialization")
                .item(0);
        final Element media = (Element) representation.getElementsByTagName("SegmentURL").item(0);
        assertDashRange(base, init.getAttribute("sourceURL"), init.getAttribute("range"),
                initialization);
        assertDashRange(base, media.getAttribute("media"), media.getAttribute("mediaRange"),
                segment);
    }

    private static void assertDashRange(final URI base, final String reference, final String range,
                                       final String expected) throws Exception {
        final String url = reference.isEmpty() ? base.toString()
                : base.resolve(reference).toString();
        final String response = request(url, "Range: bytes=" + range + "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 206"));
        assertTrue(response.contains("Content-Range: bytes " + range + "/10\r\n"));
        assertEquals(expected, response.substring(response.indexOf("\r\n\r\n") + 4));
    }

    private static String request(final String url, final String headers) throws Exception {
        final URI uri = URI.create(url);
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + uri.getRawPath()
                    + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" + headers + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String track(final String mime, final String id, final String url,
                                final String index) {
        return "<MPD mediaPresentationDuration=\"PT10S\"><Period><AdaptationSet mimeType=\""
                + mime + "\"><Representation id=\"" + id + "\"><BaseURL>" + url
                + "</BaseURL><SegmentBase indexRange=\"" + index
                + "\"><Initialization range=\"0-49\"/></SegmentBase>"
                + "</Representation></AdaptationSet></Period></MPD>";
    }
}
