/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A published intent leaves live data untouched. Startup either commits the new pair or restores
 * the saved pair before returning to consumers. An activation record without completion requires
 * rollback.
 */
public final class RestoreTransaction {
    private static final String[] SUFFIXES = {"", "-journal", "-wal", "-shm"};
    private static final int VERSION = 1;
    private static final int APPLIED = 1;
    private static final int ROLLED_BACK = 2;
    private static final int REJECTED = 3;

    public interface Preferences {
        Map<String, ?> read() throws IOException;
        void replace(Map<String, Object> values) throws IOException;
    }

    @FunctionalInterface
    public interface Validator {
        void validate(Path database) throws IOException;
    }

    public enum Step {
        BEFORE_PUBLISH, AFTER_PUBLISH, BEFORE_VALIDATION, AFTER_VALIDATION,
        BEFORE_SNAPSHOT, AFTER_SNAPSHOT, AFTER_ACTIVATION,
        BEFORE_REPLACE, AFTER_REPLACE, BEFORE_REMOVE_SIDECAR, AFTER_REMOVE_SIDECAR,
        BEFORE_PREFERENCES, AFTER_PREFERENCES, AFTER_COMMIT, AFTER_ROLLBACK,
        BEFORE_RETIRE, AFTER_RETIRE
    }

    /** Filesystem effects and interruption checkpoints; production has no injected faults. */
    public static class FileSystem {
        public void checkpoint(final Step step, final Path path) throws IOException { }

        public void syncDirectory(final Path path) throws IOException {
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    public static final class StagedException extends IOException {
        StagedException(final IOException cause) {
            super("Restore was staged; restart to finish restoring", cause);
        }
    }

    public static final class RecoveryException extends IOException {
        RecoveryException(final Throwable cause) {
            super("Restore recovery is incomplete; startup stopped to protect saved data", cause);
        }
    }

    private final Path database;
    private final Path pending;
    private final Preferences preferences;
    private final Validator validator;
    private final FileSystem io;

    public RestoreTransaction(final Path database, final Preferences preferences,
                              final Validator validator, final FileSystem io) {
        this.database = database.toAbsolutePath();
        pending = transactionPath(this.database);
        this.preferences = preferences;
        this.validator = validator;
        this.io = io;
    }

    public static Path transactionPath(final Path database) {
        return database.resolveSibling(database.getFileName() + ".restore-transaction");
    }

    private static Path legacyPath(final Path database) {
        return database.resolveSibling(database.getFileName() + ".restore");
    }

    public static Path pendingDatabase(final Path database) {
        final Path transaction = transactionPath(database);
        return Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)
                ? transaction.resolve("database") : legacyPath(database);
    }

    public static boolean hasPending(final Path database) {
        final Path transaction = transactionPath(database);
        if (Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(transaction, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            // Completed records cannot be replayed, even when retirement needs another attempt.
            if (!Files.exists(transaction.resolve("complete"))) {
                return true;
            }
            try {
                new RestoreTransaction(database, null, null, new FileSystem()).completed();
                return false;
            } catch (final IOException invalid) {
                return true;
            }
        }
        return Files.exists(legacyPath(database), LinkOption.NOFOLLOW_LINKS);
    }

    // The file lock also serializes participating secondary processes.
    public void publish(final Path prepared, final Map<String, ?> values,
                        final boolean replaceAll) throws IOException {
        synchronized (RestoreTransaction.class) {
            Files.createDirectories(database.getParent());
            try (var channel = lockChannel(); var ignored = channel.lock()) {
                cleanupRetired();
                if (Files.isDirectory(pending, LinkOption.NOFOLLOW_LINKS)
                        && Files.exists(pending.resolve("complete"))) {
                    io.syncDirectory(pending);
                    retire(completed() != APPLIED);
                }
                if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(legacyPath(database), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("A restored database is awaiting restart or cleanup");
                }
                stage(prepared, values, replaceAll, false);
            }
        }
    }

    private FileChannel lockChannel() throws IOException {
        return FileChannel.open(database.resolveSibling(database.getFileName() + ".restore-lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private void stage(final Path prepared, final Map<String, ?> values,
                       final boolean replaceAll, final boolean legacy) throws IOException {
        final Path staging = Files.createTempDirectory(database.getParent(), "backup-transaction-");
        boolean published = false;
        try {
            copyForced(prepared, staging.resolve("database"));
            write(staging.resolve("intent"), RestorePreferences.encode(values));
            write(staging.resolve("request"), request(replaceAll, legacy,
                    digest(staging.resolve("database")), digest(staging.resolve("intent"))));
            io.syncDirectory(staging);
            if (!legacy) {
                Files.delete(prepared);
            }
            io.checkpoint(Step.BEFORE_PUBLISH, pending);
            Files.move(staging, pending, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            io.syncDirectory(database.getParent());
            io.checkpoint(Step.AFTER_PUBLISH, pending);
        } catch (final IOException error) {
            if (published) {
                throw new StagedException(error);
            }
            throw error;
        } finally {
            if (!published) {
                deleteTree(staging);
            }
        }
    }

    /**
     * Finishes or aborts queued work before consumers open the database.
     * @return a handled failure to report after startup, or null on success/no pending work
     * @throws IOException if startup cannot establish a consistent pair
     */
    public IOException recover() throws IOException {
        synchronized (RestoreTransaction.class) {
            if (!Files.exists(database.getParent())) {
                return null;
            }
            try (var channel = lockChannel(); var ignored = channel.lock()) {
                cleanupRetired();
                if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(legacyPath(database), LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }
                if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                    // Old versions published only a file. Preserve current preferences because
                    // no pre-import preference snapshot exists for those historical restores.
                    stage(legacyPath(database), Map.of(), false, true);
                }
                if (!Files.isDirectory(pending, LinkOption.NOFOLLOW_LINKS)) {
                    throw new RecoveryException(
                            new IOException("Invalid restore transaction path"));
                }
                io.syncDirectory(database.getParent());
                if (Files.exists(pending.resolve("complete"))) {
                    io.syncDirectory(pending);
                    final int outcome = completed();
                    return finish(outcome != APPLIED, null);
                }
                if (Files.exists(pending.resolve("activation"))) {
                    try {
                        rollback();
                    } catch (final IOException | RuntimeException recovery) {
                        throw new RecoveryException(recovery);
                    }
                    return finish(true,
                            new IOException("Interrupted restore recovered the previous data"));
                }
                try {
                    activate();
                } catch (final RuntimeException error) {
                    return activationFailed(new IOException("Restore activation failed", error));
                } catch (final IOException error) {
                    return activationFailed(error);
                }
                return finish(false, null);
            }
        }
    }

    private IOException activationFailed(final IOException error) throws IOException {
        if (Files.exists(pending.resolve("complete"))) {
            try {
                io.syncDirectory(pending);
                return finish(completed() != APPLIED, error);
            } catch (final IOException invalid) {
                throw new RecoveryException(invalid);
            }
        }
        if (Files.exists(pending.resolve("activation"))) {
            try {
                rollback();
            } catch (final IOException | RuntimeException recovery) {
                recovery.addSuppressed(error);
                throw new RecoveryException(recovery);
            }
        } else {
            complete(REJECTED);
        }
        return finish(true,
                new IOException("Restore failed; previous data was preserved", error));
    }

    private void activate() throws IOException {
        final Request request = readRequest();
        verify(pending.resolve("database"), request.databaseHash);
        verify(pending.resolve("intent"), request.intentHash);
        if (!request.legacy && Files.exists(legacyPath(database))) {
            throw new IOException("A competing legacy restore is awaiting restart");
        }
        final Map<String, Object> intent = RestorePreferences.decode(
                read(pending.resolve("intent")));
        final Path candidate = pending.resolve("validated");
        // Unique validation paths prevent replaying a killed migration's WAL over a fresh copy.
        final Path working = Files.createTempDirectory(pending, "validation-");
        try {
            final Path workingDatabase = working.resolve("database");
            copyForced(pending.resolve("database"), workingDatabase);
            io.checkpoint(Step.BEFORE_VALIDATION, workingDatabase);
            validator.validate(workingDatabase);
            force(workingDatabase);
            Files.move(workingDatabase, candidate,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            io.syncDirectory(pending);
            io.checkpoint(Step.AFTER_VALIDATION, candidate);
        } finally {
            deleteTree(working);
        }
        final Map<String, Object> oldValues = RestorePreferences.copy(preferences.read());
        final Map<String, Object> target = request.replaceAll
                ? new HashMap<>() : RestorePreferences.copy(oldValues);
        target.putAll(intent);
        write(pending.resolve("previous.preferences"), RestorePreferences.encode(oldValues));
        write(pending.resolve("target.preferences"), RestorePreferences.encode(target));
        final byte[][] oldHashes = new byte[SUFFIXES.length][];
        for (int i = 0; i < SUFFIXES.length; i++) {
            final Path live = live(i);
            io.checkpoint(Step.BEFORE_SNAPSHOT, live);
            if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                regular(live);
                copyForced(live, previous(i));
                oldHashes[i] = digest(previous(i));
            }
            io.checkpoint(Step.AFTER_SNAPSHOT, live);
        }
        write(pending.resolve("activation"), activation(oldHashes, digest(candidate),
                digest(pending.resolve("previous.preferences")),
                digest(pending.resolve("target.preferences"))));
        io.checkpoint(Step.AFTER_ACTIVATION, pending);
        final Activation active = readActivation();
        verify(candidate, active.databaseHash);
        replace(candidate, database);
        for (int i = 1; i < SUFFIXES.length; i++) {
            io.checkpoint(Step.BEFORE_REMOVE_SIDECAR, live(i));
            Files.deleteIfExists(live(i));
            io.checkpoint(Step.AFTER_REMOVE_SIDECAR, live(i));
        }
        io.syncDirectory(database.getParent());
        io.checkpoint(Step.BEFORE_PREFERENCES, pending);
        verify(pending.resolve("target.preferences"), active.targetPreferences);
        preferences.replace(RestorePreferences.decode(read(pending.resolve("target.preferences"))));
        io.checkpoint(Step.AFTER_PREFERENCES, pending);
        complete(APPLIED);
        io.checkpoint(Step.AFTER_COMMIT, pending);
    }

    private void rollback() throws IOException {
        final Activation active = readActivation();
        verify(pending.resolve("previous.preferences"), active.previousPreferences);
        final Map<String, Object> old = RestorePreferences.decode(
                read(pending.resolve("previous.preferences")));
        // Verify the entire undo snapshot before changing even one live file.
        for (int i = 0; i < SUFFIXES.length; i++) {
            if (active.previous[i] != null) {
                verify(previous(i), active.previous[i]);
            }
        }
        for (int i = 0; i < SUFFIXES.length; i++) {
            if (active.previous[i] == null) {
                Files.deleteIfExists(live(i));
            } else {
                replace(previous(i), live(i));
            }
        }
        io.syncDirectory(database.getParent());
        preferences.replace(RestorePreferences.copy(old));
        complete(ROLLED_BACK);
        io.checkpoint(Step.AFTER_ROLLBACK, pending);
    }

    private Path live(final int index) {
        return database.resolveSibling(database.getFileName() + SUFFIXES[index]);
    }

    private Path previous(final int index) {
        return pending.resolve("previous" + SUFFIXES[index]);
    }

    private void replace(final Path source, final Path destination) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            regular(destination);
        }
        final Path temporary = Files.createTempFile(database.getParent(),
                "backup-install-", ".tmp");
        try {
            copyForced(source, temporary);
            io.checkpoint(Step.BEFORE_REPLACE, destination);
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            io.checkpoint(Step.AFTER_REPLACE, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void complete(final int outcome) throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.writeInt(outcome);
            final Path active = pending.resolve("activation");
            out.writeBoolean(Files.exists(active));
            if (Files.exists(active)) {
                out.write(digest(active));
            }
        }
        write(pending.resolve("complete"), bytes.toByteArray());
    }

    private int completed() throws IOException {
        return completed(pending);
    }

    private static int completed(final Path directory) throws IOException {
        try (var in = input(directory.resolve("complete"))) {
            version(in);
            final int outcome = in.readInt();
            if (outcome < APPLIED || outcome > REJECTED) {
                throw new IOException("Unknown restore outcome");
            }
            if (in.readBoolean()) {
                verify(directory.resolve("activation"), hash(in));
            } else if (outcome != REJECTED) {
                throw new IOException("Missing restore activation record");
            }
            end(in);
            return outcome;
        }
    }

    private IOException finish(final boolean rejected, final IOException failure) {
        try {
            retire(rejected);
        } catch (final IOException cleanup) {
            if (failure != null) {
                failure.addSuppressed(cleanup);
                return failure;
            }
            return new IOException("Restore completed; transaction cleanup is pending", cleanup);
        }
        return failure;
    }

    private void retire(final boolean rejected) throws IOException {
        final Request request;
        try {
            request = readRequest();
        } catch (final IOException damaged) {
            // A rejected, never-activated request may itself be malformed.
            if (!rejected) {
                throw damaged;
            }
            retireDirectory(true);
            return;
        }
        if (request.legacy && Files.exists(legacyPath(database))) {
            verify(legacyPath(database), request.databaseHash);
            Files.move(legacyPath(database), database.resolveSibling(database.getFileName()
                    + ".restore-rejected-" + UUID.randomUUID()), StandardCopyOption.ATOMIC_MOVE);
            io.syncDirectory(database.getParent());
        }
        retireDirectory(rejected);
    }

    private void retireDirectory(final boolean rejected) throws IOException {
        io.checkpoint(Step.BEFORE_RETIRE, pending);
        final Path retired = database.resolveSibling(database.getFileName()
                + (rejected ? ".restore-rejected-" : ".restore-cleanup-") + UUID.randomUUID());
        Files.move(pending, retired, StandardCopyOption.ATOMIC_MOVE);
        io.syncDirectory(database.getParent());
        io.checkpoint(Step.AFTER_RETIRE, retired);
        if (!rejected) {
            try {
                deleteTree(retired);
            } catch (final IOException ignored) {
                // Completed data must not be replayed because garbage collection failed.
            }
        }
    }

    private void cleanupRetired() throws IOException {
        final String prefix = database.getFileName() + ".restore-cleanup-";
        try (var entries = Files.list(database.getParent())) {
            for (final Path path : entries.collect(Collectors.toList())) {
                final String name = path.getFileName().toString();
                if (name.startsWith(prefix)) {
                    try {
                        UUID.fromString(name.substring(prefix.length()));
                        if (completed(path) == APPLIED) {
                            io.syncDirectory(database.getParent());
                            deleteTree(path);
                        }
                    } catch (final IllegalArgumentException | IOException ignored) {
                        // Garbage collection never authorizes replay of a completed restore.
                    }
                }
            }
        }
    }

    private void write(final Path path, final byte[] content) throws IOException {
        final Path temporary = Files.createTempFile(path.getParent(), "record-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                final ByteBuffer buffer = ByteBuffer.allocate(content.length + 32);
                buffer.put(content).put(digest(content));
                ((java.nio.Buffer) buffer).flip();
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, path,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            io.syncDirectory(path.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] read(final Path path) throws IOException {
        regular(path);
        final byte[] all = Files.readAllBytes(path);
        if (all.length < 32) {
            throw new IOException("Truncated restore record");
        }
        final byte[] body = Arrays.copyOf(all, all.length - 32);
        if (!MessageDigest.isEqual(digest(body),
                Arrays.copyOfRange(all, body.length, all.length))) {
            throw new IOException("Corrupt restore record");
        }
        return body;
    }

    private static DataInputStream input(final Path path) throws IOException {
        return new DataInputStream(new ByteArrayInputStream(read(path)));
    }

    private static byte[] request(final boolean replaceAll, final boolean legacy,
                                  final byte[] dbHash, final byte[] preferencesHash)
            throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.writeBoolean(replaceAll);
            out.writeBoolean(legacy);
            out.write(dbHash);
            out.write(preferencesHash);
        }
        return bytes.toByteArray();
    }

    private Request readRequest() throws IOException {
        try (var in = input(pending.resolve("request"))) {
            version(in);
            final Request result = new Request(in.readBoolean(), in.readBoolean(),
                    hash(in), hash(in));
            end(in);
            return result;
        }
    }

    private static byte[] activation(final byte[][] previous, final byte[] databaseHash,
                                     final byte[] oldPreferences, final byte[] targetPreferences)
            throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.write(databaseHash);
            out.write(oldPreferences);
            out.write(targetPreferences);
            for (final byte[] hash : previous) {
                out.writeBoolean(hash != null);
                if (hash != null) {
                    out.write(hash);
                }
            }
        }
        return bytes.toByteArray();
    }

    private Activation readActivation() throws IOException {
        try (var in = input(pending.resolve("activation"))) {
            version(in);
            final byte[] databaseHash = hash(in);
            final byte[] oldPreferences = hash(in);
            final byte[] targetPreferences = hash(in);
            final byte[][] previous = new byte[SUFFIXES.length][];
            for (int i = 0; i < previous.length; i++) {
                previous[i] = in.readBoolean() ? hash(in) : null;
            }
            end(in);
            return new Activation(previous, databaseHash, oldPreferences, targetPreferences);
        }
    }

    private static byte[] hash(final DataInputStream in) throws IOException {
        final byte[] hash = new byte[32];
        in.readFully(hash);
        return hash;
    }

    private static void version(final DataInputStream in) throws IOException {
        if (in.readInt() != VERSION) {
            throw new IOException("Unknown restore transaction version");
        }
    }

    private static void end(final DataInputStream in) throws IOException {
        if (in.available() != 0) {
            throw new IOException("Trailing restore transaction data");
        }
    }

    private static byte[] digest(final byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (final NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] digest(final Path path) throws IOException {
        regular(path);
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return digest.digest();
        } catch (final NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void verify(final Path path, final byte[] expected) throws IOException {
        if (!MessageDigest.isEqual(digest(path), expected)) {
            throw new IOException("Restore snapshot checksum mismatch: " + path.getFileName());
        }
    }

    private static void regular(final Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Restore path is not a regular file: " + path.getFileName());
        }
    }

    private static void copyForced(final Path from, final Path to) throws IOException {
        regular(from);
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        force(to);
    }

    private static void force(final Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void deleteTree(final Path path) throws IOException {
        try (var paths = Files.walk(path)) {
            for (final Path item : paths.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList())) {
                Files.deleteIfExists(item);
            }
        }
    }

    private record Request(boolean replaceAll, boolean legacy,
                           byte[] databaseHash, byte[] intentHash) { }

    private record Activation(byte[][] previous, byte[] databaseHash,
                              byte[] previousPreferences, byte[] targetPreferences) { }
}
