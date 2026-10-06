package org.schabi.newpipe.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.settings.export.PendingDatabaseRestore;

import java.nio.file.Files;
import java.nio.file.Path;

public class PendingDatabaseRestoreTest {
    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void stagedRestoreLeavesLiveDatabaseAndWalUntouchedUntilStartup() throws Exception {
        final Path database = directory.newFile("newpipe.db").toPath();
        final Path wal = database.resolveSibling("newpipe.db-wal");
        Files.writeString(database, "live database");
        Files.writeString(wal, "live WAL");
        Files.writeString(PendingDatabaseRestore.temporaryPath(database), "restored database");
        PendingDatabaseRestore.commit(database);
        assertEquals("live database", Files.readString(database));
        assertTrue(Files.exists(wal));
        PendingDatabaseRestore.install(database);
        assertEquals("restored database", Files.readString(database));
        assertFalse(Files.exists(wal));
        PendingDatabaseRestore.install(database);
        assertEquals("restored database", Files.readString(database));
    }

    @Test
    public void incompleteStagingDoesNotReplaceTheDatabase() throws Exception {
        final Path database = directory.newFile("newpipe.db").toPath();
        Files.writeString(database, "live database");
        Files.writeString(PendingDatabaseRestore.temporaryPath(database), "incomplete");
        PendingDatabaseRestore.install(database);
        assertEquals("live database", Files.readString(database));
    }
}
