package org.schabi.newpipe.streams.io;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class StoredFileNameContainmentTest {
    private static final String MIME_TYPE = "video/mp4";
    private static final byte[] OUTSIDE_CONTENT = "keep me".getBytes(StandardCharsets.UTF_8);

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void plainNamesAreSingleComponentsButMayContainDots() {
        assertTrue(StoredFileHelper.isPlainFileName("video.mp4"));
        assertTrue(StoredFileHelper.isPlainFileName("..video..mp4"));
        assertTrue(StoredFileHelper.isPlainFileName("a..b.mp4"));
        assertTrue(StoredFileHelper.isPlainFileName("...mp4"));

        assertFalse(StoredFileHelper.isPlainFileName(null));
        assertFalse(StoredFileHelper.isPlainFileName(""));
        assertFalse(StoredFileHelper.isPlainFileName("."));
        assertFalse(StoredFileHelper.isPlainFileName(".."));
        assertFalse(StoredFileHelper.isPlainFileName("../outside.mp4"));
        assertFalse(StoredFileHelper.isPlainFileName("sub/video.mp4"));
        assertFalse(StoredFileHelper.isPlainFileName("/absolute.mp4"));
        assertFalse(StoredFileHelper.isPlainFileName("..\\outside.mp4"));
        assertFalse(StoredFileHelper.isPlainFileName("nul\0.mp4"));
    }

    @Test
    public void theFileConstructorRefusesANameOutsideItsFolderWithoutTouchingIt()
            throws IOException {
        final Path downloads = folder.newFolder("downloads").toPath();
        final Path outside = writeOutsideFile();

        assertThrows(IOException.class,
                () -> new StoredFileHelper(downloads, "../outside.mp4", MIME_TYPE));
        assertThrows(IOException.class,
                () -> new StoredFileHelper(downloads, outside.toString(), MIME_TYPE));

        assertArrayEquals(OUTSIDE_CONTENT, Files.readAllBytes(outside));
        assertEquals(0, downloads.toFile().list().length);
    }

    @Test
    public void aFileFolderRefusesToCreateOrFindANameOutsideIt() throws IOException {
        final File downloads = folder.newFolder("downloads");
        final Path outside = writeOutsideFile();
        final Uri tree = mock(Uri.class);
        when(tree.getScheme()).thenReturn("file");
        when(tree.toString()).thenReturn(downloads.toURI().toString());

        try (var logs = mockStatic(Log.class);
             var uris = mockStatic(Uri.class)) {
            uris.when(() -> Uri.fromFile(any(File.class))).thenReturn(mock(Uri.class));
            final StoredDirectoryHelper directory =
                    new StoredDirectoryHelper(mock(Context.class), tree, "video");

            assertNull(directory.createFile("../outside.mp4", MIME_TYPE));
            assertNull(directory.createUniqueFile("../outside.mp4", MIME_TYPE));
            assertNull(directory.findFile("../outside.mp4"));
            assertArrayEquals(OUTSIDE_CONTENT, Files.readAllBytes(outside));

            // the same folder still creates an ordinary name
            assertNotNull(directory.createFile("inside.mp4", MIME_TYPE));
            assertTrue(new File(downloads, "inside.mp4").isFile());
        }
    }

    private Path writeOutsideFile() throws IOException {
        final Path outside = folder.getRoot().toPath().resolve("outside.mp4");
        Files.write(outside, OUTSIDE_CONTENT);
        return outside;
    }
}
