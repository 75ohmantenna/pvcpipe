package us.shandian.giga.postprocessing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class PvcFromHlsRemuxerTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void everyConversionGetsADirectoryOfItsOwn() throws IOException {
        final File parent = new File(temporary.getRoot(), "ffmpeg_temp");
        // two conversions of one mission, and one of another mission
        final File first = PvcFromHlsRemuxer.newWorkDir(parent, 1000);
        final File second = PvcFromHlsRemuxer.newWorkDir(parent, 1000);
        final File third = PvcFromHlsRemuxer.newWorkDir(parent, 2000);

        assertNotEquals(first, second);
        assertNotEquals(first, third);
        for (final File dir : new File[]{first, second, third}) {
            assertTrue(dir.isDirectory());
            assertEquals(parent, dir.getParentFile());
            assertEquals(0, dir.list().length);
        }

        // what one conversion writes is not in the directory of another
        Files.write(new File(first, "output.mp4").toPath(), new byte[]{1});
        assertFalse(new File(second, "output.mp4").exists());
    }

    @Test
    public void aDirectoryThatCannotBeMadeFailsTheConversion() throws IOException {
        // a file where the parent directory should be
        final File notADirectory = temporary.newFile("ffmpeg_temp");
        assertThrows(IOException.class, () -> PvcFromHlsRemuxer.newWorkDir(notADirectory, 1));
    }

    @Test
    public void cleaningUpRemovesSegmentsAndOutput() throws IOException {
        final File dir = PvcFromHlsRemuxer.newWorkDir(temporary.getRoot(), 1);
        final File nested = new File(dir, "segments");
        assertTrue(nested.mkdir());
        Files.write(new File(nested, "0.ts").toPath(), new byte[]{1, 2, 3});
        Files.write(new File(dir, "output.mp4").toPath(), new byte[]{4});

        PvcFromHlsRemuxer.deleteQuietly(dir);

        assertFalse(dir.exists());
        // its neighbours are left alone
        assertTrue(temporary.getRoot().isDirectory());
    }

    @Test
    public void cleaningUpWhatIsNotThereDoesNothing() {
        PvcFromHlsRemuxer.deleteQuietly(null);
        PvcFromHlsRemuxer.deleteQuietly(new File(temporary.getRoot(), "never-made"));
    }
}
