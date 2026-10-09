package us.shandian.giga.ui.adapter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.core.content.FileProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MissionFileSharingTest {
    @Test
    public void directMissionSharesOnlyRegisteredFileAndOpensReadOnly() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Path directory = Files.createTempDirectory(context.getCacheDir().toPath(),
                "mission-share-");
        final Path media = directory.resolve("mission.mp4");
        final Path unrelated = directory.resolve("private.mp4");
        try {
            Files.writeString(media, "mission bytes");
            Files.writeString(unrelated, "unrelated bytes");
            final StoredFileHelper storage = new StoredFileHelper(context,
                    Uri.fromFile(media.toFile()), "video/mp4");
            final Uri shared = MissionAdapter.resolveShareableUri(context, storage, "video/mp4");
            assertEquals("content", shared.getScheme());
            assertEquals(context.getPackageName() + ".mission-files", shared.getAuthority());
            assertFalse(shared.toString().contains(media.getFileName().toString()));
            assertEquals("video/mp4", context.getContentResolver().getType(shared));
            try (var input = new DataInputStream(context.getContentResolver()
                    .openInputStream(shared))) {
                final byte[] expected = "mission bytes".getBytes(StandardCharsets.UTF_8);
                final byte[] actual = new byte[expected.length];
                input.readFully(actual);
                assertArrayEquals(expected, actual);
                assertEquals(-1, input.read());
            }
            try (Cursor metadata = context.getContentResolver().query(shared,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null)) {
                assertTrue(metadata.moveToFirst());
                assertEquals("mission.mp4", metadata.getString(0));
                assertEquals(Files.size(media), metadata.getLong(1));
            }
            assertThrows(FileNotFoundException.class,
                    () -> context.getContentResolver().openFileDescriptor(shared, "w"));
            assertThrows(FileNotFoundException.class,
                    () -> context.getContentResolver().openFileDescriptor(
                            shared.buildUpon().appendPath(unrelated.getFileName().toString())
                                    .build(), "r"));
            assertThrows(FileNotFoundException.class,
                    () -> context.getContentResolver().openFileDescriptor(
                            shared.buildUpon().path("/" + unrelated.getFileName()).build(),
                            "r"));
            assertThrows(FileNotFoundException.class,
                    () -> context.getContentResolver().openFileDescriptor(
                            shared.buildUpon().path("/not-registered").build(), "r"));
            assertThrows(IllegalArgumentException.class,
                    () -> FileProvider.getUriForFile(context,
                            BuildConfig.APPLICATION_ID + ".provider", unrelated.toFile()));
            assertEquals(0, MissionAdapter.createExternalViewIntent(shared, "video/mp4")
                    .getFlags() & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            assertNotEquals(0, MissionAdapter.createExternalViewIntent(shared, "video/mp4")
                    .getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            final Intent send = MissionAdapter.createShareIntent(context, shared, "video/mp4",
                    "mission.mp4");
            assertEquals(shared, send.getParcelableExtra(Intent.EXTRA_STREAM));
            assertEquals(shared, send.getClipData().getItemAt(0).getUri());
            assertEquals(1, send.getClipData().getItemCount());
            assertNotEquals(0, send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            assertEquals(0, send.getFlags() & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            Files.delete(media);
            assertThrows(FileNotFoundException.class,
                    () -> context.getContentResolver().openFileDescriptor(shared, "r"));
        } finally {
            Files.deleteIfExists(media);
            Files.deleteIfExists(unrelated);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void directMissionOnAvailableExternalVolumesUsesSameExactProvider() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (final java.io.File volume : context.getExternalCacheDirs()) {
            if (volume == null) {
                continue;
            }
            final Path media = Files.createTempFile(volume.toPath(), "mission-share-", ".mp4");
            try {
                Files.writeString(media, "external volume");
                final Uri shared = MissionAdapter.resolveShareableUri(context,
                        new StoredFileHelper(context, Uri.fromFile(media.toFile()), "video/mp4"),
                        "video/mp4");
                assertEquals(context.getPackageName() + ".mission-files", shared.getAuthority());
                try (var input = new DataInputStream(context.getContentResolver()
                        .openInputStream(shared))) {
                    final byte[] expected = "external volume".getBytes(StandardCharsets.UTF_8);
                    final byte[] actual = new byte[expected.length];
                    input.readFully(actual);
                    assertArrayEquals(expected, actual);
                    assertEquals(-1, input.read());
                }
            } finally {
                Files.deleteIfExists(media);
            }
        }
    }

    @Test
    public void safMissionKeepsItsDocumentUri() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Uri document = Uri.parse("content://example.documents/document/mission-123");
        final StoredFileHelper storage = new StoredFileHelper(context, document, "video/mp4");
        assertEquals(document, MissionAdapter.resolveShareableUri(context, storage, "video/mp4"));
        final Intent send = MissionAdapter.createShareIntent(context, document, "video/mp4",
                "mission.mp4");
        assertEquals(document, send.getClipData().getItemAt(0).getUri());
        assertEquals(0, send.getFlags() & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }
}
