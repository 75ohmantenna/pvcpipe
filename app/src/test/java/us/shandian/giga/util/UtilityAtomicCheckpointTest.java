package us.shandian.giga.util;

import android.util.AtomicFile;
import android.util.Log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UtilityAtomicCheckpointTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static final class FailingCheckpoint implements Serializable {
        private void writeObject(final ObjectOutputStream out) throws IOException {
            out.defaultWriteObject();
            out.writeUTF("partially written checkpoint");
            throw new IOException("serialization failed");
        }
    }

    @Test
    public void failedSerializationRetainsReadablePreviousCheckpoint() throws Exception {
        final File checkpoint = folder.newFile("checkpoint");
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(checkpoint))) {
            out.writeObject("previous checkpoint");
        }
        final File staged = new File(checkpoint.getPath() + ".new");
        try (var logs = mockStatic(Log.class);
             MockedConstruction<AtomicFile> files = mockConstruction(AtomicFile.class,
                     (atomic, context) -> {
                         when(atomic.startWrite()).thenAnswer(call -> new FileOutputStream(staged));
                         doAnswer(call -> {
                             ((FileOutputStream) call.getArgument(0)).close();
                             Files.deleteIfExists(staged.toPath());
                             return null;
                         }).when(atomic).failWrite(any(FileOutputStream.class));
                         when(atomic.openRead())
                                 .thenAnswer(call -> new FileInputStream(checkpoint));
                     })) {
            assertFalse(Utility.writeToFile(checkpoint, new FailingCheckpoint()));
            assertEquals("previous checkpoint", Utility.<String>readFromFile(checkpoint));
            assertTrue(checkpoint.exists());
            assertFalse(staged.exists());
            verify(files.constructed().get(0)).failWrite(any(FileOutputStream.class));
            verify(files.constructed().get(0), never()).finishWrite(any(FileOutputStream.class));
        }
    }

    @Test
    public void readRequestsAtomicFileRecoveryForBackupOnlyCheckpoint() throws Exception {
        final File checkpoint = new File(folder.getRoot(), "checkpoint");
        final File backup = folder.newFile("checkpoint.bak");
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(backup))) {
            out.writeObject("backed up checkpoint");
        }
        try (MockedConstruction<AtomicFile> files = mockConstruction(AtomicFile.class,
                (atomic, context) -> when(atomic.openRead()).thenAnswer(call -> {
                    Files.move(backup.toPath(), checkpoint.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    return new FileInputStream(checkpoint);
                }))) {
            assertEquals("backed up checkpoint", Utility.<String>readFromFile(checkpoint));
            verify(files.constructed().get(0)).openRead();
            assertTrue(checkpoint.exists());
        }
    }
}
