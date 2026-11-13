package us.shandian.giga.preprocessing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.annotation.NonNull;

import com.github.evermindzz.hlsdownloader.HlsMediaProcessor;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.preprocessing.PvcHlsPreProcessor.FetchWithDownloaderImpl;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class PvcHlsPreProcessorTest {
    @Test
    public void audioRequiresEndedMediaAndPreservesSupportedByteWindows() throws Exception {
        final String media = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n"
                + "#EXT-X-BYTERANGE:100@0\nshared.aac\n";
        final PvcHlsPreProcessor live = new PvcHlsPreProcessor(clientAnswering(200,
                media.getBytes(StandardCharsets.UTF_8)));
        assertThrows(RuntimeException.class, () -> live.prepare("https://media.test/a.m3u8", true));
        final DownloadMission audio = new DownloadMission(new String[]{"https://media.test/a.m3u8"},
                null, 'a', null);
        audio.recoveryInfo = new MissionRecoveryInfo[1];
        final PvcHlsPreProcessor ended = new PvcHlsPreProcessor(clientAnswering(200,
                (media + "#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8)));
        ended.modifyMission(audio);
        final HlsDownloadPlan plan = (HlsDownloadPlan) audio.pvcArbitraryData;
        assertEquals(1, audio.urls.length);
        assertEquals(100, plan.getSegments().get(0).getMediaWindow().getLength());
        final String drm = media.replace("#EXTINF",
                "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF")
                + "#EXT-X-ENDLIST\n";
        assertThrows(RuntimeException.class, () -> new PvcHlsPreProcessor(clientAnswering(200,
                drm.getBytes(StandardCharsets.UTF_8))).prepare("https://media.test/a.m3u8", true));
    }

    // An AES-128 key is 16 arbitrary bytes. These are not valid UTF-8.
    private static final byte[] KEY = {(byte) 0xff, (byte) 0xfe, (byte) 0x80, 0x00, 0x7f,
            (byte) 0xc3, 0x28, (byte) 0xa0, (byte) 0xa1, (byte) 0xe2, (byte) 0x82, 0x28,
            (byte) 0xf0, (byte) 0x90, 0x0a, 0x0d};

    private final AtomicBoolean bodyClosed = new AtomicBoolean();
    private final AtomicReference<Request> sent = new AtomicReference<>();

    // A client that answers every request itself, without any network.
    private OkHttpClient clientAnswering(final int status, final byte[] body) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            sent.set(chain.request());
            return new Response.Builder().request(chain.request())
                    .protocol(Protocol.HTTP_1_1).code(status).message("")
                    .body(new ClosingBody(body)).build();
        }).build();
    }

    private final class ClosingBody extends ResponseBody {
        private final byte[] bytes;
        private final BufferedSource source;

        private ClosingBody(final byte[] bytes) {
            this.bytes = bytes;
            source = Okio.buffer(new ForwardingSource(new Buffer().write(bytes)) {
                @Override
                public void close() throws IOException {
                    bodyClosed.set(true);
                    super.close();
                }
            });
        }

        @Override
        public MediaType contentType() {
            return null;
        }

        @Override
        public long contentLength() {
            return bytes.length;
        }

        @NonNull
        @Override
        public BufferedSource source() {
            return source;
        }
    }

    @Test
    public void aBinaryKeyArrivesUnchanged() throws IOException {
        final FetchWithDownloaderImpl fetcher =
                new FetchWithDownloaderImpl(clientAnswering(200, KEY));
        try (InputStream stream =
                     fetcher.fetchContent(URI.create("https://example.test/hls/key.bin"))) {
            assertArrayEquals(KEY, stream.readAllBytes());
        }
        assertTrue(bodyClosed.get());
    }

    @Test
    public void anErrorPageIsNotAPlaylist() {
        for (final int status : new int[]{401, 403, 404, 429, 500}) {
            bodyClosed.set(false);
            final FetchWithDownloaderImpl fetcher = new FetchWithDownloaderImpl(
                    clientAnswering(status, "try again later".getBytes()));
            final IOException error = assertThrows(IOException.class, () ->
                    fetcher.fetchContent(URI.create("https://example.test/hls/master.m3u8")));
            assertTrue(error.getMessage().contains(String.valueOf(status)));
            assertTrue(bodyClosed.get());
        }
    }

    @Test
    public void odyseeHostsGetTheirReferrer() throws IOException {
        new FetchWithDownloaderImpl(clientAnswering(200, new byte[0])).fetchContent(
                URI.create("https://player.odycdn.com/v6/streams/abc/def/master.m3u8")).close();
        assertEquals("https://odysee.com/", sent.get().header("Referer"));

        new FetchWithDownloaderImpl(clientAnswering(200, new byte[0])).fetchContent(
                URI.create("https://example.test/hls/master.m3u8")).close();
        assertEquals(null, sent.get().header("Referer"));
    }

    private static DownloadMission mission() {
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://origin.example.test/start.m3u8"}, null, 'v', null);
        mission.recoveryInfo = new MissionRecoveryInfo[]{
                new MissionRecoveryInfo(null, null, false, 0, 'v', null)};
        return mission;
    }

    private void prepare(final DownloadMission mission, final String manifest) {
        new PvcHlsPreProcessor(clientAnswering(
                200, manifest.getBytes(StandardCharsets.UTF_8))).modifyMission(mission);
    }

    private static String playlist(final String entries) {
        return "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n"
                + entries + "#EXT-X-ENDLIST\n";
    }

    @Test
    public void standardAndImplicitRangesDeduplicateFullSignedResources() {
        final DownloadMission mission = mission();
        final MissionRecoveryInfo recovery = mission.recoveryInfo[0];
        prepare(mission, playlist("#EXTINF:2,\n#EXT-X-BYTERANGE:4@2\nshared.ts?token=A%2FB\n"
                + "#EXTINF:2,\n#EXT-X-BYTERANGE:3\nshared.ts?token=A%2FB\n"
                + "#EXTINF:2,\n#EXT-X-BYTERANGE:2@1\nother.ts?token=C\n"));

        assertArrayEquals(new String[]{
                "https://origin.example.test/shared.ts?token=A%2FB",
                "https://origin.example.test/other.ts?token=C"}, mission.urls);
        assertArrayEquals(new long[]{0, 0}, mission.offsets);
        assertEquals(2, mission.recoveryInfo.length);
        assertSame(recovery, mission.recoveryInfo[0]);
        assertSame(recovery, mission.recoveryInfo[1]);
        final List<HlsDownloadPlan.Segment> segments =
                ((HlsDownloadPlan) mission.pvcArbitraryData).getSegments();
        assertWindow(segments.get(0).getMediaWindow(), 0, 2, 4);
        assertWindow(segments.get(1).getMediaWindow(), 0, 6, 3);
        assertWindow(segments.get(2).getMediaWindow(), 1, 1, 2);
        assertNull(segments.get(0).getInitialization());
        assertTrue(bodyClosed.get());
    }

    @Test
    public void differentSignaturesAreDifferentPhysicalResources() {
        final DownloadMission mission = mission();
        prepare(mission, playlist("#EXTINF:2,\nshared.ts?signature=one\n"
                + "#EXTINF:2,\nshared.ts?signature=two\n"));
        assertEquals(2, mission.urls.length);
        assertWindow(((HlsDownloadPlan) mission.pvcArbitraryData).getSegments().get(1)
                .getMediaWindow(), 1, 0, -1);
    }

    @Test
    public void redirectedPlaylistResolvesMapsMediaAndBinaryKeysFromEffectiveUrl()
            throws Exception {
        final List<String> requests = new ArrayList<>();
        final String manifest = playlist("#EXT-X-KEY:METHOD=AES-128,URI=\"keys/key.bin\","
                + "IV=0x12c\n#EXT-X-MAP:URI=\"shared.mp4?sig=A%2FB\",BYTERANGE=\"4@2\"\n"
                + "#EXTINF:2,\n#EXT-X-BYTERANGE:8@6\nshared.mp4?sig=A%2FB\n");
        final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            final Request request = chain.request();
            requests.add(request.url().toString());
            if (request.url().encodedPath().endsWith("/key.bin")) {
                assertTrue("Playlist body must close before fetching its key", bodyClosed.get());
                return response(request, KEY);
            }
            final Request effective = request.newBuilder().url(
                    "https://cdn.example.test/redirected/list.m3u8?token=playlist").build();
            return new Response.Builder().request(effective).protocol(Protocol.HTTP_1_1)
                    .code(200).message("").body(new ClosingBody(
                            manifest.getBytes(StandardCharsets.UTF_8))).build();
        }).build();
        final DownloadMission mission = mission();
        new PvcHlsPreProcessor(client).modifyMission(mission);

        assertEquals(Arrays.asList("https://origin.example.test/start.m3u8",
                "https://cdn.example.test/redirected/keys/key.bin"), requests);
        assertArrayEquals(new String[]{
                "https://cdn.example.test/redirected/shared.mp4?sig=A%2FB"}, mission.urls);
        final HlsDownloadPlan restored = roundTrip((HlsDownloadPlan) mission.pvcArbitraryData);
        final HlsDownloadPlan.Segment segment = restored.getSegments().get(0);
        final HlsDownloadPlan.Initialization initialization = segment.getInitialization();
        assertWindow(initialization.getWindow(), 0, 2, 4);
        assertWindow(segment.getMediaWindow(), 0, 6, 8);
        assertEquals(URI.create("https://cdn.example.test/redirected/keys/key.bin"),
                initialization.getMedia().getEncryptionInfo().getUri());
        assertArrayEquals(KEY, initialization.getMedia().getEncryptionInfo().getKey());
        assertArrayEquals(KEY, segment.getMedia().getEncryptionInfo().getKey());
        assertEquals("0000000000000000000000000000012c",
                segment.getMedia().getEncryptionInfo().getIv());
    }

    @Test
    public void sequence300DecryptsWithTheRealDependencyAfterSerialization() throws Exception {
        final byte[] clear = "sequence 300 is not low-byte sequence 44"
                .getBytes(StandardCharsets.UTF_8);
        final byte[] iv = new byte[16];
        iv[14] = 1;
        iv[15] = 44;
        final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(iv));
        final byte[] encrypted = cipher.doFinal(clear);
        final String manifest = playlist("#EXT-X-MEDIA-SEQUENCE:300\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:2,\nmedia.ts\n");
        final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                response(chain.request(), chain.request().url().encodedPath().endsWith("key.bin")
                        ? KEY : manifest.getBytes(StandardCharsets.UTF_8))).build();
        final DownloadMission mission = mission();
        new PvcHlsPreProcessor(client).modifyMission(mission);
        final HlsParser.Segment media = roundTrip((HlsDownloadPlan) mission.pvcArbitraryData)
                .getSegments().get(0).getMedia();
        final HlsMediaProcessor processor = new HlsMediaProcessor(
                null, null, null, null, null, 1, null, null, null, null, false);
        try (InputStream input = processor.processSegment(media, 0,
                (uri, index) -> new ByteArrayInputStream(encrypted))) {
            assertArrayEquals(clear, input.readAllBytes());
        }
    }

    @Test
    public void unsupportedEncryptionFailsBeforeAnyMissionMutationOrKeyFetch() {
        for (final String encryption : Arrays.asList(
                "METHOD=SAMPLE-AES,URI=\"key.bin\"",
                "METHOD=SAMPLE-AES-CTR,URI=\"key.bin\"",
                "METHOD=AES-128,URI=\"key.bin\",KEYFORMAT=\"unsupported.example\"")) {
            final DownloadMission mission = mission();
            final AtomicBoolean keyRequested = new AtomicBoolean();
            final String manifest = playlist("#EXT-X-KEY:" + encryption
                    + "\n#EXTINF:2,\nmedia.ts\n");
            final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
                if (chain.request().url().encodedPath().endsWith("key.bin")) {
                    keyRequested.set(true);
                }
                return response(chain.request(), manifest.getBytes(StandardCharsets.UTF_8));
            }).build();
            assertUnchangedAfterFailure(mission, new PvcHlsPreProcessor(client));
            assertTrue("Unsupported encryption must not fetch keys", !keyRequested.get());
        }
    }

    @Test
    public void badKeyLengthsAndMasterSelectionFailBeforeMissionMutation() {
        final String encrypted = playlist("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "#EXTINF:2,\nmedia.ts\n");
        for (final int length : new int[]{15, 17}) {
            final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
                    response(chain.request(),
                            chain.request().url().encodedPath().endsWith("key.bin")
                                    ? new byte[length]
                                    : encrypted.getBytes(StandardCharsets.UTF_8))).build();
            assertUnchangedAfterFailure(mission(), new PvcHlsPreProcessor(client));
        }
        final String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1234\nmedia.m3u8\n";
        assertUnchangedAfterFailure(mission(), new PvcHlsPreProcessor(
                clientAnswering(200, master.getBytes(StandardCharsets.UTF_8))));
        assertTrue(bodyClosed.get());
    }

    @Test
    public void legacyPlansPreserveDuplicatePhysicalIdentityAndRejectInvalidEntries()
            throws Exception {
        final HlsParser.Segment media = new HlsParser.Segment(
                URI.create("https://cdn.example.test/repeated.ts"), 2, "", null);
        final HlsDownloadPlan restored = roundTrip(
                HlsDownloadPlan.fromLegacy(Arrays.asList(media, media)));
        assertWindow(restored.getSegments().get(0).getMediaWindow(), 0, 0, -1);
        assertWindow(restored.getSegments().get(1).getMediaWindow(), 1, 0, -1);
        assertSame(restored.getSegments().get(0).getMedia(),
                restored.getSegments().get(1).getMedia());
        assertThrows(UnsupportedOperationException.class, () -> restored.getSegments().clear());
        assertThrows(IOException.class, () -> HlsDownloadPlan.fromLegacy(
                Arrays.asList(media, "not a segment")));
        assertThrows(IOException.class, () -> HlsDownloadPlan.fromLegacy(
                Collections.singletonList(null)));
        assertThrows(IOException.class, () -> HlsDownloadPlan.fromLegacy(
                Collections.singletonList(new HlsParser.Segment(null, 2, "", null))));
        assertThrows(IOException.class, () -> HlsDownloadPlan.fromLegacy(Collections.emptyList()));
    }

    private static void assertUnchangedAfterFailure(final DownloadMission mission,
                                                   final PvcHlsPreProcessor preprocessor) {
        final String[] urls = mission.urls;
        final long[] offsets = mission.offsets;
        final MissionRecoveryInfo[] recovery = mission.recoveryInfo;
        final Object original = new Object();
        mission.pvcArbitraryData = original;
        mission.offsets[0] = 123;
        final RuntimeException error = assertThrows(RuntimeException.class,
                () -> preprocessor.modifyMission(mission));
        assertTrue(error.getCause() instanceof IOException);
        assertSame(urls, mission.urls);
        assertSame(offsets, mission.offsets);
        assertSame(recovery, mission.recoveryInfo);
        assertSame(original, mission.pvcArbitraryData);
        assertEquals(123, mission.offsets[0]);
    }

    private static Response response(final Request request, final byte[] bytes) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("").body(ResponseBody.create(
                        bytes, MediaType.get("application/octet-stream"))).build();
    }

    private static void assertWindow(final HlsDownloadPlan.Window window, final int resource,
                                     final long offset, final long length) {
        assertEquals(resource, window.getResourceIndex());
        assertEquals(offset, window.getOffset());
        assertEquals(length, window.getLength());
    }

    private static HlsDownloadPlan roundTrip(final HlsDownloadPlan plan) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(plan);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (HlsDownloadPlan) input.readObject();
        }
    }
    @Test
    public void cancelledPlaylistCallStopsAndFreshPreparationCanRetry() throws Exception {
        assertCancelledPreparationCanRetry("/list.m3u8");
    }

    @Test
    public void cancelledKeyCallStopsAndFreshPreparationCanRetry() throws Exception {
        assertCancelledPreparationCanRetry("/key");
    }

    private void assertCancelledPreparationCanRetry(final String blockedPath) throws Exception {
        final var server = new java.net.ServerSocket(0, 8,
                java.net.InetAddress.getByName("127.0.0.1"));
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var first = new AtomicBoolean(true);
        final String playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n"
                + "#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n";
        final Thread responder = new Thread(() -> {
            while (!server.isClosed()) {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    final var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    final String request = reader.readLine();
                    if (request == null) {
                        continue;
                    }
                    final String path = request.split(" ")[1];
                    String header;
                    do {
                        header = reader.readLine();
                    } while (header != null && !header.isEmpty());
                    if (blockedPath.equals(path) && first.getAndSet(false)) {
                        entered.countDown();
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    final byte[] body = "/key".equals(path)
                            ? KEY : playlist.getBytes(StandardCharsets.UTF_8);
                    final var output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(body);
                    output.flush();
                } catch (IOException error) {
                    // Cancellation closes the first client socket before its reply.
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "HlsCancellationPeer");
        responder.start();
        final var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        final OkHttpClient client = new OkHttpClient.Builder().build();
        final String url = "http://127.0.0.1:" + server.getLocalPort() + "/list.m3u8";
        try {
            final PvcHlsPreProcessor processor = new PvcHlsPreProcessor(client);
            final var attempt = executor.submit(() -> processor.prepare(url));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            processor.cancel();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> attempt.get(5, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            final DownloadMission mission = new DownloadMission(new String[]{url}, null, 'v', null);
            mission.recoveryInfo = new us.shandian.giga.get.MissionRecoveryInfo[]{null};
            new PvcHlsPreProcessor(client).prepare(url).applyTo(mission);
            assertEquals(1, mission.urls.length);
            assertTrue(mission.urls[0].endsWith("/segment.ts"));
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(mission);
            }
            try (ObjectInputStream input = new ObjectInputStream(
                    new ByteArrayInputStream(bytes.toByteArray()))) {
                final DownloadMission restored = (DownloadMission) input.readObject();
                assertArrayEquals(mission.urls, restored.urls);
                org.junit.Assert.assertFalse(restored.hlsPreparationPending);
                assertTrue(restored.pvcArbitraryData instanceof HlsDownloadPlan);
            }
        } finally {
            release.countDown();
            server.close();
            responder.join(5000);
            executor.shutdownNow();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
    }

}
