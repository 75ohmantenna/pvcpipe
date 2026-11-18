package us.shandian.giga.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.nio.file.Files;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;
import us.shandian.giga.util.Utility;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class DownloadFinalCheckpointTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void failedFinalCheckpointAndUndeliveredCompletionRestorePendingMission()
            throws Exception {
        final ContextWrapper context = downloadContext();
        final File pending = folder.newFolder("pending_downloads");
        final File output = folder.newFile("download.mp4");
        final File checkpoint = new File(pending, "1234");
        final byte[] payload = {10, 20, 30, 40};
        final var mission = new DownloadMission(new String[]{"https://example.test/file"},
                new StoredFileHelper(context, Uri.fromFile(output), "video/mp4"), 'v', null);
        mission.timestamp = 1234;
        mission.length = payload.length;
        mission.metadata = checkpoint;
        assertTrue(Utility.writeToFile(checkpoint, mission));
        final byte[] earlierCheckpoint = Files.readAllBytes(checkpoint.toPath());

        Files.write(output.toPath(), payload);
        mission.done = payload.length;
        mission.errObject = new CheckpointWriteFailure();
        mission.mHandler = mock(Handler.class);
        final Message undelivered = mock(Message.class);
        when(mission.mHandler.obtainMessage(DownloadManagerService.MESSAGE_FINISHED, mission))
                .thenReturn(undelivered);
        // Use the real worker completion and AtomicFile rollback before discarding this owner.
        final var completion = DownloadMission.class.getDeclaredMethod("notifyFinished");
        completion.setAccessible(true);
        completion.invoke(mission);
        assertTrue(mission.isFinished());
        verify(undelivered).sendToTarget();
        assertArrayEquals(earlierCheckpoint, Files.readAllBytes(checkpoint.toPath()));

        // The completion message is lost. A new manager only has the prior checkpoint and file.
        final var restarted = new DownloadManager(context, mock(Handler.class), null, null);
        final DownloadMission restored = restarted.findPendingMission(mission.timestamp);
        assertNotNull(restored);
        assertFalse(restored.isFinished());
        try (var history = new FinishedMissionStore(context)) {
            assertTrue(history.loadFinishedMissions().isEmpty());
        }
        assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
    }

    @Test
    public void replacedMissionCannotReplayEvenWhenItCannotWriteACheckpoint() throws Exception {
        final ContextWrapper context = downloadContext();
        final File output = folder.newFile("download.mp4");
        final var storage = new StoredFileHelper(context, Uri.fromFile(output), "video/mp4");
        final var mission = new DownloadMission(new String[]{"https://example.test/file"},
                storage, 'v', null);
        final Handler handler = new Handler(context.getMainLooper());
        final var manager = new DownloadManager(context, handler, null, null);
        manager.startMission(mission);
        mission.errObject = new CheckpointWriteFailure();

        assertTrue(manager.forgetMission(storage));
        assertNull(manager.findPendingMission(mission.timestamp));

        final byte[] replacement = {10, 20, 30, 40};
        Files.write(output.toPath(), replacement);
        final var restarted = new DownloadManager(context, handler, null, null);
        assertNull(restarted.findPendingMission(mission.timestamp));
        assertArrayEquals(replacement, Files.readAllBytes(output.toPath()));
    }

    @Test
    public void failedCheckpointRemovalRetainsOldMissionUntilRetry() throws Exception {
        final ContextWrapper context = downloadContext();
        final File output = folder.newFile("download.mp4");
        final byte[] payload = {10, 20, 30, 40};
        Files.write(output.toPath(), payload);
        final var storage = new StoredFileHelper(context, Uri.fromFile(output), "video/mp4");
        final var mission = new DownloadMission(new String[]{"https://example.test/file"},
                storage, 'v', null);
        final Handler handler = new Handler(context.getMainLooper());
        final var manager = new DownloadManager(context, handler, null, null);
        manager.startMission(mission);
        final var companion = Files.createDirectory(
                new File(mission.metadata.getPath() + ".new").toPath());
        final var blocked = Files.createFile(companion.resolve("blocked"));

        assertFalse(manager.forgetMission(storage));
        assertSame(mission, manager.findPendingMission(mission.timestamp));
        assertFalse(mission.isDeletionPending());
        final var restarted = new DownloadManager(context, handler, null, null);
        assertNotNull(restarted.findPendingMission(mission.timestamp));

        Files.delete(blocked);
        assertTrue(restarted.forgetMission(storage));
        assertNull(new DownloadManager(context, handler, null, null)
                .findPendingMission(mission.timestamp));
        assertArrayEquals(payload, Files.readAllBytes(output.toPath()));
    }

    private ContextWrapper downloadContext() {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public File getFilesDir() {
                return folder.getRoot();
            }
        };
    }

    private static final class CheckpointWriteFailure extends IOException {
        @Serial
        private static final long serialVersionUID = 1;

        @Serial
        private void writeObject(final ObjectOutputStream ignored) throws IOException {
            throw new IOException("injected completion checkpoint failure");
        }
    }
}
