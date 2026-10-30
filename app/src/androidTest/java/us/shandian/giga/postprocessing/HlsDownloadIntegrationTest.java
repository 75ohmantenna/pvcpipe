/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package us.shandian.giga.postprocessing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.video.PlaceholderSurface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.preprocessing.HlsDownloadPlan;
import us.shandian.giga.preprocessing.PvcHlsPreProcessor;
import us.shandian.giga.service.DownloadManagerService;

/**
 * Real HTTP -> preprocessing -> serialized mission -> workers -> RunFFmpeg -> decoded local MP4.
 * Uses a unique target-cache directory, without the download manager, database or user storage.
 * Run with the ordinary AndroidJUnitRunner on a disposable installation with the native converter.
 */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class HlsDownloadIntegrationTest {
    private static final long WAIT_SECONDS = 60;
    private static final String ASSET_DIRECTORY = "media3-engine/";

    private Context context;
    private File directory;
    private Loopback server;
    private DownloadMission mission;
    private ExoPlayer player;
    private PlaceholderSurface surface;
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final AtomicInteger terminalMessage = new AtomicInteger(-1);
    private final AtomicReference<Throwable> engineError = new AtomicReference<>();
    private final AtomicInteger videoFrames = new AtomicInteger();
    private final AtomicInteger firstFrames = new AtomicInteger();
    private final AtomicInteger audioAdvances = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(context.getCacheDir().toPath(),
                "hls-download-").toFile();
    }

    @After
    public void tearDown() throws Exception {
        if (player != null) {
            main(() -> {
                player.release();
                return null;
            });
        }
        if (surface != null) {
            surface.release();
        }
        if (server != null) {
            server.close();
        }
        if (mission != null) {
            mission.pause();
            mission.running = false;
            stop(mission.init);
            for (final Thread worker : mission.threads) {
                stop(worker);
            }
        }
        if (directory != null) {
            delete(directory);
        }
        if (server != null) {
            server.assertHealthy();
        }
    }

    @Test
    public void transportStreamByteRangesDownloadOnceAndReopenAsDecodedMp4() throws Exception {
        completedDownload("kick-ts", ".ts", false);
    }

    @Test
    public void fragmentedMp4ByteRangesRetainInitializationAndDecodeAfterRestore()
            throws Exception {
        completedDownload("kick-fmp4", ".mp4", true);
    }

    @Test
    public void actualConverterNonzeroExitFailsMissionInsteadOfReportingPartialSuccess()
            throws Exception {
        server = new Loopback(("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:2,\ninvalid.ts\n#EXT-X-ENDLIST\n")
                .getBytes(StandardCharsets.UTF_8), "invalid.ts",
                "This is deliberately not a transport stream or any other media format.\n"
                        .getBytes(StandardCharsets.UTF_8));
        prepareRestoredMission();
        startAndAwaitTerminal();
        assertEquals(DownloadManagerService.MESSAGE_ERROR, terminalMessage.get());
        assertEquals(DownloadMission.ERROR_POSTPROCESSING, mission.errCode);
        assertFalse("Failed conversion must not finish the mission", mission.isFinished());
        assertEquals(0, mission.psState);
        assertNotNull("The real conversion failure must remain observable", mission.errObject);
        boolean nonzeroExit = false;
        for (Throwable cause = mission.errObject; cause != null; cause = cause.getCause()) {
            final String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            if (cause instanceof IOException && message.contains("ffmpeg")
                    && message.contains("exit")) {
                nonzeroExit = true;
            }
        }
        assertTrue("Expected the actual FFmpeg exit failure, not an earlier fixture failure: "
                + mission.errObject, nonzeroExit);
        assertEquals(1, server.mediaGets.get());
        assertTrue("Conversion must have consumed actual downloaded bytes",
                server.mediaBytes.get() > 0);
    }

    @Test
    public void workerHttpFailureRemainsAnErrorAndNeverStartsConversion() throws Exception {
        serveAsset("kick-ts", ".ts");
        server.rejectMediaGets = true;
        prepareRestoredMission();
        startAndAwaitTerminal();
        assertEquals(DownloadManagerService.MESSAGE_ERROR, terminalMessage.get());
        assertEquals(503, mission.errCode);
        assertEquals(0, mission.psState);
        assertFalse(mission.isFinished());
        assertEquals("No retry or fake success is permitted by this fixture", 1,
                server.mediaGets.get());
        assertEquals(0, server.mediaBytes.get());
    }

    private void completedDownload(final String asset, final String extension,
                                   final boolean initialization) throws Exception {
        serveAsset(asset, extension);
        prepareRestoredMission();
        final HlsDownloadPlan plan = (HlsDownloadPlan) mission.pvcArbitraryData;
        assertEquals("Four logical windows must share one physical object", 4,
                plan.getSegments().size());
        assertEquals(1, mission.urls.length);
        for (final HlsDownloadPlan.Segment segment : plan.getSegments()) {
            assertEquals(0, segment.getMediaWindow().getResourceIndex());
            assertTrue(segment.getMediaWindow().getLength() > 0);
            assertEquals(initialization, segment.getInitialization() != null);
            if (initialization) {
                assertEquals(0, segment.getInitialization().getWindow().getResourceIndex());
                assertTrue(segment.getInitialization().getWindow().getLength() > 0);
            }
        }
        startAndAwaitTerminal();
        assertEquals("Worker/remux failure: " + mission.errObject,
                DownloadManagerService.MESSAGE_FINISHED, terminalMessage.get());
        assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
        assertTrue(mission.isFinished());
        assertEquals(2, mission.psState);
        assertTrue("Production initializer must inspect the physical resource",
                server.mediaHeads.get() > 0);
        assertEquals("Reused byte windows must not download the object repeatedly", 1,
                server.mediaGets.get());
        assertEquals("Download the physical object once in full", server.media.length,
                server.mediaBytes.get());
        assertEquals("Media reopening must be local, not another playlist fetch", 1,
                server.playlistGets.get());
        final File output = new File(directory, "completed.mp4");
        assertMp4TracksAndDuration(output);
        assertDecodedPlaybackAdvances(output);
        assertEquals("Local playback must not redownload physical HLS resources", 1,
                server.mediaGets.get());
    }

    private void serveAsset(final String asset, final String extension) throws IOException {
        final Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        final byte[] playlist;
        final byte[] media;
        try (InputStream input = testContext.getAssets().open(ASSET_DIRECTORY + asset + ".m3u8")) {
            playlist = bytes(input);
        }
        try (InputStream input =
                     testContext.getAssets().open(ASSET_DIRECTORY + asset + extension)) {
            media = bytes(input);
        }
        server = new Loopback(playlist, asset + extension, media);
    }

    private void prepareRestoredMission() throws Exception {
        final File output = new File(directory, "completed.mp4");
        assertTrue(output.createNewFile());
        final DownloadMission prepared = new DownloadMission(new String[]{server.playlistUrl()},
                new StoredFileHelper(context, Uri.fromFile(output), "video/mp4"), 'v',
                new PvcFromHlsRemuxer(new File(directory, "conversion")));
        prepared.threadCount = 1;
        prepared.maxRetry = 0;
        prepared.recoveryInfo = new MissionRecoveryInfo[]{new MissionRecoveryInfo(
                org.schabi.newpipe.extractor.MediaFormat.MPEG_4, "180p", false, 0, 'v', null)};
        // Use the production default HTTP client and entry point, not an injected downloader.
        new PvcHlsPreProcessor().modifyMission(prepared);
        assertTrue(prepared.pvcArbitraryData instanceof HlsDownloadPlan);
        assertEquals(0, server.mediaGets.get());
        final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        try (ObjectOutputStream outputStream = new ObjectOutputStream(serialized)) {
            outputStream.writeObject(prepared);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(serialized.toByteArray()))) {
            mission = (DownloadMission) input.readObject();
            // DownloadManager restores these transient runtime handles after reading a checkpoint.
            mission.threads = new Thread[0];
        }
        mission.storage = StoredFileHelper.deserialize(mission.storage, context);
        mission.restoreProgress();
        mission.metadata = new File(directory, "mission.checkpoint");
        mission.maxRetry = 0;
        mission.psAlgorithm.setTemporalDir(directory);
        mission.mHandler = new Handler(Looper.getMainLooper(), message -> {
            if (message.what == DownloadManagerService.MESSAGE_FINISHED
                    || message.what == DownloadManagerService.MESSAGE_ERROR) {
                terminalMessage.set(message.what);
                terminal.countDown();
            }
            return true;
        });
        final java.lang.reflect.Method checkpoint =
                DownloadMission.class.getDeclaredMethod("writeThisToFile");
        checkpoint.setAccessible(true);
        mission.checkpointReady = (boolean) checkpoint.invoke(mission);
        assertTrue("Prepared/restored mission must have its real initial checkpoint",
                mission.checkpointReady);
    }

    private void startAndAwaitTerminal() throws Exception {
        mission.start();
        assertTrue("Timed out in real download/remux; code=" + mission.errCode
                        + ", error=" + mission.errObject,
                terminal.await(WAIT_SECONDS, TimeUnit.SECONDS));
        stop(mission.init);
        for (final Thread worker : mission.threads) {
            // Normal terminal delivery occurs after all media reads and conversion are complete.
            worker.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse("Terminal mission left a live worker", worker.isAlive());
        }
        server.assertHealthy();
    }

    private static void assertMp4TracksAndDuration(final File output) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(output.getAbsolutePath());
            boolean video = false;
            boolean audio = false;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                final MediaFormat format = extractor.getTrackFormat(index);
                final String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    video = true;
                    assertEquals(320, format.getInteger(MediaFormat.KEY_WIDTH));
                    assertEquals(180, format.getInteger(MediaFormat.KEY_HEIGHT));
                } else if (mime != null && mime.startsWith("audio/")) {
                    audio = true;
                }
            }
            assertTrue("Completed MP4 must retain a video track", video);
            assertTrue("Completed MP4 must retain an audio track", audio);
        } finally {
            extractor.release();
        }
        final MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(output.getAbsolutePath());
            final String duration = metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull(duration);
            assertEquals("Repeated whole objects or discarded maps must not pass", 8000,
                    Long.parseLong(duration), 250);
        } finally {
            metadata.release();
        }
    }

    private void assertDecodedPlaybackAdvances(final File output) throws Exception {
        main(() -> {
            player = new ExoPlayer.Builder(context).build();
            surface = PlaceholderSurface.newInstance(context, false);
            player.setVideoSurface(surface);
            player.setVolume(0f);
            player.setVideoFrameMetadataListener((presentationTimeUs, releaseTimeNs,
                                                  format, mediaFormat) ->
                    videoFrames.incrementAndGet());
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(@NonNull final PlaybackException error) {
                    engineError.compareAndSet(null, error);
                }
            });
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onRenderedFirstFrame(@NonNull final EventTime eventTime,
                                                 @NonNull final Object outputSurface,
                                                 final long renderTimeMs) {
                    firstFrames.incrementAndGet();
                }

                @Override
                public void onAudioPositionAdvancing(@NonNull final EventTime eventTime,
                                                     final long playoutStartSystemTimeMs) {
                    audioAdvances.incrementAndGet();
                }
            });
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(output)));
            player.prepare();
            player.play();
            return null;
        });
        awaitPlayback(() -> player.getPlaybackState() == Player.STATE_READY
                && player.getCurrentPosition() >= 1500 && videoFrames.get() >= 5
                && firstFrames.get() > 0 && audioAdvances.get() > 0);
        main(() -> {
            assertTrue(player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO));
            assertTrue(player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO));
            assertEquals(8000, player.getDuration(), 250);
            player.pause();
            player.seekTo(6000);
            return null;
        });
        final int beforeSeekFrames = videoFrames.get();
        main(() -> {
            player.play();
            return null;
        });
        awaitPlayback(() -> player.getCurrentPosition() >= 6500
                && videoFrames.get() > beforeSeekFrames + 3);
    }

    private void awaitPlayback(final BooleanSupplier condition) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            final Throwable failure = engineError.get();
            if (failure != null) {
                throw new AssertionError("Decoded completed-download playback failed", failure);
            }
            if (main(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out awaiting real audio/video decoding and advancement");
    }

    private static <T> T main(final Callable<T> action) throws Exception {
        final FutureTask<T> task = new FutureTask<>(action);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static void stop(final Thread thread) throws InterruptedException {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse("Fixture must not leave a worker running", thread.isAlive());
        }
    }

    private static byte[] bytes(final InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void delete(final File file) throws IOException {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                delete(child);
            }
        }
        Files.deleteIfExists(file.toPath());
    }

    /** Small bounded HTTP server with real HEAD/range semantics and transfer accounting. */
    private static final class Loopback implements AutoCloseable {
        private final byte[] playlist;
        private final String mediaPath;
        private final byte[] media;
        private final ServerSocket socket;
        private final Thread worker;
        private final AtomicReference<Socket> active = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger playlistGets = new AtomicInteger();
        private final AtomicInteger mediaHeads = new AtomicInteger();
        private final AtomicInteger mediaGets = new AtomicInteger();
        private final AtomicLong mediaBytes = new AtomicLong();
        private volatile boolean rejectMediaGets;

        Loopback(final byte[] playlist, final String mediaName, final byte[] media)
                throws IOException {
            this.playlist = playlist;
            this.mediaPath = "/" + mediaName;
            this.media = media;
            socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve, "HlsDownloadLoopback");
            worker.setDaemon(true);
            worker.start();
        }

        String playlistUrl() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/index.m3u8";
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    active.set(client);
                    client.setSoTimeout(5000);
                    respond(client);
                } catch (final Exception error) {
                    if (!socket.isClosed()) {
                        failure.compareAndSet(null, error);
                    }
                } finally {
                    active.set(null);
                }
            }
        }

        private void respond(final Socket client) throws IOException {
            final BufferedReader reader = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            final String request = reader.readLine();
            if (request == null) {
                throw new IOException("Missing loopback request");
            }
            final String[] line = request.split(" ");
            if (line.length != 3 || !("HEAD".equals(line[0]) || "GET".equals(line[0]))) {
                throw new IOException("Unexpected loopback request: " + request);
            }
            final boolean head = "HEAD".equals(line[0]);
            final Map<String, String> headers = new HashMap<>();
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                final int separator = header.indexOf(':');
                if (separator > 0) {
                    headers.put(header.substring(0, separator).toLowerCase(Locale.ROOT),
                            header.substring(separator + 1).trim());
                }
            }
            final boolean resource = mediaPath.equals(line[1]);
            final boolean manifest = "/index.m3u8".equals(line[1]);
            final OutputStream output = client.getOutputStream();
            if (!resource && !manifest) {
                status(output, "404 Not Found");
                throw new IOException("Unexpected loopback resource: " + line[1]);
            }
            if (resource) {
                (head ? mediaHeads : mediaGets).incrementAndGet();
                if (!head && rejectMediaGets) {
                    status(output, "503 Service Unavailable");
                    return;
                }
            } else if (!head) {
                playlistGets.incrementAndGet();
            }
            final byte[] body = resource ? media : playlist;
            int start = 0;
            int end = body.length - 1;
            final String range = headers.get("range");
            if (range != null) {
                if (!range.startsWith("bytes=") || range.indexOf(',') >= 0) {
                    throw new IOException("Unexpected range: " + range);
                }
                final String[] bounds = range.substring(6).split("-", -1);
                if (bounds.length != 2 || bounds[0].isEmpty()) {
                    throw new IOException("Unsupported range: " + range);
                }
                start = Integer.parseInt(bounds[0]);
                if (!bounds[1].isEmpty()) {
                    end = Math.min(end, Integer.parseInt(bounds[1]));
                }
                if (start < 0 || start > end) {
                    status(output, "416 Range Not Satisfiable");
                    return;
                }
            }
            final String type = resource ? "application/octet-stream"
                    : "application/vnd.apple.mpegurl";
            final String response = "HTTP/1.1 " + (range == null ? "200 OK" : "206 Partial Content")
                    + "\r\nContent-Type: " + type + "\r\nContent-Length: " + (end - start + 1)
                    + "\r\nAccept-Ranges: bytes\r\nETag: \"generated-v1\"\r\n"
                    + (range == null ? "" : "Content-Range: bytes " + start + "-" + end + "/"
                    + body.length + "\r\n") + "Connection: close\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            if (!head) {
                output.write(body, start, end - start + 1);
                if (resource) {
                    mediaBytes.addAndGet(end - start + 1);
                }
            }
            output.flush();
        }

        private static void status(final OutputStream output, final String code)
                throws IOException {
            output.write(("HTTP/1.1 " + code + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        void assertHealthy() {
            if (failure.get() != null) {
                throw new AssertionError("Loopback HTTP fixture failed", failure.get());
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
            final Socket client = active.get();
            if (client != null) {
                client.close();
            }
            worker.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse("Fixture must not leave a server running", worker.isAlive());
        }
    }
}
