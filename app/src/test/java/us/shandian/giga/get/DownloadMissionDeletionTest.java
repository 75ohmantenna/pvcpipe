/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.get;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.util.Utility;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/** Actual files, AtomicFile checkpoints and serialization; storage refusals are injected. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class DownloadMissionDeletionTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private final byte[] bytes = {9, 8, 7, 6};

    private DownloadMission mission() throws Exception {
        final File media = folder.newFile("media.bin");
        Files.write(media.toPath(), bytes);
        final StoredFileHelper storage = spy(new StoredFileHelper(null,
                Uri.fromFile(media), "application/octet-stream"));
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://fixture.invalid/media"}, storage, 'v', null);
        mission.metadata = new File(folder.getRoot(), "checkpoint");
        mission.mHandler = new Handler(Looper.getMainLooper());
        assertTrue(Utility.writeToFile(mission.metadata, mission));
        return mission;
    }

    @Test
    public void failedDeletionPersistsIntentAndPreservesMediaAndCheckpoint() throws Exception {
        final DownloadMission mission = mission();
        doReturn(false).when(mission.storage).deleteRetainingPermission();
        mission.enqueued = true;
        assertFalse(mission.delete());
        assertTrue(mission.isDeletionPending());
        assertFalse(mission.enqueued);
        assertArrayEquals(bytes, Files.readAllBytes(
                new File(folder.getRoot(), "media.bin").toPath()));
        final DownloadMission saved = Utility.readFromFile(mission.metadata);
        assertNotNull(saved);
        assertTrue(saved.isDeletionPending());
        assertFalse(saved.enqueued);
        mission.start();
        mission.setEnqueued(true);
        mission.notifyFinished();
        assertFalse(mission.running);
        assertFalse(mission.enqueued);
        assertEquals(0, mission.current);
    }

    @Test
    public void failedIntentCheckpointNeverDeletesMedia() throws Exception {
        final DownloadMission mission = mission();
        final byte[] checkpoint = Files.readAllBytes(mission.metadata.toPath());
        try (var utility = mockStatic(Utility.class)) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(DownloadMission.class)))
                    .thenReturn(false);
            assertFalse(mission.delete());
            verify(mission.storage, never()).deleteRetainingPermission();
        }
        assertArrayEquals(bytes, Files.readAllBytes(
                new File(folder.getRoot(), "media.bin").toPath()));
        assertArrayEquals(checkpoint, Files.readAllBytes(mission.metadata.toPath()));
    }

    @Test
    public void metadataFailureAfterMediaDeletionCanBeRetried() throws Exception {
        final DownloadMission mission = spy(mission());
        final File checkpoint = mission.metadata;
        doReturn(false).doCallRealMethod().when(mission).deleteMetadata();
        assertFalse(mission.delete());
        assertFalse(new File(folder.getRoot(), "media.bin").exists());
        assertTrue(checkpoint.exists());
        final DownloadMission saved = Utility.readFromFile(checkpoint);
        assertNotNull(saved);
        assertTrue(saved.isDeletionPending());
        assertTrue(mission.delete());
        assertFalse(checkpoint.exists());
    }

    @Test
    public void activePostprocessingRefusesDeletionBeforeChangingFiles() throws Exception {
        final DownloadMission mission = mission();
        mission.psAlgorithm = mock(Postprocessing.class);
        mission.psState = 1;
        assertFalse(mission.delete());
        assertFalse(mission.isDeletionPending());
        verify(mission.storage, never()).deleteRetainingPermission();
        assertTrue(mission.metadata.exists());
    }

    @Test
    public void checkpointCompanionFailureKeepsDeletionMarker() throws Exception {
        final DownloadMission mission = mission();
        final File checkpoint = mission.metadata;
        final File companion = new File(checkpoint.getPath() + ".new");
        assertTrue(companion.mkdir());
        Files.write(new File(companion, "blocked").toPath(), bytes);
        assertFalse(mission.delete());
        assertFalse(new File(folder.getRoot(), "media.bin").exists());
        assertTrue(checkpoint.isFile());
        final DownloadMission saved = Utility.readFromFile(checkpoint);
        assertNotNull(saved);
        assertTrue(saved.isDeletionPending());
        Files.delete(new File(companion, "blocked").toPath());
        Files.delete(companion.toPath());
        assertTrue(mission.delete());
        assertFalse(checkpoint.exists());
    }

    @Test
    public void busyWritersAreInterruptedAndMediaWaitsUntilTheyExit() throws Exception {
        final DownloadMission mission = mission();
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch interrupted = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final Runnable owner = () -> {
            started.countDown();
            boolean done = false;
            while (!done) {
                try {
                    release.await();
                    done = true;
                } catch (final InterruptedException e) {
                    interrupted.countDown();
                }
            }
        };
        mission.threads = new Thread[]{new Thread(owner), new Thread(owner)};
        for (final Thread thread : mission.threads) {
            thread.start();
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(mission.delete());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertArrayEquals(bytes, Files.readAllBytes(
                    new File(folder.getRoot(), "media.bin").toPath()));
        } finally {
            release.countDown();
            for (final Thread thread : mission.threads) {
                thread.join(5000);
                assertFalse(thread.isAlive());
            }
        }
        assertTrue(mission.delete());
    }
}
