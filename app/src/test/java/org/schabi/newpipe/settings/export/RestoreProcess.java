/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** A fresh JVM exits without finally blocks at a selected, real filesystem transition. */
public final class RestoreProcess {
    private RestoreProcess() { }

    static final class DiskPreferences implements RestoreTransaction.Preferences {
        private final Path file;

        DiskPreferences(final Path directory) {
            file = directory.resolve("live.preferences");
        }

        @Override
        public Map<String, Object> read() throws IOException {
            return RestorePreferences.decode(Files.readAllBytes(file));
        }

        @Override
        public void replace(final Map<String, Object> values) throws IOException {
            final Path temporary = Files.createTempFile(file.getParent(), "preferences-", ".tmp");
            try {
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    final ByteBuffer buffer = ByteBuffer.wrap(RestorePreferences.encode(values));
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                new RestoreTransaction.FileSystem().syncDirectory(file.getParent());
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        final Path directory = Path.of(args[0]);
        final String action = args[1];
        final String step = args[2];
        final int occurrence = Integer.parseInt(args[3]);
        final var io = new RestoreTransaction.FileSystem() {
            private int seen;

            @Override
            public void checkpoint(final RestoreTransaction.Step reached, final Path path) {
                if (reached.name().equals(step) && ++seen == occurrence) {
                    Runtime.getRuntime().halt(91);
                }
            }
        };
        final var transaction = new RestoreTransaction(directory.resolve("newpipe.db"),
                new DiskPreferences(directory), path -> {
                    if (!Files.readString(path).equals("new DB")) {
                        throw new IOException("Synthetic candidate rejected");
                    }
                }, io);
        try {
            if (action.equals("publish")) {
                transaction.publish(directory.resolve("candidate"), Map.of("version", "new"), true);
            } else {
                final IOException result = transaction.recover();
                Files.writeString(directory.resolve("result"), result == null ? "OK" : "HANDLED");
            }
        } catch (final RestoreTransaction.RecoveryException incomplete) {
            System.exit(72);
        }
    }
}
