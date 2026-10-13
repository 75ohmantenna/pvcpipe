package us.shandian.giga.postprocessing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
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
    public void aFailedConversionLeavesNothingBehindAndKeepsItsError() {
        final File parent = new File(temporary.getRoot(), "ffmpeg_temp");
        final File[] used = new File[1];
        final IOException failure = new IOException("ffmpeg exited with 1");

        final IOException thrown = assertThrows(IOException.class, () ->
                PvcFromHlsRemuxer.inWorkDir(parent, 1000, workDir -> {
                    used[0] = workDir;
                    // what a conversion has on disk when it fails half way
                    Files.write(new File(workDir, "0.ts").toPath(), new byte[]{1, 2, 3});
                    Files.write(new File(workDir, "output.mp4").toPath(), new byte[]{4});
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertTrue(used[0].getPath().startsWith(parent.getPath()));
        assertFalse(used[0].exists());
    }

    @Test
    public void anInterruptedConversionIsCleanedUpToo() {
        final File[] used = new File[1];
        assertThrows(InterruptedException.class, () ->
                PvcFromHlsRemuxer.inWorkDir(temporary.getRoot(), 1000, workDir -> {
                    used[0] = workDir;
                    throw new InterruptedException();
                }));
        assertFalse(used[0].exists());
    }

    @Test
    public void aFinishedConversionRemovesItsDirectory() throws Exception {
        final File[] used = new File[1];
        PvcFromHlsRemuxer.inWorkDir(temporary.getRoot(), 1000, workDir -> {
            used[0] = workDir;
            assertTrue(workDir.isDirectory());
            assertEquals(0, workDir.list().length);
            Files.write(new File(workDir, "output.mp4").toPath(), new byte[]{4});
        });
        assertFalse(used[0].exists());
    }

    @Test
    public void leftoversOfEarlierConversionsAreRemovedWhenNoneIsRunning() throws Exception {
        final File parent = temporary.newFolder("ffmpeg_temp");
        // what a build with one shared directory left after a failed conversion
        final File oldSegment = new File(parent, "segment_1.ts");
        Files.write(oldSegment.toPath(), new byte[]{1});
        // and the directory of a conversion whose process was killed
        final File abandoned = new File(parent, "1000-1-1");
        assertTrue(abandoned.mkdir());
        Files.write(new File(abandoned, "output.mp4").toPath(), new byte[]{2});

        PvcFromHlsRemuxer.inWorkDir(parent, 2000, workDir -> {
            assertFalse(oldSegment.exists());
            assertFalse(abandoned.exists());
            assertTrue(workDir.isDirectory());
        });

        assertEquals(0, parent.list().length);
    }

    @Test
    public void aConversionThatStartsDoesNotDisturbOneThatIsRunning() throws Exception {
        final File parent = temporary.newFolder("ffmpeg_temp");

        PvcFromHlsRemuxer.inWorkDir(parent, 1000, first -> {
            final File segment = new File(first, "0.ts");
            Files.write(segment.toPath(), new byte[]{1});

            PvcFromHlsRemuxer.inWorkDir(parent, 2000, second -> {
                assertNotEquals(first, second);
                // the first conversion still has what it wrote
                assertTrue(segment.exists());
            });

            assertTrue(segment.exists());
            assertTrue(first.isDirectory());
        });

        assertEquals(0, parent.list().length);
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
