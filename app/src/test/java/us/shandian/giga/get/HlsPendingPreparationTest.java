package us.shandian.giga.get;

import android.os.Handler;
import android.os.Message;

import org.junit.Test;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import us.shandian.giga.preprocessing.PvcHlsPreProcessor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE,
        application = android.app.Application.class, sdk = 26)
public class HlsPendingPreparationTest {
    private DownloadMission mission() {
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.existsAsFile()).thenReturn(true);
        when(storage.canWrite()).thenReturn(true);
        final DownloadMission mission = spy(new DownloadMission(
                new String[]{"https://example.test/playlist.m3u8"}, storage, 'v', null));
        mission.hlsPreparationPending = true;
        mission.mHandler = mock(Handler.class);
        when(mission.mHandler.obtainMessage(anyInt(), any())).thenReturn(mock(Message.class));
        return mission;
    }

    @Test
    public void pauseDiscardsLatePreparedResultAndRetainsPlaylistForRetry() throws Exception {
        final DownloadMission mission = mission();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch complete = new CountDownLatch(1);
        final PvcHlsPreProcessor.Prepared result = mock(PvcHlsPreProcessor.Prepared.class);
        try (var construction = mockConstruction(PvcHlsPreProcessor.class, (processor, context) -> {
            when(processor.prepare(any())).thenAnswer(call -> {
                entered.countDown();
                // Deliberately simulate an uncooperative late response after cancellation.
                boolean ready = false;
                while (!ready) {
                    try {
                        ready = complete.await(5, TimeUnit.SECONDS);
                        assertTrue(ready);
                    } catch (InterruptedException ignored) {
                        // Exercise the running/owner fence, not just interruption checking.
                    }
                }
                return result;
            });
        })) {
            mission.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            final Thread worker = mission.init;
            mission.pause();
            complete.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertTrue(mission.hlsPreparationPending);
            assertArrayEquals(new String[]{"https://example.test/playlist.m3u8"}, mission.urls);
            verify(result, never()).applyTo(any());
            verify(construction.constructed().get(0)).cancel();
        } finally {
            complete.countDown();
        }
    }

    @Test
    public void failedPreparedCheckpointRestoresRetryableOriginalPlaylist() throws Exception {
        final DownloadMission mission = mission();
        final PvcHlsPreProcessor.Prepared result = mock(PvcHlsPreProcessor.Prepared.class);
        doAnswer(call -> {
            mission.urls = new String[]{"https://example.test/0.ts", "https://example.test/1.ts"};
            mission.offsets = new long[2];
            mission.pvcArbitraryData = new Object();
            return null;
        }).when(result).applyTo(mission);
        doReturn(false).when(mission).writeThisToFile();
        try (var construction = mockConstruction(PvcHlsPreProcessor.class,
                (processor, context) -> when(processor.prepare(any())).thenReturn(result))) {
            mission.start();
            mission.init.join(5000);
            assertFalse(mission.running);
            assertTrue(mission.hlsPreparationPending);
            assertArrayEquals(new String[]{"https://example.test/playlist.m3u8"}, mission.urls);
            org.junit.Assert.assertEquals(DownloadMission.ERROR_FILE_CREATION, mission.errCode);
        }
    }

    @Test
    public void existingPreparedMissionDoesNotStartNewHlsPreparation() {
        final DownloadMission mission = mission();
        mission.hlsPreparationPending = false; // Default for serialized older checkpoints.
        mission.blocks = new int[]{0};
        doReturn(new Thread() {
            @Override public synchronized void start() { }
        }).when(mission).createDownloadWorker(0);
        try (var construction = mockConstruction(PvcHlsPreProcessor.class)) {
            mission.start();
            assertTrue(construction.constructed().isEmpty());
        }
    }
    @Test
    public void preparedCheckpointPrecedesResourceDownload() throws Exception {
        final DownloadMission mission = mission();
        final PvcHlsPreProcessor.Prepared result = mock(PvcHlsPreProcessor.Prepared.class);
        doAnswer(call -> {
            mission.urls = new String[]{"https://example.test/0.ts"};
            return null;
        }).when(result).applyTo(mission);
        final java.util.concurrent.atomic.AtomicBoolean saved =
                new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            assertFalse(mission.hlsPreparationPending);
            assertArrayEquals(new String[]{"https://example.test/0.ts"}, mission.urls);
            saved.set(true);
            return true;
        }).when(mission).writeThisToFile();
        final CountDownLatch resourceStarted = new CountDownLatch(1);
        doReturn(new Thread() {
            @Override public synchronized void start() {
                assertTrue(saved.get());
                resourceStarted.countDown();
            }
        }).when(mission).createInitializer();
        try (var construction = mockConstruction(PvcHlsPreProcessor.class,
                (processor, context) -> when(processor.prepare(any())).thenReturn(result))) {
            mission.start();
            assertTrue(resourceStarted.await(5, TimeUnit.SECONDS));
            assertFalse(mission.hlsPreparationPending);
        }
    }

    @Test
    public void pendingPlaylistSurvivesCheckpointSerialization() throws Exception {
        final DownloadMission original = new DownloadMission(
                new String[]{"https://example.test/playlist.m3u8"}, null, 'v', null);
        original.hlsPreparationPending = true;
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        try (var input = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            final DownloadMission restored = (DownloadMission) input.readObject();
            assertTrue(restored.hlsPreparationPending);
            assertArrayEquals(original.urls, restored.urls);
        }
    }

}
