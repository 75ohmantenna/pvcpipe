package org.schabi.newpipe.player.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CastMediaServerTest {
    @Test
    public void localContentSupportsHeadSuffixRangesAndCannotBecomeAnOpenProxy() throws Exception {
        final CastMediaServer server = new CastMediaServer(InetAddress.getByName("127.0.0.1"));
        try {
            final String url = server.content("0123456789", "video/mp4");
            final String suffix = request(url, "GET", "Range: bytes=-3\r\n");
            assertTrue(suffix.startsWith("HTTP/1.1 206"));
            assertTrue(suffix.contains("Content-Range: bytes 7-9/10"));
            assertTrue(suffix.endsWith("\r\n\r\n789"));
            final String head = request(url, "HEAD", "");
            assertTrue(head.startsWith("HTTP/1.1 200"));
            assertTrue(head.contains("Content-Length: 10"));
            assertTrue(head.endsWith("\r\n\r\n"));
            assertTrue(request(url, "GET", "Range: bytes=99-\r\n")
                    .startsWith("HTTP/1.1 416"));
            assertTrue(request(url + "?url=http://example.com", "GET", "")
                    .startsWith("HTTP/1.1 404"));
            assertTrue(request(url + "/../../anything", "GET", "")
                    .startsWith("HTTP/1.1 404"));
            assertTrue(request(url, "POST", "").startsWith("HTTP/1.1 405"));
            server.close();
            server.close();
            assertThrows(IOException.class, () -> server.register("https://example.com/video", ""));
        } finally {
            server.close();
        }
    }

    @Test
    public void proxyPreservesRangeStatusHeadersAndRequiredUserAgent() throws Exception {
        final var worker = Executors.newSingleThreadExecutor();
        try (ServerSocket origin = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
             CastMediaServer server = new CastMediaServer(InetAddress.getByName("127.0.0.1"))) {
            origin.setSoTimeout(5000);
            final var received = worker.submit(() -> {
                try (Socket socket = origin.accept()) {
                    socket.setSoTimeout(5000);
                    final BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    final StringBuilder headers = new StringBuilder();
                    String line = reader.readLine();
                    while (!line.isEmpty()) {
                        headers.append(line).append('\n');
                        line = reader.readLine();
                    }
                    socket.getOutputStream().write(("HTTP/1.1 206 Partial Content\r\n"
                            + "Content-Type: video/mp4\r\nContent-Length: 3\r\n"
                            + "Content-Range: bytes 2-4/10\r\nAccept-Ranges: bytes\r\n"
                            + "Connection: close\r\n\r\n234").getBytes(StandardCharsets.US_ASCII));
                    return headers.toString();
                }
            });
            final String upstream = "http://127.0.0.1:" + origin.getLocalPort() + "/video?sig=abc";
            final String url = server.register(upstream, "video/mp4");
            final String response = request(url, "GET", "Range: bytes=2-4\r\n"
                    + "Authorization: Bearer receiver-secret\r\nCookie: receiver=session\r\n");
            assertTrue(response.startsWith("HTTP/1.1 206"));
            assertTrue(response.contains("Content-Range: bytes 2-4/10"));
            assertTrue(response.endsWith("\r\n\r\n234"));
            final String headers = received.get(5, TimeUnit.SECONDS);
            assertTrue(headers.startsWith("GET /video?sig=abc HTTP/1.1"));
            assertTrue(headers.contains("Range: bytes=2-4"));
            assertTrue(headers.contains("User-Agent:"));
            assertFalse(headers.contains("receiver-secret"));
            assertFalse(headers.contains("receiver=session"));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void capabilityRoutesAreIsolatedBetweenLoads() throws Exception {
        try (CastMediaServer first = new CastMediaServer(InetAddress.getByName("127.0.0.1"));
             CastMediaServer second = new CastMediaServer(InetAddress.getByName("127.0.0.1"))) {
            final String oldUrl = first.content("old load", "video/mp4");
            final String newUrl = second.content("new load", "video/mp4");
            final URI oldUri = URI.create(oldUrl);
            final URI newUri = URI.create(newUrl);
            final String foreignRoute = "http://" + newUri.getHost() + ":" + newUri.getPort()
                    + oldUri.getRawPath();
            assertTrue(request(foreignRoute, "GET", "").startsWith("HTTP/1.1 404"));
            assertTrue(request(oldUrl, "GET", "").endsWith("\r\n\r\nold load"));
            assertTrue(request(newUrl, "GET", "").endsWith("\r\n\r\nnew load"));
        }
    }

    @Test
    public void discardedManifestErrorsHaveAnEmptyResponseLength() throws Exception {
        final var worker = Executors.newSingleThreadExecutor();
        try (ServerSocket origin = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
             CastMediaServer server = new CastMediaServer(InetAddress.getByName("127.0.0.1"))) {
            origin.setSoTimeout(5000);
            final var received = worker.submit(() -> {
                try (Socket socket = origin.accept()) {
                    socket.setSoTimeout(5000);
                    final BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line = reader.readLine();
                    while (line != null && !line.isEmpty()) {
                        line = reader.readLine();
                    }
                    socket.getOutputStream().write(("HTTP/1.1 404 Not Found\r\n"
                            + "Content-Length: 3\r\nConnection: close\r\n\r\nbad")
                            .getBytes(StandardCharsets.US_ASCII));
                }
                return null;
            });
            final String url = server.register("http://127.0.0.1:" + origin.getLocalPort()
                    + "/manifest.m3u8", CastManifest.HLS);
            final String response = request(url, "GET", "");
            assertTrue(response.startsWith("HTTP/1.1 404"));
            assertTrue(response.contains("Content-Length: 0\r\n"));
            assertTrue(response.endsWith("\r\n\r\n"));
            assertFalse(response.contains("bad"));
            received.get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void templateCapabilitiesAcceptOnlyExpectedNumericSubstitutions() throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getByName("127.0.0.1"))) {
            final String url = server.register(
                    "https://cdn.example/segment-$Number%05d$.m4s?time=$Time$", "");
            assertTrue(url.endsWith("/$Number%05d$/$Time$"));
            assertFalse(url.contains("cdn.example"));
            assertTrue(request(url.replace("$Number%05d$", "https:")
                    .replace("$Time$", "1"), "GET", "").startsWith("HTTP/1.1 404"));
            assertTrue(request(url.replace("$Number%05d$", "1")
                    .replace("$Time$", "../2"), "GET", "").startsWith("HTTP/1.1 404"));
            assertThrows(IOException.class,
                    () -> server.register("https://cdn.example/$SubNumber$.m4s", ""));
        }
    }

    @Test
    public void preparedLiveLoadCanReplaceOldCapabilitiesWithoutRejectingNewMedia()
            throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final String oldest = server.register("https://cdn.example/segment-0.ts", "");
            server.finishPreparation();
            String newest = oldest;
            for (int i = 1; i <= 8192; i++) {
                newest = server.register("https://cdn.example/segment-" + i + ".ts", "");
            }
            assertTrue(request(oldest, "OPTIONS", "").startsWith("HTTP/1.1 404"));
            assertTrue(request(newest, "OPTIONS", "").startsWith("HTTP/1.1 204"));
        }
    }

    @Test
    public void liveSegmentChurnRetainsPreparedManifests() throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final String inline = server.content("#EXTM3U\n", CastManifest.HLS);
            final String playlist = server.register("https://cdn.example/live.m3u8",
                    CastManifest.HLS);
            final String oldest = server.register("https://cdn.example/segment-0.ts", "");
            server.finishPreparation();
            String newest = oldest;
            for (int i = 1; i <= 8192; i++) {
                newest = server.register("https://cdn.example/segment-" + i + ".ts", "");
            }
            assertTrue(request(inline, "GET", "").endsWith("\r\n\r\n#EXTM3U\n"));
            assertTrue(request(playlist, "OPTIONS", "").startsWith("HTTP/1.1 204"));
            assertTrue(request(oldest, "OPTIONS", "").startsWith("HTTP/1.1 404"));
            assertTrue(request(newest, "OPTIONS", "").startsWith("HTTP/1.1 204"));
        }
    }

    @Test
    public void retainedManifestsCannotOverflowTheRouteBound() throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            final String first = server.register("https://cdn.example/0.m3u8", CastManifest.HLS);
            for (int i = 1; i < 8192; i++) {
                server.register("https://cdn.example/" + i + ".m3u8", CastManifest.HLS);
            }
            server.finishPreparation();
            assertThrows(IOException.class,
                    () -> server.register("https://cdn.example/segment.ts", ""));
            assertEquals(first, server.register("https://cdn.example/0.m3u8", CastManifest.HLS));
            assertTrue(request(first, "OPTIONS", "").startsWith("HTTP/1.1 204"));
        }
    }

    @Test
    public void rejectedTemplatesDoNotConsumePreparationCapacity() throws Exception {
        try (CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            for (int i = 0; i < 8192; i++) {
                final String url = "https://cdn.example/" + i + "/$Unsupported$.m4s";
                assertThrows(IOException.class, () -> server.register(url, ""));
            }
            final String content = server.content("still available", "video/mp4");
            assertTrue(request(content, "GET", "").endsWith("\r\n\r\nstill available"));
        }
    }

    @Test(timeout = 40000)
    public void progressingHeadersExpireWithoutInterruptingAnActiveStream() throws Exception {
        final var originWorker = Executors.newSingleThreadExecutor();
        final var dripper = Executors.newSingleThreadScheduledExecutor();
        final List<Socket> slowClients = new ArrayList<>();
        final CountDownLatch finishMedia = new CountDownLatch(1);
        try (ServerSocket origin = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
             CastMediaServer server = new CastMediaServer(InetAddress.getLoopbackAddress())) {
            origin.setSoTimeout(5000);
            final var originResponse = originWorker.submit(() -> {
                try (Socket socket = origin.accept()) {
                    socket.setSoTimeout(5000);
                    final BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line = reader.readLine();
                    while (line != null && !line.isEmpty()) {
                        line = reader.readLine();
                    }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Type: video/mp4\r\nContent-Length: 2\r\n"
                            + "Connection: close\r\n\r\nA").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    assertTrue(finishMedia.await(30, TimeUnit.SECONDS));
                    socket.getOutputStream().write('B');
                }
                return null;
            });
            final URI media = URI.create(server.register("http://127.0.0.1:"
                    + origin.getLocalPort() + "/video", "video/mp4"));
            try (Socket receiver = new Socket(media.getHost(), media.getPort())) {
                receiver.setSoTimeout(20000);
                receiver.getOutputStream().write(("GET " + media.getRawPath()
                        + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                final BufferedReader reader = new BufferedReader(new InputStreamReader(
                        receiver.getInputStream(), StandardCharsets.US_ASCII));
                String line = reader.readLine();
                while (line != null && !line.isEmpty()) {
                    line = reader.readLine();
                }
                assertEquals('A', reader.read());
                for (int index = 0; index < 7; index++) {
                    final Socket client = new Socket(media.getHost(), media.getPort());
                    slowClients.add(client);
                    client.setSoTimeout(20000);
                    client.getOutputStream().write((index % 2 == 0 ? "GET "
                            : "GET / HTTP/1.1\r\nX-Slow: ").getBytes(StandardCharsets.US_ASCII));
                }
                dripper.scheduleAtFixedRate(() -> {
                    for (final Socket client : slowClients) {
                        try {
                            client.getOutputStream().write('x');
                        } catch (final IOException ignored) {
                            // Expired request connections must not stop the other slow clients.
                        }
                    }
                }, 0, 100, TimeUnit.MILLISECONDS);
                for (final Socket client : slowClients) {
                    client.getInputStream().readAllBytes();
                }
                assertTrue(request(server.content("receiver media", "video/mp4"), "GET", "")
                        .endsWith("\r\n\r\nreceiver media"));
                finishMedia.countDown();
                assertEquals('B', reader.read());
                assertEquals(-1, reader.read());
                originResponse.get(5, TimeUnit.SECONDS);
            }
        } finally {
            finishMedia.countDown();
            dripper.shutdownNow();
            for (final Socket client : slowClients) {
                client.close();
            }
            originWorker.shutdownNow();
        }
    }

    private static String request(final String url, final String method, final String headers)
            throws Exception {
        final URI uri = URI.create(url);
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(5000);
            final String target = uri.getRawPath()
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            socket.getOutputStream().write((method + " " + target + " HTTP/1.1\r\nHost: "
                    + uri.getHost() + "\r\n" + headers + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
