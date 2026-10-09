package us.shandian.giga.service;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.AtomicFile;
import android.util.Log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;
import us.shandian.giga.util.Utility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DownloadManagerCheckpointTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void failedHistoryInsertionKeepsBackupOnlyCheckpointForRestartReplay() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final File checkpoint = new File(pending, "1234");
        final File backup = new File(pending, "1234.bak");
        assertTrue(backup.createNewFile());
        final Context context = mock(Context.class);
        when(context.getExternalFilesDir("pending_downloads")).thenReturn(pending);
        when(context.getExternalFilesDir(null)).thenReturn(folder.getRoot());

        final DownloadMission mission = mock(DownloadMission.class);
        mission.timestamp = 1234;
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        final Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn("file:///download.mp4");
        when(storage.getUri()).thenReturn(uri);
        final StoredFileHelper serializedStorage = mock(StoredFileHelper.class);
        when(serializedStorage.getUri()).thenThrow(new IllegalStateException("not deserialized"));
        mission.storage = serializedStorage;
        when(mission.isFinished()).thenReturn(true);
        when(mission.deleteMetadata()).thenAnswer(call -> Files.deleteIfExists(backup.toPath()));

        final FinishedMission existing = new FinishedMission();
        existing.timestamp = 1234;
        existing.storage = storage;
        final AtomicInteger inserts = new AtomicInteger();
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             var fileHelpers = mockStatic(StoredFileHelper.class);
             MockedConstruction<FinishedMissionStore> stores =
                     mockConstruction(FinishedMissionStore.class, (store, construction) -> {
                         when(store.loadFinishedMissions()).thenReturn(
                                 construction.getCount() == 2
                                         ? new ArrayList<>(List.of(existing))
                                         : new ArrayList<>());
                         when(store.addFinishedMission(any(DownloadMission.class)))
                                 .thenAnswer(call -> inserts.incrementAndGet() > 1);
                     })) {
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            utility.when(() -> Utility.<DownloadMission>readFromFile(checkpoint))
                    .thenReturn(mission);

            fileHelpers.when(() -> StoredFileHelper.deserialize(serializedStorage, context))
                    .thenReturn(storage);
            fileHelpers.when(() -> StoredFileHelper.deserialize(storage, context))
                    .thenReturn(storage);
            final DownloadManager first =
                    new DownloadManager(context, mock(Handler.class), null, null);
            assertSame(mission, first.findPendingMission(1234));
            assertTrue(backup.exists());

            final DownloadManager restarted =
                    new DownloadManager(context, mock(Handler.class), null, null);
            assertNull(restarted.findPendingMission(1234));
            assertFalse(backup.exists());
            assertEquals(2, restarted.getIterator().getOldListSize());
            verify(mission).deleteMetadata();
            assertEquals(2, inserts.get());
        }
    }

    @Test
    public void initialCheckpointFailurePreventsDownloadUntilRetrySucceeds() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final Context context = mock(Context.class);
        when(context.getExternalFilesDir("pending_downloads")).thenReturn(pending);
        final DownloadMission mission = mock(DownloadMission.class);
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             MockedConstruction<AtomicFile> atomicFiles = mockConstruction(AtomicFile.class);
             MockedConstruction<FinishedMissionStore> stores =
                     mockConstruction(FinishedMissionStore.class, (store, construction) ->
                             when(store.loadFinishedMissions()).thenReturn(new ArrayList<>()))) {
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            utility.when(() -> Utility.writeToFile(any(File.class), eq(mission)))
                    .thenReturn(false, false, false, true);
            final DownloadManager manager =
                    new DownloadManager(context, mock(Handler.class), null, null);

            manager.startMission(mission);
            assertFalse(mission.checkpointReady);
            verify(mission, never()).start();
            verify(mission).notifyError(eq(DownloadMission.ERROR_FILE_CREATION),
                    any(IOException.class));
            verify(atomicFiles.constructed().get(0)).delete();

            manager.startAllMissions();
            verify(mission, never()).start();
            mission.enqueued = true;
            manager.handleConnectivityState(DownloadManager.NetworkState.Operating, false);
            verify(mission, never()).start();
            manager.resumeMission(mission);
            assertTrue(mission.checkpointReady);
            verify(mission).start();
        }
    }

    @Test
    public void temporarilyUnreadableCheckpointRemainsForLaterRecovery() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final File checkpoint = new File(pending, "1234");
        assertTrue(checkpoint.createNewFile());
        final Context context = mock(Context.class);
        when(context.getExternalFilesDir("pending_downloads")).thenReturn(pending);
        when(context.getExternalFilesDir(null)).thenReturn(folder.getRoot());
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             MockedConstruction<FinishedMissionStore> stores =
                     mockConstruction(FinishedMissionStore.class, (store, construction) ->
                             when(store.loadFinishedMissions()).thenReturn(new ArrayList<>()))) {
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            utility.when(() -> Utility.<DownloadMission>readFromFile(checkpoint))
                    .thenReturn(null);
            new DownloadManager(context, mock(Handler.class), null, null);
            assertTrue(checkpoint.exists());
        }
    }

}
