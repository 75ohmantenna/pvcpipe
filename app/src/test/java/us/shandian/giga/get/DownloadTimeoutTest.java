package us.shandian.giga.get;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.extractor.Extractor;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;

import us.shandian.giga.io.FileStream;

/** Executes the worker loops with controlled transport faults, never a public service. */
public class DownloadTimeoutTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private static final String URL = "https://media.example/audio";

    @Test
    public void initializerRetriesConnectAndHeaderTimeouts() throws Exception {
        for (final boolean header : new boolean[]{false, true}) {
            try (var logging = mockStatic(Log.class)) {
                final Mission mission = new Mission(temporary.newFile());
                final HttpURLConnection failed = connection();
                final HttpURLConnection good = connection();
                when(good.getResponseCode()).thenReturn(200);
                when(good.getHeaderField("Content-Length")).thenReturn("1000");
                when(good.getContentLengthLong()).thenReturn(1000L);
                if (header) {
                    when(failed.getResponseCode()).thenThrow(new SocketTimeoutException());
                } else {
                    mission.connectFailures = 1;
                }
                mission.connections = new HttpURLConnection[]{failed, good, good};
                new DownloadInitializer(mission).run();
                assertEquals(1, mission.initialized);
                assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
                verify(failed, atLeastOnce()).disconnect();
                verify(good, never()).disconnect();
            }
        }
    }

    @Test
    public void initializerExhaustionStopsMissionAndKeepsRetryBudget() throws Exception {
        try (var logging = mockStatic(Log.class)) {
            final Mission mission = new Mission(temporary.newFile());
            mission.maxRetry = 0;
            mission.connectFailures = 10;
            mission.connections = new HttpURLConnection[]{connection(), connection()};
            new DownloadInitializer(mission).run();
            assertEquals(2, mission.opens);
            assertEquals(DownloadMission.ERROR_TIMEOUT, mission.errCode);
            assertFalse(mission.running);
            assertEquals(0, mission.initialized);
        }
    }

    @Test
    public void initializerPauseAndInterruptionDoNotRetryOrReport() throws Exception {
        for (final boolean header : new boolean[]{false, true}) {
            for (final boolean interrupt : new boolean[]{false, true}) {
                try (var logging = mockStatic(Log.class)) {
                    final Mission mission = new Mission(temporary.newFile());
                    final HttpURLConnection conn = connection();
                    mission.connections = new HttpURLConnection[]{conn};
                    final DownloadInitializer worker = new DownloadInitializer(mission);
                    mission.worker = worker;
                    if (header) {
                        when(conn.getResponseCode()).thenAnswer(call -> {
                            if (interrupt) {
                                worker.interrupt();
                            } else {
                                mission.running = false;
                            }
                            throw new InterruptedIOException("cancelled header read");
                        });
                    } else {
                        mission.cancel = interrupt ? 2 : 1;
                    }
                    worker.run();
                    assertEquals(1, mission.opens);
                    assertEquals(0, mission.initialized);
                    assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
                    verify(conn, atLeastOnce()).disconnect();
                }
            }
        }
    }

    @Test
    public void recoveryFetchRetriesTimeoutAndOrdinaryIoFailure() throws Exception {
        for (final IOException error : new IOException[]{new SocketTimeoutException(),
                new InterruptedIOException("request timeout"), new IOException("transport")}) {
            recovery(error, false, false, false);
        }
    }

    @Test
    public void recoveryFetchRetriesOrdinaryIoFailure() throws Exception {
        recovery(new IOException("transport"), false, false, false);
    }

    @Test
    public void recoveryFetchExhaustionStopsMissionWithoutDiscardingPartialBytes()
            throws Exception {
        recovery(new SocketTimeoutException(), true, false, false);
    }

    @Test
    public void recoveryRepeatedOrdinaryFetchFailuresExhaustBudget() throws Exception {
        recovery(new IOException("transport"), true, false, false);
    }

    @Test
    public void recoveryProbeRetriesWithoutFetchingAgain() throws Exception {
        recovery(new SocketTimeoutException(), false, true, false);
    }

    @Test
    public void recoveryProbeExhaustionStopsMission() throws Exception {
        recovery(new SocketTimeoutException(), true, true, false);
    }

    @Test
    public void recoveryCancellationDuringFetchOrProbeDoesNotRetry() throws Exception {
        recovery(new SocketTimeoutException(), false, false, true);
        recovery(new SocketTimeoutException(), false, true, true);
    }

    @Test
    public void recoveryThreadInterruptionDuringFetchOrProbeDoesNotRetry() throws Exception {
        recovery(new SocketTimeoutException(), false, false, 2);
        recovery(new SocketTimeoutException(), false, true, 2);
    }

    private void recovery(final IOException fault, final boolean exhaust,
                          final boolean probe, final boolean cancel) throws Exception {
        recovery(fault, exhaust, probe, cancel ? 1 : 0);
    }

    private void recovery(final IOException fault, final boolean exhaust,
                          final boolean probe, final int cancel) throws Exception {
        try (var logging = mockStatic(Log.class); var newPipe = mockStatic(NewPipe.class)) {
            final File file = temporary.newFile();
            final byte[] partial = new byte[187];
            java.util.Arrays.fill(partial, (byte) 7);
            Files.write(file.toPath(), partial);
            final Mission mission = new Mission(file);
            mission.blocks = new int[]{187};
            mission.done = 187;
            mission.fallbackResumeOffset = 187;
            mission.length = 1000;
            mission.maxRetry = 3;
            mission.source = "https://source.example/video";
            if (!probe) {
                mission.urls[0] = "https://media.example/expired";
            }
            final AudioStream audio = new AudioStream.Builder().setId("audio")
                    .setContent(URL, true).setMediaFormat(MediaFormat.M4A)
                    .setAverageBitrate(128).build();
            mission.recoveryInfo = new MissionRecoveryInfo[]{new MissionRecoveryInfo(audio)};
            final StreamExtractor extractor = mock(StreamExtractor.class);
            final StreamingService service = mock(StreamingService.class);
            newPipe.when(() -> NewPipe.getServiceByUrl(mission.source)).thenReturn(service);
            when(service.getStreamExtractor(mission.source)).thenReturn(extractor);
            doCallRealMethod().when(extractor).fetchPage();
            when(extractor.getAudioStreams()).thenAnswer(call -> {
                final java.lang.reflect.Field fetched =
                        Extractor.class.getDeclaredField("pageFetched");
                fetched.setAccessible(true);
                if (!fetched.getBoolean(extractor)) {
                    throw new IllegalStateException("Page has not been fetched");
                }
                return List.of(audio);
            });
            final int[] fetches = {0};
            doAnswer(call -> {
                fetches[0]++;
                if (!probe && (exhaust || fetches[0] == 1)) {
                    if (cancel == 1) {
                        mission.running = false;
                    } else if (cancel == 2) {
                        mission.worker.interrupt();
                    }
                    throw fault;
                }
                return null;
            }).when(extractor).onFetchPage(nullable(Downloader.class));
            final HttpURLConnection conn = connection();
            mission.connections = new HttpURLConnection[]{conn, conn, conn};
            if (probe) {
                mission.resourceIdentity = ResourceIdentity.from(conn, URL);
                mission.connectFailures = exhaust ? 3 : 1;
                mission.cancel = cancel;
            }
            final DownloadMissionRecover worker = new DownloadMissionRecover(mission,
                    DownloadMission.ERROR_HTTP_FORBIDDEN);
            mission.worker = worker;
            worker.run();
            assertEquals(probe || cancel != 0 ? 1 : exhaust ? 3 : 2, fetches[0]);
            verify(extractor, times(fetches[0])).fetchPage();
            assertEquals(exhaust || cancel != 0 ? 0 : 1, mission.recovered);
            if (!exhaust && cancel == 0) {
                assertEquals(URL, mission.urls[0]);
            }
            assertEquals(187, mission.done);
            assertEquals(187, mission.fallbackResumeOffset);
            assertEquals(187, mission.blocks[0]);
            assertArrayEquals(partial, Files.readAllBytes(file.toPath()));
            if (exhaust) {
                assertFalse(mission.running);
                assertEquals(DownloadMission.ERROR_HTTP_FORBIDDEN, mission.errCode);
            } else {
                assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
            }
            if (probe) {
                assertEquals(cancel != 0 ? 1 : exhaust ? 3 : 2, mission.opens);
                verify(conn, atLeastOnce()).disconnect();
            }
        }
    }

    private static HttpURLConnection connection() throws Exception {
        final HttpURLConnection conn = mock(HttpURLConnection.class);
        when(conn.getResponseCode()).thenReturn(206);
        when(conn.getHeaderField("Content-Range")).thenReturn("bytes 990-998/1000");
        when(conn.getHeaderField("Content-Length")).thenReturn("9");
        when(conn.getContentLengthLong()).thenReturn(9L);
        when(conn.getHeaderField("ETag")).thenReturn("\"v1\"");
        when(conn.getURL()).thenReturn(new URL(URL));
        when(conn.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return conn;
    }

    private static final class Mission extends DownloadMission {
        private HttpURLConnection[] connections;
        private int opens;
        private int connectFailures;
        private int cancel;
        private int initialized;
        private int recovered;
        private Thread worker;

        Mission(final File file) throws Exception {
            super(new String[]{URL}, mock(StoredFileHelper.class), 'a', null);
            when(storage.getStream()).thenAnswer(call -> new FileStream(file));
            when(storage.canWrite()).thenReturn(true);
            mHandler = mock(Handler.class);
            when(mHandler.obtainMessage(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.any())).thenReturn(mock(Message.class));
            blocks = new int[0];
            running = true;
            length = 1000;
            metadata = null;
        }

        @Override
        HttpURLConnection openConnection(final String url, final boolean head,
                                         final long start, final long end) {
            return connections[opens++];
        }

        @Override
        void establishConnection(final int id, final HttpURLConnection conn) throws IOException {
            if (cancel != 0) {
                if (cancel == 1) {
                    running = false;
                } else {
                    worker.interrupt();
                }
                throw new InterruptedIOException("cancelled");
            }
            if (connectFailures-- > 0) {
                throw new SocketTimeoutException("controlled timeout");
            }
        }

        @Override
        synchronized void initializationFinished() {
            initialized++;
        }

        @Override
        synchronized void recoveryFinished() {
            recovered++;
        }
    }
}
