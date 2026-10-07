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
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    private ResponseRule rule;

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
    public void tearDown() throws IOException {
        server.close();
        executor.shutdownNow();
    }

    private void handle(final LocalExchange exchange) throws IOException {
        try {
            final String header = exchange.getRequestHeaders().getFirst("Range");
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

        LocalExchange(final Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(5000);
            final BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            method = reader.readLine().split(" ", 2)[0];
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

}
