package org.schabi.newpipe.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.settings.export.BackupFileLocator;
import org.schabi.newpipe.settings.export.ImportExportManager;
import org.schabi.newpipe.settings.export.PendingDatabaseRestore;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.reactivex.rxjava3.schedulers.Schedulers;

/** Uses isolated files and real Android preferences; never invokes application restart. */
public class BackupRestoreIntegrationTest {
    private Context context;
    private Path directory;
    private Path database;
    private String preferenceName;
    private SharedPreferences preferences;
    private BackupRestore backups;
    private int restarts;

    @Before
    public void setup() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "backup-test-");
        database = directory.resolve("newpipe.db");
        Files.writeString(database, "live database");
        Files.writeString(directory.resolve("newpipe.db-wal"), "live WAL");
        preferenceName = "backup-test-" + UUID.randomUUID();
        preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().putString("original", "settings").commit());
        final Context filesContext = new ContextWrapper(context) {
            @Override
            public File getDatabasePath(final String name) {
                return directory.resolve(name).toFile();
            }
        };
        final BackupRestore.Platform platform = new BackupRestore.Platform() {
            @Override
            public StoredFileHelper document(final Uri uri) {
                return new StoredFileHelper(context, uri, "application/zip");
            }
            @Override
            public void checkpoint() { }
            @Override
            public void normalizeImportedPreferences(final Map<String, Object> values) { }
            @Override
            public void restart() {
                restarts++;
            }
            @Override
            public void reportRestoreFailure(final Throwable error) { }
        };
        backups = new BackupRestore(new BackupOperations(Schedulers.trampoline()),
                new ImportExportManager(new BackupFileLocator(filesContext)), preferences,
                "location", platform, Schedulers.trampoline());
    }

    @After
    public void cleanup() throws IOException {
        context.deleteSharedPreferences(preferenceName);
        try (var paths = Files.walk(directory)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Uri archive(final boolean includeDatabase) throws IOException {
        final Path archive = directory.resolve("source.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            if (includeDatabase) {
                zip.putNextEntry(new ZipEntry("newpipe.db"));
                zip.write("restored database".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("preferences.json"));
            zip.write("{\"restored\":true}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Uri.fromFile(archive.toFile());
    }

    @Test
    public void realPreferencesAndFilesCommitBeforeStartupActivation() throws IOException {
        final Uri uri = archive(true);
        final var inspection = backups.inspect(uri).blockingGet();
        backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
                .test().assertComplete();
        assertFalse(preferences.contains("original"));
        assertTrue(preferences.getBoolean("restored", false));
        assertEquals(uri.toString(), preferences.getString("location", null));
        assertEquals(1, restarts);
        assertEquals("live database", Files.readString(database));
        assertTrue(Files.exists(directory.resolve("newpipe.db-wal")));
        PendingDatabaseRestore.install(database);
        assertEquals("restored database", Files.readString(database));
        assertFalse(Files.exists(directory.resolve("newpipe.db-wal")));
    }

    @Test
    public void missingDatabaseLeavesRealPreferencesAndFilesUnchanged() throws IOException {
        final var inspection = backups.inspect(archive(false)).blockingGet();
        backups.restore(inspection, BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS)
                .test().assertError(IOException.class);
        assertEquals("settings", preferences.getString("original", null));
        assertFalse(preferences.contains("restored"));
        assertEquals("live database", Files.readString(database));
        assertTrue(Files.exists(directory.resolve("newpipe.db-wal")));
        assertFalse(PendingDatabaseRestore.hasPending(database));
        assertEquals(0, restarts);
    }
}
