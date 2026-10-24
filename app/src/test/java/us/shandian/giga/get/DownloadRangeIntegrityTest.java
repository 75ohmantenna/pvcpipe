package us.shandian.giga.get;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import us.shandian.giga.io.FileStream;
import us.shandian.giga.service.DownloadManagerService;
import us.shandian.giga.util.Utility;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Exercises real download workers against bounded local HTTP responses and real file bytes. */
public class DownloadRangeIntegrityTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private final byte[] content = new byte[DownloadMission.BLOCK_SIZE * 2 + 137];
    private ServerSocket server;
    private ExecutorService executor;
    private File target;
    private final AtomicInteger mediaRequests = new AtomicInteger();
    private final AtomicInteger missions = new AtomicInteger();
    private final List<RetryMission> retryMissions = new ArrayList<>();
    private final AtomicInteger refusedHeadRequests = new AtomicInteger();
    private ResponseRule rule;
    // When set, the server answers every HEAD request with this status and no length.
    private volatile int headRefusalStatus;

    @FunctionalInterface
    private interface ResponseRule {
        void reply(LocalExchange exchange, long start, long end) throws IOException;
    }

    @Before
    public void setUp() throws Exception {
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31 + i / 101);
        }
        target = folder.newFile("download.bin");
        server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        executor = Executors.newCachedThreadPool();
        rule = (exchange, start, end) -> sendRange(exchange, start, end, start, end,
                "bytes " + start + "-" + end + "/" + content.length, end - start + 1);
        executor.execute(() -> {
            while (!server.isClosed()) {
                try {
                    final Socket socket = server.accept();
                    executor.execute(() -> {
                        try (LocalExchange exchange = new LocalExchange(socket)) {
                            handle(exchange);
                        } catch (final IOException ignored) {
                            // Invalid/truncated fixtures can close their sockets early.
                        }
                    });
                } catch (final IOException closed) {
                    return;
                }
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        try {
            for (final RetryMission mission : retryMissions) {
                mission.releaseReconnect.countDown();
                mission.releaseExit.countDown();
                try (var logging = mockStatic(Log.class)) {
                    mission.pause();
                }
                mission.joinCreatedThreads();
            }
        } finally {
            server.close();
            executor.shutdownNow();
            if (!retryMissions.isEmpty()) {
                assertTrue("HTTP fixture threads leaked",
                        executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private void handle(final LocalExchange exchange) throws IOException {
        try {
            final String header = exchange.getRequestHeaders().getFirst("Range");
            if (headRefusalStatus != 0 && "HEAD".equals(exchange.getRequestMethod())) {
                refusedHeadRequests.incrementAndGet();
                exchange.sendResponseHeaders(headRefusalStatus, -1);
                return;
            }
            if ("HEAD".equals(exchange.getRequestMethod()) && (header == null
                    || "bytes=0-".equals(header))) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(content.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            long start = 0;
            long end = content.length - 1;
            if (header != null) {
                final String[] parts = header.substring("bytes=".length()).split("-", -1);
                start = Long.parseLong(parts[0]);
                if (!parts[1].isEmpty()) {
                    end = Math.min(end, Long.parseLong(parts[1]));
                }
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                mediaRequests.incrementAndGet();
            }
            rule.reply(exchange, start, end);
        } finally {
            exchange.close();
        }
    }

    private void sendRange(final LocalExchange exchange, final long requestStart,
                           final long requestEnd, final long actualStart, final long actualEnd,
                           final String rangeHeader, final long bodyLength) throws IOException {
        if (rangeHeader != null) {
            exchange.getResponseHeaders().set("Content-Range", rangeHeader);
        }
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(bodyLength));
            exchange.sendResponseHeaders(206, -1);
            return;
        }
        exchange.sendResponseHeaders(206, bodyLength);
        exchange.getResponseBody().write(content, (int) actualStart,
                (int) (actualEnd - actualStart + 1));
    }

    private void sendVersionedRange(final LocalExchange exchange, final long start,
                                    final long end, final byte[] version,
                                    final String etag) throws IOException {
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Content-Range",
                "bytes " + start + "-" + end + "/" + version.length);
        exchange.sendResponseHeaders(206, end - start + 1);
        if ("GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseBody().write(version, (int) start, (int) (end - start + 1));
        }
    }

    private static final class LocalExchange implements AutoCloseable {
        private final Socket socket;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final String path;

        LocalExchange(final Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(5000);
            final BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            final String[] request = reader.readLine().split(" ", 3);
            method = request[0];
            path = request[1];
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                final int colon = line.indexOf(':');
                if (colon >= 0) {
                    requestHeaders.set(line.substring(0, colon), line.substring(colon + 1).trim());
                }
            }
        }

        Headers getRequestHeaders() {
            return requestHeaders;
        }

        Headers getResponseHeaders() {
            return responseHeaders;
        }

        String getRequestMethod() {
            return method;
        }

        void sendResponseHeaders(final int status, final long length) throws IOException {
            if (length >= 0 && responseHeaders.getFirst("Content-Length") == null) {
                responseHeaders.set("Content-Length", String.valueOf(length));
            }
            final StringBuilder headers = new StringBuilder("HTTP/1.1 " + status + " "
                    + (status == 206 ? "Partial Content" : "OK") + "\r\n");
            responseHeaders.values.forEach((key, value) -> headers.append(key).append(": ")
                    .append(value).append("\r\n"));
            headers.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        }

        OutputStream getResponseBody() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class Headers {
        private final Map<String, String> values = new HashMap<>();

        String getFirst(final String name) {
            return values.get(name.toLowerCase(Locale.ROOT));
        }

        void set(final String name, final String value) {
            values.put(name.toLowerCase(Locale.ROOT), value);
        }
    }

    private DownloadMission mission(final CountDownLatch terminal) throws Exception {
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.existsAsFile()).thenReturn(true);
        when(storage.canWrite()).thenReturn(true);
        when(storage.getStream()).thenAnswer(ignored -> new FileStream(target));
        final DownloadMission mission = new DownloadMission(
                new String[]{"http://" + server.getInetAddress().getHostAddress() + ":"
                        + server.getLocalPort() + "/media"}, storage, 'v', null) {
            @Override
            Thread createInitializer() {
                return new DownloadInitializer(this) {
                    @Override
                    public void run() {
                        try (var logging = mockStatic(Log.class)) {
                            super.run();
                        }
                    }
                };
            }

            @Override
            Thread createDownloadWorker(final int index) {
                return new DownloadRunnable(this, index) {
                    @Override
                    public void run() {
                        try (var logging = mockStatic(Log.class)) {
                            super.run();
                        }
                    }
                };
            }

            @Override
            Thread createFallbackWorker() {
                return new DownloadRunnableFallback(this) {
                    @Override
                    public void run() {
                        try (var logging = mockStatic(Log.class)) {
                            super.run();
                        }
                    }
                };
            }
        };
        mission.metadata = folder.newFile("download-" + missions.incrementAndGet() + ".meta");
        watchTerminal(mission, terminal);
        return mission;
    }

    private static void watchTerminal(final DownloadMission mission,
                                      final CountDownLatch terminal) {
        mission.mHandler = mock(Handler.class);
        when(mission.mHandler.obtainMessage(anyInt(), any())).thenAnswer(call -> {
            final int code = call.getArgument(0);
            if (code == DownloadManagerService.MESSAGE_ERROR
                    || code == DownloadManagerService.MESSAGE_FINISHED) {
                terminal.countDown();
            }
            return mock(Message.class);
        });
    }

    private void runAndAwait(final DownloadMission mission, final CountDownLatch terminal)
            throws Exception {
        try (var logging = mockStatic(Log.class)) {
            if (mission.blocks != null && mission.blocks.length == 0) {
                // Single-worker fallback runs on this test thread so Android's JVM Log stub
                // remains intercepted while real HTTP and real file I/O are exercised.
                mission.running = true;
                mission.threads = new Thread[]{Thread.currentThread()};
                new DownloadRunnableFallback(mission).run();
            } else {
                mission.start();
            }
            final boolean done = terminal.await(15, TimeUnit.SECONDS);
            assertTrue("download did not reach a terminal state: requests=" + mediaRequests.get()
                            + ", running=" + mission.running + ", error=" + mission.errCode
                            + ", blocks=" + (mission.blocks == null ? "null"
                            : mission.blocks.length), done);
            if (mission.blocks != null && mission.blocks.length > 0) {
                for (final Thread worker : mission.threads) {
                    worker.join(5000);
                }
            }
            if (mission.init != null) {
                mission.init.join(5000);
            }
        }
    }

    private void assertCompleted(final DownloadMission mission) throws Exception {
        assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
        assertTrue(mission.isFinished());
        assertEquals(content.length, mission.done);
        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
    }

    private void assertFailed(final DownloadMission mission) {
        assertFalse("invalid data must not be reported complete", mission.isFinished());
        assertFalse("invalid data must signal an error", mission.errCode
                == DownloadMission.ERROR_NOTHING);
    }

    @Test
    public void parallelBlocksWithShortValidRangesProduceExactBytes() throws Exception {
        rule = (exchange, start, end) -> {
            final long partEnd = "HEAD".equals(exchange.getRequestMethod())
                    ? end : Math.min(end, start + 64 * 1024 - 1);
            sendRange(exchange, start, end, start, partEnd,
                    "bytes " + start + "-" + partEnd + "/" + content.length,
                    partEnd - start + 1);
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 3;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
        assertTrue(mediaRequests.get() > 3);
    }

    @Test
    public void serverThatDoesNotAllowHeadIsAskedAgainWithGetAndDownloaded() throws Exception {
        headRefusalStatus = 405;
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 3;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
        // one refusal is enough to stop asking with HEAD
        assertEquals(1, refusedHeadRequests.get());
    }

    @Test
    public void odyseeFileHostIsAskedWithGetAndTheReferrerEvenForTheHeaders() throws Exception {
        final DownloadMission mission = mission(new CountDownLatch(1));
        final String file = "https://player.odycdn.com/v6/streams/id/file.mp4";

        // The first request wants the size. One byte tells it; "bytes=0-" would make the
        // server send the whole file to a caller that only reads the headers.
        final HttpURLConnection probe = mission.openConnection(file, true, 0, 0);
        assertEquals("GET", probe.getRequestMethod());
        assertEquals("bytes=0-0", probe.getRequestProperty("Range"));
        assertEquals("https://odysee.com/", probe.getRequestProperty("Referer"));

        // a probe for a closed range, and one without a range, keep what they asked for
        assertEquals("bytes=90-100",
                mission.openConnection(file, true, 90, 100).getRequestProperty("Range"));
        final HttpURLConnection plain = mission.openConnection(file, true, -1, -1);
        assertEquals("GET", plain.getRequestMethod());
        assertNull(plain.getRequestProperty("Range"));

        // a segment of an HLS rendition on the same host is asked with HEAD
        final HttpURLConnection segment = mission.openConnection(
                "https://player.odycdn.com/v6/streams/id/hash/v0_s000001.ts", true, 0, 0);
        assertEquals("HEAD", segment.getRequestMethod());
        assertEquals("bytes=0-", segment.getRequestProperty("Range"));
        assertEquals("https://odysee.com/", segment.getRequestProperty("Referer"));

        final HttpURLConnection block = mission.openConnection(file, false, 0, 99);
        assertEquals("GET", block.getRequestMethod());
        assertEquals("https://odysee.com/", block.getRequestProperty("Referer"));

        // any other host is asked as before
        final HttpURLConnection other = mission.openConnection(mission.urls[0], true, 0, 0);
        assertEquals("HEAD", other.getRequestMethod());
        assertNull(other.getRequestProperty("Referer"));
    }

    @Test
    public void parallelWorkersNeverWriteBeyondAdvertisedInterval() throws Exception {
        rule = (exchange, start, end) -> {
            sendRange(exchange, start, end, start, end,
                    "bytes " + start + "-" + end + "/" + content.length,
                    end - start + 1);
            if ("GET".equals(exchange.getRequestMethod()) && end + 8 < content.length) {
                // A misbehaving server sends more body bytes than its valid 206 promises.
                exchange.getResponseBody().write(content, (int) (end + 1), 8);
            }
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 3;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
    }

    @Test
    public void malformedRangeProbeFailsBeforeWorkersOrFileAllocation() throws Exception {
        rule = (exchange, start, end) ->
                sendRange(exchange, start, end, start, end, null, end - start + 1);
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 3;
        runAndAwait(mission, terminal);
        assertFailed(mission);
        assertEquals(0, mediaRequests.get());
        assertEquals(0, target.length());
    }

    @Test
    public void missingMalformedWrongStartWrongEndWrongTotalAndLengthFailWithoutSuccess()
            throws Exception {
        for (int caseNumber = 0; caseNumber < 6; caseNumber++) {
            final int scenario = caseNumber;
            mediaRequests.set(0);
            rule = (exchange, start, end) -> {
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    sendRange(exchange, start, end, start, end,
                            "bytes " + start + "-" + end + "/" + content.length,
                            end - start + 1);
                    return;
                }
                final long actualStart = scenario == 2 ? start + 1 : start;
                final long actualEnd = scenario == 3 ? end + 1
                        : scenario == 2 ? actualStart : start;
                final String header = scenario == 0 ? null : scenario == 1 ? "bytes rubbish"
                        : "bytes " + actualStart + "-" + actualEnd + "/"
                        + (scenario == 4 ? content.length + 1 : content.length);
                final long bodyEnd = scenario == 5 ? actualEnd + 1 : actualEnd;
                sendRange(exchange, start, end, actualStart, bodyEnd,
                        header, bodyEnd - actualStart + 1);
            };
            final CountDownLatch terminal = new CountDownLatch(1);
            final DownloadMission mission = mission(terminal);
            mission.threadCount = 2;
            runAndAwait(mission, terminal);
            assertFailed(mission);
            assertEquals("worker must not write a rejected range", 0, mission.done);
            assertArrayEquals("no rejected body may be written to the preallocated file",
                    new byte[content.length], Files.readAllBytes(target.toPath()));
        }
    }

    @Test
    public void encodedPartialResponsesFailBeforeWritingTheirBodies() throws Exception {
        rule = (exchange, start, end) -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }
            sendRange(exchange, start, end, start, end,
                    "bytes " + start + "-" + end + "/" + content.length,
                    end - start + 1);
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 2;
        runAndAwait(mission, terminal);
        assertFailed(mission);
        assertEquals(0, mission.done);
    }

    @Test
    public void truncatedPartialResponseFailsRatherThanCompleting() throws Exception {
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                sendRange(exchange, start, end, start, end,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            } else {
                sendRange(exchange, start, end, start, start + 4,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            }
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.maxRetry = 0;
        runAndAwait(mission, terminal);
        assertFailed(mission);
    }

    @Test
    public void fallbackResumeUsesVerifiedRangeAndPreservesPrefix() throws Exception {
        final int prefix = 187;
        Files.write(target.toPath(), Arrays.copyOf(content, prefix));
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.blocks = new int[0];
        mission.length = content.length;
        mission.fallbackResumeOffset = prefix;
        mission.done = prefix;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
    }

    @Test
    public void fallbackResumeRejectsMismatchedStart() throws Exception {
        final int prefix = 187;
        Files.write(target.toPath(), Arrays.copyOf(content, prefix));
        rule = (exchange, start, end) -> sendRange(exchange, start, end, start - 1, end,
                "bytes " + (start - 1) + "-" + end + "/" + content.length,
                end - start + 2);
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.blocks = new int[0];
        mission.length = content.length;
        mission.fallbackResumeOffset = prefix;
        mission.done = prefix;
        runAndAwait(mission, terminal);
        assertFailed(mission);
        assertArrayEquals(Arrays.copyOf(content, prefix), Files.readAllBytes(target.toPath()));
    }

    @Test
    public void resume416RestartsSingleWorkerWithoutRetainingOldBytes() throws Exception {
        Files.write(target.toPath(), new byte[content.length + 40]);
        rule = (exchange, start, end) -> {
            if (start > 0) {
                exchange.sendResponseHeaders(416, 0);
            } else {
                sendRange(exchange, start, end, start, end,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            }
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.blocks = new int[0];
        mission.length = content.length;
        mission.fallbackResumeOffset = 200;
        mission.done = 200;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
    }

    @Test
    public void ignoredResumeRangeRestartsSingleWorkerAndDiscardsOldTail() throws Exception {
        Files.write(target.toPath(), new byte[content.length + 40]);
        rule = (exchange, start, end) -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.blocks = new int[0];
        mission.length = content.length;
        mission.fallbackResumeOffset = 200;
        mission.done = 200;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
    }

    @Test
    public void fullResponseWithoutLengthRejectsExcessBytes() throws Exception {
        Files.write(target.toPath(), Arrays.copyOf(content, 187));
        rule = (exchange, start, end) -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.getResponseBody().write(content);
            exchange.getResponseBody().write(0x42);
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.blocks = new int[0];
        mission.length = content.length;
        mission.fallbackResumeOffset = 187;
        mission.done = 187;
        runAndAwait(mission, terminal);
        assertFailed(mission);
    }

    @Test
    public void lengthlessRetryStaysUnknownUntilEndOfFullResponse() throws Exception {
        assertUnknownLengthRetry(false);
    }

    @Test
    public void lengthlessRetryCanAcceptNewKnownLength() throws Exception {
        assertUnknownLengthRetry(true);
    }

    private void assertUnknownLengthRetry(final boolean sendLengthOnRetry) throws Exception {
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        final AtomicInteger partialProgress = new AtomicInteger();
        mission.blocks = new int[0];
        mission.unknownLength = true;
        mission.maxRetry = 1;
        rule = (exchange, start, end) -> {
            if (mediaRequests.get() == 1) {
                exchange.getResponseHeaders().set("Transfer-Encoding", "chunked");
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().write("28\r\n".getBytes(StandardCharsets.US_ASCII));
                exchange.getResponseBody().write(content, 0, 40);
                exchange.getResponseBody().write("\r\n50\r\n".getBytes(StandardCharsets.US_ASCII));
                exchange.getResponseBody().write(content, 40, 20);
            } else {
                synchronized (mission) {
                    partialProgress.set((int) mission.done);
                }
                exchange.sendResponseHeaders(200, sendLengthOnRetry ? content.length : -1);
                exchange.getResponseBody().write(content);
            }
        };
        runAndAwait(mission, terminal);
        assertTrue("first response must contribute partial bytes", partialProgress.get() > 0);
        assertEquals(2, mediaRequests.get());
        assertCompleted(mission);
    }

    @Test
    public void strongValidatorKeepsParallelBlocksOnTheSameRepresentation() throws Exception {
        final AtomicInteger conditionalRequests = new AtomicInteger();
        rule = (exchange, start, end) -> {
            if ("GET".equals(exchange.getRequestMethod())
                    && "\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-Range"))) {
                conditionalRequests.incrementAndGet();
            }
            sendVersionedRange(exchange, start, end, content, "\"v1\"");
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 3;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
        assertEquals(mediaRequests.get(), conditionalRequests.get());
    }

    @Test
    public void stableRedirectKeepsParallelRangesOnTheSameResource() throws Exception {
        rule = (exchange, start, end) -> {
            if ("/media".equals(exchange.path)) {
                exchange.getResponseHeaders().set("Location", "/body");
                exchange.sendResponseHeaders(302, 0);
            } else {
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            }
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 3;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
    }

    @Test
    public void strongValidatorSurvivesFailedWorkerAndResume() throws Exception {
        final AtomicInteger conditionalRequests = new AtomicInteger();
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            } else if (mediaRequests.get() == 1) {
                sendRange(exchange, start, end, start, start + 186,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            } else {
                if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-Range"))) {
                    conditionalRequests.incrementAndGet();
                }
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            }
        };
        final CountDownLatch first = new CountDownLatch(1);
        final DownloadMission mission = mission(first);
        mission.threadCount = 1;
        mission.maxRetry = 0;
        mission.running = true;
        try (var logging = mockStatic(Log.class)) {
            new DownloadInitializer(mission).run();
        }
        assertEquals(0, mission.blocks.length);
        runAndAwait(mission, first);
        assertFailed(mission);
        assertEquals(187, mission.fallbackResumeOffset);
        assertEquals("\"v1\"", mission.resourceIdentity.condition);

        final CountDownLatch resumed = new CountDownLatch(1);
        watchTerminal(mission, resumed);
        mission.errCode = DownloadMission.ERROR_NOTHING;
        runAndAwait(mission, resumed);
        assertCompleted(mission);
        assertEquals(1, conditionalRequests.get());
    }

    @Test
    public void parallelBlocksRejectDifferentSameSizeRepresentation() throws Exception {
        final byte[] changed = Arrays.copyOf(content, content.length);
        for (int i = 0; i < changed.length; i++) {
            changed[i] ^= 0x5a;
        }
        rule = (exchange, start, end) ->
                sendVersionedRange(exchange, start, end,
                        start == 0 || "HEAD".equals(exchange.getRequestMethod())
                                ? content : changed,
                        start == 0 || "HEAD".equals(exchange.getRequestMethod())
                                ? "\"v1\"" : "\"v2\"");
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 3;
        runAndAwait(mission, terminal);
        assertTrue(mediaRequests.get() >= 2);
        assertFailed(mission);
    }

    @Test
    public void resumedBytesRejectDifferentSameSizeRepresentation() throws Exception {
        final int prefix = 187;
        Files.write(target.toPath(), Arrays.copyOf(content, prefix));
        final byte[] changed = Arrays.copyOf(content, content.length);
        for (int i = 0; i < changed.length; i++) {
            changed[i] ^= 0x5a;
        }
        rule = (exchange, start, end) ->
                sendVersionedRange(exchange, start, end, changed, "\"v2\"");
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.blocks = new int[0];
        mission.length = content.length;
        mission.fallbackResumeOffset = prefix;
        mission.done = prefix;
        runAndAwait(mission, terminal);
        assertFailed(mission);
        assertArrayEquals(Arrays.copyOf(content, prefix), Files.readAllBytes(target.toPath()));
    }

    @Test
    public void resumedStrongValidatorRejectsSameSizeReplacement() throws Exception {
        final int prefix = 187;
        final byte[] changed = Arrays.copyOf(content, content.length);
        for (int i = 0; i < changed.length; i++) {
            changed[i] ^= 0x5a;
        }
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            } else if (mediaRequests.get() == 1) {
                sendRange(exchange, start, end, start, start + prefix - 1,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            } else {
                sendVersionedRange(exchange, start, end, changed, "\"v2\"");
            }
        };
        final CountDownLatch first = new CountDownLatch(1);
        final DownloadMission mission = mission(first);
        mission.threadCount = 1;
        mission.maxRetry = 0;
        mission.running = true;
        try (var logging = mockStatic(Log.class)) {
            new DownloadInitializer(mission).run();
        }
        runAndAwait(mission, first);
        assertFailed(mission);
        assertEquals("\"v1\"", mission.resourceIdentity.condition);

        final CountDownLatch resumed = new CountDownLatch(1);
        watchTerminal(mission, resumed);
        mission.errCode = DownloadMission.ERROR_NOTHING;
        mission.maxRetry = 5; // A validator mismatch is terminal, not a retryable bad range.
        runAndAwait(mission, resumed);
        assertFailed(mission);
        assertEquals(2, mediaRequests.get());
        assertArrayEquals(Arrays.copyOf(content, prefix),
                Arrays.copyOf(Files.readAllBytes(target.toPath()), prefix));
    }

    @Test
    public void conditionalFullResponseStopsParallelWorkersWithoutReplacingSharedFile()
            throws Exception {
        final byte[] changed = Arrays.copyOf(content, content.length);
        for (int i = 0; i < changed.length; i++) {
            changed[i] ^= 0x5a;
        }
        final CountDownLatch firstWorkerStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirstWorker = new CountDownLatch(1);
        final AtomicInteger conditionalFullResponses = new AtomicInteger();
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            } else if (start == 0) {
                exchange.getResponseHeaders().set("ETag", "\"v1\"");
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + start + "-" + end + "/" + content.length);
                exchange.sendResponseHeaders(206, end - start + 1);
                exchange.getResponseBody().write(content, 0, 1024);
                firstWorkerStarted.countDown();
                try {
                    if (!releaseFirstWorker.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Second worker did not request a range");
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                exchange.getResponseBody().write(content, 1024, (int) (end - start - 1023));
            } else {
                try {
                    if (!firstWorkerStarted.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("First worker did not start");
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-Range"))) {
                    conditionalFullResponses.incrementAndGet();
                }
                exchange.getResponseHeaders().set("ETag", "\"v2\"");
                exchange.sendResponseHeaders(200, content.length);
                releaseFirstWorker.countDown();
                exchange.getResponseBody().write(changed);
            }
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 2;
        mission.maxRetry = 5;
        runAndAwait(mission, terminal);
        assertFailed(mission);
        assertTrue(conditionalFullResponses.get() > 0);
        assertTrue(mediaRequests.get() <= 3); // Two workers; no retry of the changed version.
        for (final Thread worker : mission.threads) {
            assertFalse(worker.isAlive());
        }
        assertEquals(content.length, target.length());
    }

    @Test
    public void weakEtagDoesNotBecomeAnIfRangeCondition() throws Exception {
        final AtomicInteger conditionalRequests = new AtomicInteger();
        rule = (exchange, start, end) -> {
            exchange.getResponseHeaders().set("Last-Modified", "Mon, 06 Oct 2025 10:00:00 GMT");
            exchange.getResponseHeaders().set("Date", "Mon, 06 Oct 2025 10:00:02 GMT");
            if ("GET".equals(exchange.getRequestMethod())
                    && exchange.getRequestHeaders().getFirst("If-Range") != null) {
                conditionalRequests.incrementAndGet();
            }
            sendVersionedRange(exchange, start, end, content, "W/\"v1\"");
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 2;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
        assertEquals(0, conditionalRequests.get());
    }

    @Test
    public void unqualifiedLastModifiedIsNotUsedAsIfRange() throws Exception {
        final AtomicInteger conditionalRequests = new AtomicInteger();
        rule = (exchange, start, end) -> {
            exchange.getResponseHeaders().set("Last-Modified", "Mon, 06 Oct 2025 10:00:00 GMT");
            exchange.getResponseHeaders().set("Date", "Mon, 06 Oct 2025 10:00:00 GMT");
            if ("GET".equals(exchange.getRequestMethod())
                    && exchange.getRequestHeaders().getFirst("If-Range") != null) {
                conditionalRequests.incrementAndGet();
            }
            sendRange(exchange, start, end, start, end,
                    "bytes " + start + "-" + end + "/" + content.length, end - start + 1);
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 2;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
        assertEquals(0, conditionalRequests.get());
    }

    @Test
    public void provenStrongLastModifiedMayGuardByteRanges() throws Exception {
        final String modified = "Mon, 06 Oct 2025 10:00:00 GMT";
        final String date = "Mon, 06 Oct 2025 10:00:02 GMT";
        final AtomicInteger conditionalRequests = new AtomicInteger();
        rule = (exchange, start, end) -> {
            exchange.getResponseHeaders().set("Last-Modified", modified);
            exchange.getResponseHeaders().set("Date", date);
            if ("GET".equals(exchange.getRequestMethod())
                    && modified.equals(exchange.getRequestHeaders().getFirst("If-Range"))) {
                conditionalRequests.incrementAndGet();
            }
            sendRange(exchange, start, end, start, end,
                    "bytes " + start + "-" + end + "/" + content.length, end - start + 1);
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 2;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
        assertEquals(mediaRequests.get(), conditionalRequests.get());
    }

    @Test
    public void conditionalFullResponseReplacesSingleWorkerBytesAndValidator() throws Exception {
        final byte[] changed = Arrays.copyOf(content, content.length);
        for (int i = 0; i < changed.length; i++) {
            changed[i] ^= 0x5a;
        }
        final AtomicInteger conditionalFullResponses = new AtomicInteger();
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            } else if (mediaRequests.get() == 1) {
                sendRange(exchange, start, end, start, start + 186,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            } else {
                if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-Range"))) {
                    conditionalFullResponses.incrementAndGet();
                }
                exchange.getResponseHeaders().set("ETag", "\"v2\"");
                exchange.sendResponseHeaders(200, changed.length);
                exchange.getResponseBody().write(changed);
            }
        };
        final CountDownLatch first = new CountDownLatch(1);
        final DownloadMission mission = mission(first);
        mission.threadCount = 1;
        mission.maxRetry = 0;
        mission.running = true;
        try (var logging = mockStatic(Log.class)) {
            new DownloadInitializer(mission).run();
        }
        runAndAwait(mission, first);
        assertFailed(mission);
        final CountDownLatch resumed = new CountDownLatch(1);
        watchTerminal(mission, resumed);
        mission.errCode = DownloadMission.ERROR_NOTHING;
        runAndAwait(mission, resumed);
        assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
        assertTrue(mission.isFinished());
        assertArrayEquals(changed, Files.readAllBytes(target.toPath()));
        assertEquals("\"v2\"", mission.resourceIdentity.condition);
        assertEquals(1, conditionalFullResponses.get());
    }

    @Test
    public void replacementCheckpointCannotResumePastNewlyWrittenBytes() throws Exception {
        final byte[] changed = content.clone();
        for (int i = 0; i < changed.length; i++) {
            changed[i] ^= 0x5a;
        }
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        final AtomicReference<Long> replacementOffset = new AtomicReference<>();
        final AtomicReference<Exception> peerFailure = new AtomicReference<>();
        mission.blocks = new int[0];
        mission.unknownLength = true;
        mission.maxRetry = 1;
        mission.metadata = null;
        rule = (exchange, start, end) -> {
            exchange.getResponseHeaders().set("ETag",
                    mediaRequests.get() == 1 ? "\"v1\"" : "\"v2\"");
            exchange.sendResponseHeaders(200, content.length);
            if (mediaRequests.get() == 1) {
                exchange.getResponseBody().write(content, 0, 187);
                return; // A transport failure, not a representation-completeness success.
            }
            exchange.getResponseBody().write(changed, 0, 100);
            exchange.getResponseBody().flush();
            try {
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    synchronized (mission) {
                        if (mission.done == 100) {
                            replacementOffset.set(mission.fallbackResumeOffset);
                            mission.pause();
                            return;
                        }
                    }
                    Thread.sleep(1);
                }
                throw new IOException("Replacement prefix was not accepted");
            } catch (final Exception error) {
                peerFailure.set(error);
                throw new IOException(error);
            }
        };
        try (var logging = mockStatic(Log.class)) {
            mission.running = true;
            new DownloadRunnableFallback(mission).run();
        }
        assertNull(peerFailure.get());
        assertEquals(Long.valueOf(0), replacementOffset.get());
        assertEquals(100, mission.done);
        assertArrayEquals(Arrays.copyOf(changed, 100), Files.readAllBytes(target.toPath()));

        rule = (exchange, start, end) ->
                sendVersionedRange(exchange, start, end, changed, "\"v2\"");
        runAndAwait(mission, terminal);
        assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
        assertTrue(mission.isFinished());
        assertArrayEquals(changed, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void missionSerializationRetainsBoundIdentityAndLoadsUnboundState() throws Exception {
        rule = (exchange, start, end) ->
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
        final String url = "http://" + server.getInetAddress().getHostAddress() + ":"
                + server.getLocalPort() + "/media";
        final HttpURLConnection probe = (HttpURLConnection) new URL(url).openConnection();
        probe.setRequestMethod("HEAD");
        probe.setRequestProperty("Range", "bytes=1-1");
        probe.getResponseCode();
        final DownloadMission bound = new DownloadMission(new String[]{url}, null, 'v', null);
        bound.resourceIdentity = ResourceIdentity.from(probe, url);
        bound.blocks = new int[]{187};
        bound.done = 187;
        bound.length = content.length;
        probe.disconnect();

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(bound);
            output.writeObject(new DownloadMission(new String[]{url}, null, 'v', null));
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            final DownloadMission restored = (DownloadMission) input.readObject();
            assertEquals("\"v1\"", restored.resourceIdentity.condition);
            assertEquals(url, restored.resourceIdentity.requestUrl);
            assertEquals(url, restored.resourceIdentity.effectiveUrl);
            assertEquals(187, restored.blocks[0]);
            assertEquals(187, restored.done);
            final DownloadMission unbound = (DownloadMission) input.readObject();
            assertTrue(unbound.resourceIdentity == null);
        }
    }

    @Test
    public void restoredCleanRestartStillRequestsAFullRepresentation() throws Exception {
        rule = (exchange, start, end) -> {
            assertNull(exchange.getRequestHeaders().getFirst("Range"));
            assertNull(exchange.getRequestHeaders().getFirst("If-Range"));
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission template = mission(terminal);
        final DownloadMission saved = new DownloadMission(template.urls, null, 'v', null);
        saved.blocks = new int[0];
        saved.length = content.length + 40;
        saved.done = 100;
        saved.fallbackResumeOffset = 187;
        Files.write(target.toPath(), new byte[100]);
        saved.forceFullDownload = true;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(saved);
        }
        final DownloadMission restored;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (DownloadMission) input.readObject();
        }
        restored.storage = template.storage;
        watchTerminal(restored, terminal);
        runAndAwait(restored, terminal);
        assertCompleted(restored);
    }

    @Test
    public void cleanRestartRejectsUnsolicitedPartialResponseWithoutWritingOrRetrying()
            throws Exception {
        final byte[] previous = Arrays.copyOf(content, 187);
        Files.write(target.toPath(), previous);
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.blocks = new int[0];
        mission.unknownLength = true;
        mission.forceFullDownload = true;
        mission.maxRetry = 5;
        runAndAwait(mission, terminal);
        assertFailed(mission);
        assertEquals(0, mission.done);
        assertEquals(1, mediaRequests.get());
        assertArrayEquals(previous, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void failedCleanRestartCheckpointLeavesPartialFileUntouched() throws Exception {
        final byte[] previous = Arrays.copyOf(content, 187);
        Files.write(target.toPath(), previous);
        rule = (exchange, start, end) -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.blocks = new int[]{187, 0, 0};
        mission.length = content.length;
        mission.done = 187;
        mission.errObject = new ValidatedRange.InvalidRangeException("Restart required");
        mission.checkpointReady = true;
        try (var logging = mockStatic(Log.class);
             var utility = mockStatic(Utility.class)) {
            utility.when(() -> Utility.writeToFile(mission.metadata, mission)).thenReturn(false);
            mission.start();
            assertTrue(terminal.await(15, TimeUnit.SECONDS));
            for (final Thread worker : mission.threads) {
                worker.join(5000);
            }
        }
        assertFailed(mission);
        assertFalse(mission.running);
        assertFalse(mission.checkpointReady);
        assertEquals(DownloadMission.ERROR_FILE_CREATION, mission.errCode);
        assertArrayEquals(previous, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void reextractedUrlRestartsEvenIfDifferentResourceReusesEtag() throws Exception {
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                sendVersionedRange(exchange, start, end, content, "\"same-tag\"");
            } else {
                sendRange(exchange, start, end, start, start + 186,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            }
        };
        final CountDownLatch first = new CountDownLatch(1);
        final DownloadMission mission = mission(first);
        mission.threadCount = 1;
        mission.maxRetry = 0;
        mission.running = true;
        try (var logging = mockStatic(Log.class)) {
            new DownloadInitializer(mission).run();
        }
        runAndAwait(mission, first);
        assertFailed(mission);
        assertEquals(187, mission.fallbackResumeOffset);
        mission.threads = new Thread[0]; // The synchronous fallback worker has exited.

        final String newUrl = mission.urls[0] + "?renewed=1";
        final DownloadMissionRecover recovery =
                new DownloadMissionRecover(mission, DownloadMission.ERROR_HTTP_FORBIDDEN);
        final Method resolve = DownloadMissionRecover.class
                .getDeclaredMethod("resolve", String.class);
        resolve.setAccessible(true);
        try (var logging = mockStatic(Log.class)) {
            resolve.invoke(recovery, newUrl);
        }
        assertEquals(newUrl, mission.urls[0]);
        assertTrue(mission.resourceIdentity == null);
        assertTrue(mission.blocks == null);
        assertEquals(0, mission.done);

        final byte[] changed = Arrays.copyOf(content, content.length);
        for (int i = 0; i < changed.length; i++) {
            changed[i] ^= 0x5a;
        }
        rule = (exchange, start, end) ->
                sendVersionedRange(exchange, start, end, changed, "\"same-tag\"");
        final CountDownLatch restarted = new CountDownLatch(1);
        watchTerminal(mission, restarted);
        mission.threadCount = 2;
        runAndAwait(mission, restarted);
        assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
        assertTrue(mission.isFinished());
        assertArrayEquals(changed, Files.readAllBytes(target.toPath()));
        assertEquals(newUrl, mission.resourceIdentity.requestUrl);
    }

    @Test
    public void sameUrlRecoveryProbesWithGetAndPreservesValidatedProgress() throws Exception {
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            } else {
                sendRange(exchange, start, end, start, start + 186,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            }
        };
        final CountDownLatch first = new CountDownLatch(1);
        final DownloadMission mission = mission(first);
        mission.threadCount = 1;
        mission.maxRetry = 0;
        mission.running = true;
        try (var logging = mockStatic(Log.class)) {
            new DownloadInitializer(mission).run();
        }
        runAndAwait(mission, first);
        assertFailed(mission);
        assertEquals(187, mission.fallbackResumeOffset);

        final AtomicReference<String> conditional = new AtomicReference<>();
        final AtomicReference<String> requestedRange = new AtomicReference<>();
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("ETag", "\"v1\"");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(content.length));
                exchange.sendResponseHeaders(200, -1); // A compliant HEAD ignores Range.
            } else {
                conditional.set(exchange.getRequestHeaders().getFirst("If-Range"));
                requestedRange.set(exchange.getRequestHeaders().getFirst("Range"));
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            }
        };
        final DownloadMissionRecover recovery =
                new DownloadMissionRecover(mission, DownloadMission.ERROR_HTTP_FORBIDDEN);
        final Method resolve = DownloadMissionRecover.class
                .getDeclaredMethod("resolve", String.class);
        resolve.setAccessible(true);
        try (var logging = mockStatic(Log.class)) {
            resolve.invoke(recovery, mission.urls[0]);
        }
        assertEquals("\"v1\"", conditional.get());
        assertEquals("bytes=" + (content.length - 10) + "-" + (content.length - 1),
                requestedRange.get());
        assertEquals(187, mission.fallbackResumeOffset);
        assertEquals(187, mission.done);
        assertArrayEquals(Arrays.copyOf(content, 187), Files.readAllBytes(target.toPath()));
    }

    @Test
    public void oldParallelProgressCannotAdoptANewlyFetchedValidator() throws Exception {
        final int prefix = DownloadMission.BLOCK_SIZE;
        Files.write(target.toPath(), Arrays.copyOf(content, prefix));
        final byte[] changed = Arrays.copyOf(content, content.length);
        for (int i = 0; i < changed.length; i++) {
            changed[i] ^= 0x5a;
        }
        rule = (exchange, start, end) ->
                sendVersionedRange(exchange, start, end, changed, "\"v2\"");
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.blocks = new int[]{-1, 0, 0};
        mission.length = content.length;
        mission.done = prefix;
        mission.threadCount = 2;
        runAndAwait(mission, terminal);
        assertFailed(mission);
        assertArrayEquals(Arrays.copyOf(content, prefix), Files.readAllBytes(target.toPath()));
    }

    @Test
    public void firstGetCanBindValidatorWhenHeadProbeOmitsIt() throws Exception {
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                sendRange(exchange, start, end, start, end,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            } else {
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            }
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 3;
        runAndAwait(mission, terminal);
        assertCompleted(mission);
        assertEquals("\"v1\"", mission.resourceIdentity.condition);
    }

    @Test
    public void oldUninitializedErrorDoesNotReplaceFreshRangeInitialization() throws Exception {
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        // A restored checkpoint can retain a failed probe, but no initialized blocks.
        mission.errObject = new ValidatedRange.InvalidRangeException("Previous failed probe");
        runAndAwait(mission, terminal);
        assertCompleted(mission);
    }

    @Test
    public void startAfterRedirectRejectionRestartsIdenticalBytesWithoutRanges() throws Exception {
        assertRedirectRestart(false);
    }

    @Test
    public void startAfterRedirectRejectionReplacesPartialBytesDespiteReusedEtag()
            throws Exception {
        assertRedirectRestart(true);
    }

    private void assertRedirectRestart(final boolean partial) throws Exception {
        final byte[] replacement = content.clone();
        if (partial) {
            for (int i = 0; i < replacement.length; i++) {
                replacement[i] ^= 0x5a;
            }
        }
        final AtomicInteger fullRequests = new AtomicInteger();
        rule = (exchange, start, end) -> {
            if ("/media".equals(exchange.path)) {
                exchange.getResponseHeaders().set("Location",
                        "HEAD".equals(exchange.getRequestMethod()) ? "/probe" : "/body");
                exchange.sendResponseHeaders(302, 0);
            } else if (exchange.getRequestHeaders().getFirst("Range") == null
                    && "GET".equals(exchange.getRequestMethod())) {
                fullRequests.incrementAndGet();
                exchange.getResponseHeaders().set("ETag", "\"same-tag\"");
                exchange.sendResponseHeaders(200, replacement.length);
                exchange.getResponseBody().write(replacement);
            } else {
                sendVersionedRange(exchange, start, end,
                        "HEAD".equals(exchange.getRequestMethod()) ? content : replacement,
                        "\"same-tag\"");
            }
        };
        final CountDownLatch rejected = new CountDownLatch(1);
        final DownloadMission mission = mission(rejected);
        mission.threadCount = 3;
        final byte[] saved = Arrays.copyOf(content, 123 + 187);
        System.arraycopy(content, 0, saved, 123, 187);
        if (partial) {
            final HttpURLConnection probe = mission.openConnection(true, 1, 1);
            probe.getResponseCode();
            mission.resourceIdentity = ResourceIdentity.from(probe, mission.urls[0]);
            probe.disconnect();
            mission.urls = new String[]{"https://example.invalid/completed", mission.urls[0]};
            mission.offsets = new long[]{0, 123};
            mission.current = 1;
            Files.write(target.toPath(), saved);
            mission.blocks = new int[]{187, 0, 0};
            mission.length = content.length;
            mission.done = 123 + 187;
        }
        runAndAwait(mission, rejected);
        assertFailed(mission);
        assertTrue(mission.errObject instanceof ValidatedRange.InvalidRangeException);
        assertEquals(partial ? 123 + 187 : 0, mission.done);
        if (partial) {
            assertArrayEquals(saved, Files.readAllBytes(target.toPath()));
            // A second worker can report an HTTP status after the integrity error.
            try (var logging = mockStatic(Log.class)) {
                mission.notifyError(new DownloadMission.HttpError(202));
            }
        }

        restartAfterLastWriter(mission);
        assertTrue(mission.isFinished());
        assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
        assertEquals(1, fullRequests.get());
        final byte[] expected;
        if (partial) {
            expected = Arrays.copyOf(content, 123 + replacement.length);
            System.arraycopy(replacement, 0, expected, 123, replacement.length);
        } else {
            expected = replacement;
        }
        assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
    }

    private void restartAfterLastWriter(final DownloadMission mission) throws Exception {
        final CountDownLatch restarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final byte[] before = Files.readAllBytes(target.toPath());
        final long previousDone = mission.done;
        final Thread previous = new Thread(() -> {
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (final InterruptedException ignored) {
                    // Model a writer still finishing an I/O operation after interruption.
                }
            }
            try {
                Files.write(target.toPath(), new byte[40],
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (final IOException error) {
                failure.set(error);
            }
        });
        mission.threads = new Thread[]{previous};
        // JVM byte-I/O fixture, not Android AtomicFile checkpoint evidence.
        mission.metadata = null;
        watchTerminal(mission, restarted);
        previous.start();
        try (var logging = mockStatic(Log.class)) {
            try {
                mission.start();
                assertFalse("Old writer owns the state until it stops", mission.running);
                assertEquals(previousDone, mission.done);
                assertArrayEquals(before, Files.readAllBytes(target.toPath()));
            } finally {
                release.countDown();
            }
            assertTrue(restarted.await(15, TimeUnit.SECONDS));
            previous.join(5000);
            for (final Thread worker : mission.threads) {
                worker.join(5000);
            }
        }
        assertNull(failure.get());
        assertFalse(previous.isAlive());
    }

    @Test
    public void laterValidatorCannotBindAfterUnvalidatedWorkerWasAdmitted() throws Exception {
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod()) || start == 0) {
                sendRange(exchange, start, end, start, end,
                        "bytes " + start + "-" + end + "/" + content.length,
                        end - start + 1);
            } else {
                sendVersionedRange(exchange, start, end, content, "\"v2\"");
            }
        };
        final CountDownLatch terminal = new CountDownLatch(1);
        final DownloadMission mission = mission(terminal);
        mission.threadCount = 2;
        runAndAwait(mission, terminal);
        assertFailed(mission);
    }

    @Test
    public void terminalReconnectPublishesPartialBlockBeforeWarmStart() throws Exception {
        final RetryMission mission = retryMission(true, false);
        startAtReconnect(mission);
        assertRetryOwnsPartialBlock(mission);
        mission.releaseReconnect.countDown();
        awaitRetry(mission.terminal, "terminal reconnect failure");
        mission.joinCreatedThreads();
        assertEquals(503, mission.errCode);
        assertPublishedPartialBlock(mission);

        final CountDownLatch resumed = new CountDownLatch(1);
        watchTerminal(mission, resumed);
        runAndAwait(mission, resumed); // The same mission's real warm Start, not a new worker.
        mission.joinCreatedThreads();
        assertCompletedRetry(mission, 2);
    }

    @Test
    public void successfulBodyRetryRetainsOwnershipAcrossReconnect() throws Exception {
        final RetryMission mission = retryMission(false, false);
        startAtReconnect(mission);
        assertRetryOwnsPartialBlock(mission);
        mission.releaseReconnect.countDown();
        awaitRetry(mission.terminal, "successful body retry");
        mission.joinCreatedThreads();
        assertCompletedRetry(mission, 1);
    }

    @Test
    public void pauseDuringReconnectJoinsOldWorkerBeforeWarmStart() throws Exception {
        final RetryMission mission = retryMission(false, true);
        startAtReconnect(mission);
        assertRetryOwnsPartialBlock(mission);
        try (var logging = mockStatic(Log.class)) {
            mission.pause();
        }
        awaitRetry(mission.ownerCleanup, "paused owner published its partial block");
        assertPublishedPartialBlock(mission);
        final int oldWorkers = mission.workerCreations.get();
        final int oldRequests = mediaRequests.get();
        assertTrue("old owner is deliberately held after cleanup", mission.prefixOwner.isAlive());

        try (var logging = mockStatic(Log.class)) {
            mission.start();
        }
        assertFalse("Start must join the old owner before activating a new epoch",
                mission.running);
        assertEquals(oldWorkers, mission.workerCreations.get());
        assertEquals(oldRequests, mediaRequests.get());
        assertPublishedPartialBlock(mission);

        mission.releaseExit.countDown();
        mission.releaseReconnect.countDown();
        awaitRetry(mission.terminal, "warm Start after paused owner exits");
        mission.joinCreatedThreads();
        assertCompletedRetry(mission, 2);
    }

    @Test
    public void terminalCheckpointPrecedesCleanupButLaterSaveIncludesPublishedPrefix()
            throws Exception {
        final RetryMission mission = retryMission(true, false);
        startAtReconnect(mission);
        // No asynchronous progress save is pending: attach metadata only once the workers
        // are stationary, to isolate the real terminal save through the JVM adapter.
        mission.metadata = folder.newFile("retry.meta");
        mission.releaseReconnect.countDown();
        awaitRetry(mission.terminal, "terminal checkpoint");
        mission.joinCreatedThreads();
        assertPublishedPartialBlock(mission);
        assertTrue("capture the actual save performed by the failing owner",
                mission.terminalCheckpoint != null);
        final DownloadMission beforeCleanup = readRetryCheckpoint(mission.terminalCheckpoint);
        assertArrayEquals(new int[]{0, -1, -1}, beforeCleanup.blocks);
        assertEquals(content.length - DownloadMission.BLOCK_SIZE + 187L, beforeCleanup.done);
        assertEquals(503, beforeCleanup.errCode);

        assertTrue("explicit later checkpoint succeeds", mission.writeThisToFile());
        final DownloadMission afterCleanup = readRetryCheckpoint(
                Files.readAllBytes(mission.metadata.toPath()));
        assertArrayEquals(new int[]{187, -1, -1}, afterCleanup.blocks);
        assertEquals(beforeCleanup.done, afterCleanup.done);
    }

    @Test
    public void releasedBlockCannotOverwriteCompletionOrReleaseItsNextOwner() throws Exception {
        final DownloadMission mission = ownershipMission();
        final DownloadMission.Block first = mission.acquireBlock();
        mission.releaseBlock(first, 187);
        final DownloadMission.Block next = mission.acquireBlock();
        assertEquals(187, next.done);
        mission.releaseBlock(first, -1);
        assertEquals(187, mission.blocks[0]);
        assertTrue(acquiredBlocks(mission)[0]);
        mission.releaseBlock(next, -1);
        mission.releaseBlock(next, 187);
        assertEquals(-1, mission.blocks[0]);
        assertFalse(acquiredBlocks(mission)[0]);
        assertNull("a completed block cannot be acquired again", mission.acquireBlock());
    }

    @Test
    public void staleBlockPublicationCannotTouchResetOrReplacementOwnership() throws Exception {
        final DownloadMission mission = ownershipMission();
        final DownloadMission.Block stale = mission.acquireBlock();
        try (var logging = mockStatic(Log.class)) {
            mission.resetState(false, false, DownloadMission.ERROR_NOTHING);
        }
        mission.releaseBlock(stale, 187);
        assertNull(mission.blocks);
        replaceOwnership(mission, 23);
        final DownloadMission.Block replacement = mission.acquireBlock();
        mission.releaseBlock(stale, -1);
        assertEquals(23, mission.blocks[0]);
        assertTrue(acquiredBlocks(mission)[0]);

        // A new acquisition epoch must also reject a token for the same blocks array.
        replaceAcquisitions(mission);
        final DownloadMission.Block restarted = mission.acquireBlock();
        mission.releaseBlock(replacement, -1);
        assertEquals(23, mission.blocks[0]);
        assertTrue(acquiredBlocks(mission)[0]);
        mission.releaseBlock(restarted, -1);
        assertEquals(-1, mission.blocks[0]);
        assertFalse(acquiredBlocks(mission)[0]);
    }

    private DownloadMission ownershipMission() throws Exception {
        final DownloadMission mission = mission(new CountDownLatch(1));
        mission.metadata = null;
        replaceOwnership(mission, 0);
        return mission;
    }

    private static void replaceOwnership(final DownloadMission mission, final int done)
            throws Exception {
        mission.blocks = new int[]{done};
        replaceAcquisitions(mission);
    }

    private static void replaceAcquisitions(final DownloadMission mission) throws Exception {
        final Field field = DownloadMission.class.getDeclaredField("blockAcquired");
        field.setAccessible(true);
        field.set(mission, new boolean[1]);
    }

    private static boolean[] acquiredBlocks(final DownloadMission mission) throws Exception {
        final Field field = DownloadMission.class.getDeclaredField("blockAcquired");
        field.setAccessible(true);
        return (boolean[]) field.get(mission);
    }

    private RetryMission retryMission(final boolean failReconnect, final boolean holdExit)
            throws Exception {
        final RetryMission mission = new RetryMission(new String[]{
                "http://" + server.getInetAddress().getHostAddress() + ":"
                        + server.getLocalPort() + "/media"}, holdExit);
        retryMissions.add(mission);
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.existsAsFile()).thenReturn(true);
        when(storage.canWrite()).thenReturn(true);
        when(storage.getStream()).thenAnswer(ignored -> new FileStream(target));
        mission.storage = storage;
        mission.threadCount = 2;
        mission.maxRetry = 1;
        mission.metadata = null;
        watchTerminal(mission, mission.terminal);
        rule = (exchange, start, end) -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            } else if (start == 0) {
                if (mission.zeroRequests.incrementAndGet() == 1) {
                    exchange.getResponseHeaders().set("ETag", "\"v1\"");
                    sendRange(exchange, start, end, start, start + 186,
                            "bytes " + start + "-" + end + "/" + content.length,
                            end - start + 1);
                } else {
                    sendVersionedRange(exchange, start, end, content, "\"v1\"");
                }
            } else if (start == 187) {
                mission.prefixRanges.add(exchange.getRequestHeaders().getFirst("Range"));
                if (mission.prefixRanges.size() == 1) {
                    awaitRetry(mission.otherExited, "other worker exits before reconnect response");
                    mission.reconnectSeen.countDown();
                    awaitRetry(mission.releaseReconnect, "release reconnect response");
                    if (failReconnect) {
                        exchange.sendResponseHeaders(503, 0);
                        return;
                    }
                }
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            } else {
                sendVersionedRange(exchange, start, end, content, "\"v1\"");
            }
        };
        return mission;
    }

    private static void startAtReconnect(final RetryMission mission) throws Exception {
        try (var logging = mockStatic(Log.class)) {
            mission.start();
        }
        awaitRetry(mission.reconnectSeen, "187-byte body retry reaches gated reconnect");
        mission.otherWorker.join(5000);
        assertFalse("terminal response is held until the other worker actually exits",
                mission.otherWorker.isAlive());
    }

    private void assertRetryOwnsPartialBlock(final RetryMission mission) throws Exception {
        assertEquals(content.length - DownloadMission.BLOCK_SIZE + 187L, mission.done);
        assertArrayEquals(new int[]{0, -1, -1}, mission.blocks);
        assertTrue(acquiredBlocks(mission)[0]);
        assertNull("another worker cannot acquire the retrying owner's block",
                mission.acquireBlock());
        assertArrayEquals(Arrays.copyOf(content, 187),
                Arrays.copyOf(Files.readAllBytes(target.toPath()), 187));
    }

    private void assertPublishedPartialBlock(final RetryMission mission) throws Exception {
        assertArrayEquals(new int[]{187, -1, -1}, mission.blocks);
        assertArrayEquals(new boolean[]{false, false, false}, acquiredBlocks(mission));
        assertEquals(content.length - DownloadMission.BLOCK_SIZE + 187L, mission.done);
    }

    private void assertCompletedRetry(final RetryMission mission, final int prefixRequests)
            throws Exception {
        assertCompleted(mission);
        assertEquals("the already-counted prefix must never replay from zero",
                1, mission.zeroRequests.get());
        assertEquals(prefixRequests, mission.prefixRanges.size());
        for (final String range : mission.prefixRanges) {
            assertEquals("bytes=187-524287", range);
        }
        assertArrayEquals("terminal cleanup must not overwrite completed blocks",
                new int[]{-1, -1, -1}, mission.blocks);
        assertArrayEquals(new boolean[]{false, false, false}, acquiredBlocks(mission));
    }

    private static DownloadMission readRetryCheckpoint(final byte[] snapshot) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(snapshot))) {
            return (DownloadMission) input.readObject();
        }
    }

    private static void awaitRetry(final CountDownLatch latch, final String description) {
        try {
            assertTrue("timed out: " + description, latch.await(15, TimeUnit.SECONDS));
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description, error);
        }
    }

    /** Scheduling and Android adapters only; all requests, byte writes and workers are real. */
    private static final class RetryMission extends DownloadMission {
        private static final long serialVersionUID = 1L;
        private final transient CountDownLatch terminal = new CountDownLatch(1);
        private final transient CountDownLatch reconnectSeen = new CountDownLatch(1);
        private final transient CountDownLatch releaseReconnect = new CountDownLatch(1);
        private final transient CountDownLatch otherExited = new CountDownLatch(1);
        private final transient CountDownLatch ownerCleanup = new CountDownLatch(1);
        private final transient CountDownLatch releaseExit = new CountDownLatch(1);
        private final transient AtomicInteger workerCreations = new AtomicInteger();
        private final transient AtomicInteger zeroRequests = new AtomicInteger();
        private final transient List<String> prefixRanges =
                Collections.synchronizedList(new ArrayList<>());
        private final transient List<Thread> created =
                Collections.synchronizedList(new ArrayList<>());
        private final transient AtomicReference<Throwable> failure = new AtomicReference<>();
        private final transient boolean holdExit;
        private transient volatile Thread prefixOwner;
        private transient volatile Thread otherWorker;
        private transient volatile byte[] terminalCheckpoint;

        RetryMission(final String[] urls, final boolean holdExit) {
            super(urls, null, 'v', null);
            this.holdExit = holdExit;
        }

        @Override
        Block acquireBlock() {
            final Block block = super.acquireBlock();
            if (block != null && block.position == 0) {
                prefixOwner = Thread.currentThread();
            }
            return block;
        }

        @Override
        Thread createInitializer() {
            final Thread initializer = new DownloadInitializer(this) {
                @Override
                public void run() {
                    try (var logging = mockStatic(Log.class)) {
                        super.run();
                    } catch (final Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }
            };
            created.add(initializer);
            return initializer;
        }

        @Override
        Thread createDownloadWorker(final int index) {
            workerCreations.incrementAndGet();
            final Thread worker = new DownloadRunnable(this, index) {
                @Override
                public void run() {
                    try (var logging = mockStatic(Log.class)) {
                        super.run();
                    } catch (final Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        if (Thread.currentThread() != prefixOwner) {
                            otherWorker = Thread.currentThread();
                            otherExited.countDown();
                        } else {
                            ownerCleanup.countDown();
                            if (holdExit && releaseExit.getCount() != 0) {
                                // Interruption does not finish the simulated outstanding I/O.
                                boolean interrupted = false;
                                while (releaseExit.getCount() != 0) {
                                    try {
                                        releaseExit.await();
                                    } catch (final InterruptedException ignored) {
                                        interrupted = true;
                                    }
                                }
                                if (interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
                }
            };
            created.add(worker);
            return worker;
        }

        @Override
        boolean writeThisToFile() {
            final Thread writer = Thread.currentThread();
            // Production LOCK and real ObjectOutputStream are retained. Only Android's
            // AtomicFile boundary and the nonserializable storage mock are adapted.
            try (var utility = mockStatic(Utility.class)) {
                utility.when(() -> Utility.writeToFile(any(File.class), any()))
                        .thenAnswer(call -> {
                            final File file = call.getArgument(0);
                            try (ObjectOutputStream output = new ObjectOutputStream(
                                    Files.newOutputStream(file.toPath())) {
                                {
                                    enableReplaceObject(true);
                                }

                                @Override
                                protected Object replaceObject(final Object object) {
                                    return object instanceof StoredFileHelper ? null : object;
                                }
                            }) {
                                output.writeObject(call.getArgument(1));
                            }
                            if (writer == prefixOwner && errCode == 503
                                    && terminalCheckpoint == null) {
                                terminalCheckpoint = Files.readAllBytes(file.toPath());
                            }
                            return true;
                        });
                return super.writeThisToFile();
            } catch (final Throwable error) {
                failure.compareAndSet(null, error);
                return false;
            }
        }

        void joinCreatedThreads() throws Exception {
            final Thread[] snapshot;
            synchronized (created) {
                snapshot = created.toArray(new Thread[0]);
            }
            for (final Thread thread : snapshot) {
                if (thread != Thread.currentThread()) {
                    thread.join(5000);
                    assertFalse("fixture thread leaked: " + thread.getName(), thread.isAlive());
                }
            }
            if (failure.get() != null) {
                throw new AssertionError("background worker/checkpoint failed", failure.get());
            }
        }
    }

}
