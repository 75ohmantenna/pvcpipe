package org.schabi.newpipe.settings.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Installs an accepted restore at process startup, before any database connection is opened. */
public final class PendingDatabaseRestore {
    private PendingDatabaseRestore() { }

    private static Path pendingPath(final Path database) {
        return database.resolveSibling(database.getFileName() + ".restore");
    }

    public static boolean hasPending(final Path database) {
        return Files.exists(pendingPath(database));
    }

    public static void publish(final Path database, final Path prepared) throws IOException {
        // Do not replace a previously accepted restore, even if one appears during preparation.
        Files.move(prepared, pendingPath(database));
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
