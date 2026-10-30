package us.shandian.giga.service;

import android.content.Context;
import android.os.Handler;
import android.net.Uri;
import android.util.Log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DownloadManagerDeleteEntryTest {
    private static final long TIMESTAMP = 4321;

    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private Context context;

    @Test
    public void onlyFinishedEntriesCanBeForgottenWithoutTheirFile() {
        assertTrue(DownloadManager.canDeleteEntryOnly(new FinishedMission()));
        assertFalse(DownloadManager.canDeleteEntryOnly(mock(DownloadMission.class)));
        assertFalse(DownloadManager.canDeleteEntryOnly(null));
    }

    @Test
    public void forgettingAnUnfinishedEntryKeepsItsMissionAndCheckpoint() throws Exception {
        final File checkpoint = pendingCheckpoint();
        final DownloadMission mission = pendingMission();
        final FinishedMission finished = finishedMission();

        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             var files = mockStatic(StoredFileHelper.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore(finished)) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            utility.when(() -> Utility.mkdir(checkpoint.getParentFile(), false)).thenReturn(true);
            utility.when(() -> Utility.<DownloadMission>readFromFile(checkpoint))
                    .thenReturn(mission);
            files.when(() -> StoredFileHelper.deserialize(mission.storage, context()))
                    .thenReturn(mission.storage);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);
            assertSame(mission, manager.findPendingMission(TIMESTAMP));

            manager.deleteMission(mission, false);

            // The entry stays visible: hiding it would not stop its threads or checkpoint.
            assertSame(mission, manager.findPendingMission(TIMESTAMP));
            verify(mission, never()).delete();
            verify(mission, never()).deleteMetadata();
            verify(mission, never()).pause();
            assertTrue(checkpoint.exists());
            // pending header, pending mission, finished header, finished mission
            assertEquals(4, manager.getIterator().getOldListSize());
        }
    }

    @Test
    public void deletingAnUnfinishedEntryWithItsFileStillRemovesBoth() throws Exception {
        final File checkpoint = pendingCheckpoint();
        final DownloadMission mission = pendingMission();
        when(mission.delete()).thenReturn(true);

        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             var files = mockStatic(StoredFileHelper.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore()) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            utility.when(() -> Utility.mkdir(checkpoint.getParentFile(), false)).thenReturn(true);
            utility.when(() -> Utility.<DownloadMission>readFromFile(checkpoint))
                    .thenReturn(mission);
            files.when(() -> StoredFileHelper.deserialize(mission.storage, context()))
                    .thenReturn(mission.storage);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);

            manager.deleteMission(mission, true);

            assertNull(manager.findPendingMission(TIMESTAMP));
            verify(mission).delete();
        }
    }

    @Test
    public void forgettingAFinishedEntryKeepsItsFile() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final FinishedMission finished = finishedMission();

        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore(finished)) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);
            assertEquals(2, manager.getIterator().getOldListSize());

            manager.deleteMission(finished, false);

            assertEquals(0, manager.getIterator().getOldListSize());
            verify(stores.constructed().get(0)).deleteMission(finished);
            verify(finished.storage, never()).delete();
            verify(finished.storage, never()).deleteRetainingPermission();
        }
    }

    @Test
    public void failedFileDeletionKeepsFinishedEntryForRetry() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final FinishedMission finished = finishedMission();
        when(finished.storage.deleteRetainingPermission()).thenReturn(false, true);
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore(finished)) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);
            assertFalse(manager.deleteMission(finished, true));
            assertEquals(2, manager.getIterator().getOldListSize());
            verify(stores.constructed().get(0), never()).deleteMission(finished);
            assertTrue(manager.deleteMission(finished, true));
            assertEquals(0, manager.getIterator().getOldListSize());
            verify(stores.constructed().get(0)).deleteMission(finished);
        }
    }

    @Test
    public void providerExceptionKeepsFinishedEntry() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final FinishedMission finished = finishedMission();
        when(finished.storage.deleteRetainingPermission())
                .thenThrow(new SecurityException("denied"));
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore(finished)) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);
            manager.deleteMission(finished, true);
            assertEquals(2, manager.getIterator().getOldListSize());
            verify(stores.constructed().get(0), never()).deleteMission(finished);
        }
    }

    @Test
    public void databaseExceptionKeepsFinishedEntry() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final FinishedMission finished = finishedMission();
        when(finished.storage.deleteRetainingPermission()).thenReturn(true);
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore(finished)) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);
            doThrow(new IllegalStateException("database unavailable"))
                    .when(stores.constructed().get(0)).deleteMission(finished);
            assertFalse(manager.deleteMission(finished, true));
            assertEquals(2, manager.getIterator().getOldListSize());
            verify(finished.storage, never()).releasePermission();
            doNothing().when(stores.constructed().get(0)).deleteMission(finished);
            assertTrue(manager.deleteMission(finished, true));
            verify(finished.storage).releasePermission();
        }
    }

    @Test
    public void failedPendingDeletionKeepsMissionAndCheckpoint() throws Exception {
        final File checkpoint = pendingCheckpoint();
        final DownloadMission mission = pendingMission();
        when(mission.delete()).thenReturn(false);
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             var files = mockStatic(StoredFileHelper.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore()) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            utility.when(() -> Utility.mkdir(checkpoint.getParentFile(), false)).thenReturn(true);
            utility.when(() -> Utility.<DownloadMission>readFromFile(checkpoint))
                    .thenReturn(mission);
            files.when(() -> StoredFileHelper.deserialize(mission.storage, context()))
                    .thenReturn(mission.storage);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);
            manager.deleteMission(mission, true);
            assertSame(mission, manager.findPendingMission(TIMESTAMP));
            assertTrue(checkpoint.exists());
        }
    }

    @Test
    public void staleDeletionDoesNotDeleteAReplacementAtTheSameUri() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final FinishedMission finished = finishedMission();
        when(finished.storage.deleteRetainingPermission()).thenReturn(true);
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore(finished)) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);
            assertTrue(manager.deleteMission(finished, true));
            assertTrue(manager.deleteMission(finished, true));
            verify(finished.storage, times(1)).deleteRetainingPermission();
            verify(stores.constructed().get(0), times(1)).deleteMission(finished);
        }
    }

    @Test
    public void inaccessibleFinishedProviderIsNotForgottenAtLoadOrConflictCheck() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final FinishedMission finished = finishedMission();
        when(finished.storage.existsAsFile()).thenReturn(false);
        when(finished.storage.isAbsent()).thenReturn(false);
        when(finished.storage.equals(finished.storage)).thenReturn(true);
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore(finished)) {
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);
            assertEquals(2, manager.getIterator().getOldListSize());
            assertEquals(MissionState.Finished,
                    manager.checkForExistingMission(finished.storage));
            verify(stores.constructed().get(0), never()).deleteMission(finished);
        }
    }

    @Test
    public void receiptWriteFailureKeepsTheEntryAndDoesNotDeleteMediaTwice() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final FinishedMission finished = finishedMission();
        when(finished.storage.deleteRetainingPermission()).thenReturn(true);
        try (var logs = mockStatic(Log.class);
             var utility = mockStatic(Utility.class);
             MockedConstruction<FinishedMissionStore> stores = finishedStore(finished)) {
            utility.when(() -> Utility.mkdir(pending, false)).thenReturn(true);
            final DownloadManager manager =
                    new DownloadManager(context(), mock(Handler.class), null, null);
            assertFalse(manager.deleteMission(finished, true));
            assertTrue(finished.mediaDeleted);
            assertEquals(2, manager.getIterator().getOldListSize());
            verify(stores.constructed().get(0), never()).deleteMission(finished);
            utility.when(() -> Utility.writeToFile(any(File.class), any(Serializable.class)))
                    .thenReturn(true);
            assertTrue(manager.deleteMission(finished, true));
            verify(finished.storage, times(1)).deleteRetainingPermission();
        }
    }

    private Context context() {
        if (context == null) {
            context = mock(Context.class);
            when(context.getFilesDir()).thenReturn(folder.getRoot());
            when(context.getExternalFilesDir(null)).thenReturn(folder.getRoot());
        }
        return context;
    }

    private File pendingCheckpoint() throws Exception {
        final File pending = folder.newFolder("pending_downloads");
        final File checkpoint = new File(pending, Long.toString(TIMESTAMP));
        assertTrue(checkpoint.createNewFile());
        return checkpoint;
    }

    private static DownloadMission pendingMission() {
        final DownloadMission mission = mock(DownloadMission.class);
        mission.timestamp = TIMESTAMP;
        mission.storage = mock(StoredFileHelper.class);
        when(mission.storage.existsAsFile()).thenReturn(true);
        return mission;
    }

    private static FinishedMission finishedMission() {
        final FinishedMission finished = new FinishedMission();
        finished.timestamp = 1;
        finished.storage = mock(StoredFileHelper.class);
        final Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn("content://fixture/document/finished");
        when(finished.storage.getUri()).thenReturn(uri);
        when(finished.storage.existsAsFile()).thenReturn(true);
        return finished;
    }

    private static MockedConstruction<FinishedMissionStore> finishedStore(
            final FinishedMission... finished) {
        return mockConstruction(FinishedMissionStore.class, (store, construction) ->
                when(store.loadFinishedMissions())
                        .thenReturn(new ArrayList<>(List.of(finished))));
    }
}
