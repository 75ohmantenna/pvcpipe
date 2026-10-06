package org.schabi.newpipe.settings.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Installs an accepted restore at process startup, before any database connection is opened. */
public final class PendingDatabaseRestore {
    private PendingDatabaseRestore() { }

    public static Path temporaryPath(final Path database) {
        return database.resolveSibling(database.getFileName() + ".restore.tmp");
    }

    private static Path pendingPath(final Path database) {
        return database.resolveSibling(database.getFileName() + ".restore");
    }

    public static void commit(final Path database) throws IOException {
        Files.move(temporaryPath(database), pendingPath(database),
                StandardCopyOption.REPLACE_EXISTING);
    }

    public static void install(final Path database) throws IOException {
        final Path pending = pendingPath(database);
        if (!Files.exists(pending)) {
            return;
        }
        // Retain the pending file until installation succeeds, so interrupted startup retries.
        for (final String suffix : new String[]{"-journal", "-wal", "-shm"}) {
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + suffix));
        }
        Files.move(pending, database, StandardCopyOption.REPLACE_EXISTING);
    }
}
