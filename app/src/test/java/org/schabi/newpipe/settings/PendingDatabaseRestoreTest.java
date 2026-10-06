package org.schabi.newpipe.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
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
        final Path prepared = directory.newFile("prepared.tmp").toPath();
        Files.writeString(prepared, "restored database");
        PendingDatabaseRestore.publish(database, prepared);
        assertEquals("live database", Files.readString(database));
        assertTrue(Files.exists(wal));
        PendingDatabaseRestore.install(database);
        assertEquals("restored database", Files.readString(database));
        assertFalse(Files.exists(wal));
        PendingDatabaseRestore.install(database);
        assertEquals("restored database", Files.readString(database));
    }

    @Test
    public void failedActivationRetainsPendingDatabaseForRetry() throws Exception {
        final Path database = directory.newFolder("newpipe.db").toPath();
        final Path blocker = database.resolve("blocker");
        Files.writeString(blocker, "prevent directory replacement");
        final Path prepared = directory.newFile("prepared.tmp").toPath();
        Files.writeString(prepared, "restored database");
        PendingDatabaseRestore.publish(database, prepared);
        assertThrows(java.io.IOException.class, () -> PendingDatabaseRestore.install(database));
        assertTrue(PendingDatabaseRestore.hasPending(database));
        Files.delete(blocker);
        Files.delete(database);
        PendingDatabaseRestore.install(database);
        assertEquals("restored database", Files.readString(database));
        assertFalse(PendingDatabaseRestore.hasPending(database));
    }

    @Test
    public void incompleteStagingDoesNotReplaceTheDatabase() throws Exception {
        final Path database = directory.newFile("newpipe.db").toPath();
        Files.writeString(database, "live database");
        Files.writeString(directory.newFile("prepared.tmp").toPath(), "incomplete");
        PendingDatabaseRestore.install(database);
        assertEquals("live database", Files.readString(database));
    }
}
