/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.service;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;
import us.shandian.giga.util.Utility;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real checkpoint serialization/loader and media files; finished-store calls are doubled. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class DownloadDeletionRecoveryTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void coldLoadDoesNotRecreateOrFinishDeletedMedia() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final File checkpoint = new File(pending, "1234");
        final File media = folder.newFile("data.bin");
        Files.write(media.toPath(), new byte[]{1, 2, 3});
        final DownloadMission mission = spy(new DownloadMission(
                new String[]{"https://fixture.invalid/data"},
                new StoredFileHelper(null, Uri.fromFile(media), "application/octet-stream"),
                'v', null));
        mission.metadata = checkpoint;
        mission.timestamp = 1234;
        mission.mHandler = new Handler(Looper.getMainLooper());
        mission.current = 1; // Also reject replay as a completed download.
        doReturn(false).when(mission).deleteMetadata();
        assertFalse(mission.delete());
        assertFalse(media.exists());
        assertTrue(checkpoint.exists());
        final DownloadMission onDisk = Utility.readFromFile(checkpoint);
        assertNotNull(onDisk);
        assertTrue(onDisk.isDeletionPending());

        final Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() {
                return folder.getRoot();
            }
            @Override public File getExternalFilesDir(final String type) {
                return folder.getRoot();
            }
        };
        try (var stores = mockConstruction(FinishedMissionStore.class, (store, construction) ->
                when(store.loadFinishedMissions()).thenReturn(new ArrayList<>()))) {
            final DownloadManager manager = new DownloadManager(context,
                    new Handler(Looper.getMainLooper()), null, null);
            final DownloadMission restored = manager.findPendingMission(1234);
            assertNotNull(restored);
            assertTrue(restored.isDeletionPending());
            manager.startAllMissions();
            manager.resumeMission(restored);
            manager.tryRecover(restored);
            assertFalse(manager.setFinished(restored));
            assertFalse(media.exists());
            assertFalse(restored.running);
            assertFalse(manager.canRecoverMission(restored));
            verify(stores.constructed().get(0), never()).addFinishedMission(any());
            assertTrue(manager.deleteMission(restored, true));
            assertNull(manager.findPendingMission(1234));
            assertFalse(checkpoint.exists());
            final DownloadManager restarted = new DownloadManager(context,
                    new Handler(Looper.getMainLooper()), null, null);
            assertNull(restarted.findPendingMission(1234));
            assertFalse(media.exists());
        }
    }
}
