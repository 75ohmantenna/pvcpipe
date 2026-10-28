/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Real files and fresh JVMs with typed disk preferences and a controlled database validator. */
public class RestoreTransactionTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String[] SIDECARS = {"-journal", "-wal", "-shm"};

    private Path fixture() throws IOException {
        final Path directory = temporary.newFolder().toPath();
        Files.writeString(directory.resolve("newpipe.db"), "old DB");
        Files.writeString(directory.resolve("candidate"), "new DB");
        for (final String suffix : SIDECARS) {
            Files.writeString(directory.resolve("newpipe.db" + suffix), "old " + suffix);
        }
        new RestoreProcess.DiskPreferences(directory)
                .replace(new HashMap<>(Map.of("version", "old")));
        return directory;
    }

    private RestoreTransaction transaction(final Path directory,
                                            final RestoreTransaction.FileSystem io) {
        return new RestoreTransaction(directory.resolve("newpipe.db"),
                new RestoreProcess.DiskPreferences(directory), path -> {
                    if (!Files.readString(path).equals("new DB")) {
                        throw new IOException("Synthetic invalid database");
                    }
                }, io);
    }

    private RestoreTransaction transaction(final Path directory) {
        return transaction(directory, new RestoreTransaction.FileSystem());
    }

    private void publish(final Path directory) throws IOException {
        transaction(directory).publish(directory.resolve("candidate"),
                Map.of("version", "new"), true);
    }

    private void oldPair(final Path directory) throws IOException {
        assertEquals("old DB", Files.readString(directory.resolve("newpipe.db")));
        assertEquals(Map.of("version", "old"),
                new RestoreProcess.DiskPreferences(directory).read());
        for (final String suffix : SIDECARS) {
            assertEquals("old " + suffix,
                    Files.readString(directory.resolve("newpipe.db" + suffix)));
        }
    }

    private void newPair(final Path directory) throws IOException {
        assertEquals("new DB", Files.readString(directory.resolve("newpipe.db")));
        assertEquals(Map.of("version", "new"),
                new RestoreProcess.DiskPreferences(directory).read());
        for (final String suffix : SIDECARS) {
            assertFalse(Files.exists(directory.resolve("newpipe.db" + suffix)));
        }
    }

    private int child(final Path directory, final String mode,
                      final RestoreTransaction.Step step, final int occurrence) throws Exception {
        return child(directory, mode, step.name(), occurrence);
    }

    private int child(final Path directory, final String mode,
                      final String step, final int occurrence) throws Exception {
        final Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"),
                "bin", "java").toString(), "-cp", String.join(System.getProperty("path.separator"),
                Path.of(RestoreProcess.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI()).toString(),
                Path.of(RestoreTransaction.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI()).toString()), RestoreProcess.class.getName(),
                directory.toString(), mode, step, Integer.toString(occurrence))
                .redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile())
                .start();
        assertTrue("Child JVM timed out", process.waitFor(30, TimeUnit.SECONDS));
        final int code = process.exitValue();
        if (code != 0 && code != 91 && code != 72) {
            throw new AssertionError("Child exit " + code + ": "
                    + Files.readString(directory.resolve("child.log")));
        }
        return code;
    }

    @Test
    public void publicationDoesNotChangeLiveData() throws Exception {
        final Path directory = fixture();
        publish(directory);
        oldPair(directory);
        assertTrue(RestoreTransaction.hasPending(directory.resolve("newpipe.db")));
        assertNull(transaction(directory).recover());
        newPair(directory);
        assertFalse(RestoreTransaction.hasPending(directory.resolve("newpipe.db")));
    }

    @Test
    public void killedPublisherLeavesEitherNoRequestOrAnIntactRequest() throws Exception {
        for (final var step : new RestoreTransaction.Step[]{
                RestoreTransaction.Step.BEFORE_PUBLISH, RestoreTransaction.Step.AFTER_PUBLISH}) {
            final Path directory = fixture();
            assertEquals(91, child(directory, "publish", step, 1));
            oldPair(directory);
            assertEquals(0, child(directory, "recover", "NONE", 1));
            if (step == RestoreTransaction.Step.AFTER_PUBLISH) {
                newPair(directory);
            } else {
                oldPair(directory);
            }
        }
    }

    @Test
    public void killedPreActivationCanFinishFromTheImmutableInput() throws Exception {
        for (final var step : new RestoreTransaction.Step[]{
                RestoreTransaction.Step.BEFORE_VALIDATION, RestoreTransaction.Step.AFTER_VALIDATION,
                RestoreTransaction.Step.BEFORE_SNAPSHOT, RestoreTransaction.Step.AFTER_SNAPSHOT}) {
            final Path directory = fixture();
            publish(directory);
            assertEquals(91, child(directory, "recover", step, 1));
            oldPair(directory);
            assertEquals(0, child(directory, "recover", "NONE", 1));
            newPair(directory);
        }
    }

    @Test
    public void killedUncommittedActivationRollsBackInAFreshJvm() throws Exception {
        for (final var step : new RestoreTransaction.Step[]{
                RestoreTransaction.Step.AFTER_ACTIVATION, RestoreTransaction.Step.BEFORE_REPLACE,
                RestoreTransaction.Step.AFTER_REPLACE,
                RestoreTransaction.Step.BEFORE_REMOVE_SIDECAR,
                RestoreTransaction.Step.AFTER_REMOVE_SIDECAR,
                RestoreTransaction.Step.BEFORE_PREFERENCES,
                RestoreTransaction.Step.AFTER_PREFERENCES}) {
            final int count = step == RestoreTransaction.Step.AFTER_REMOVE_SIDECAR ? 3 : 1;
            for (int occurrence = 1; occurrence <= count; occurrence++) {
                final Path directory = fixture();
                publish(directory);
                assertEquals(91, child(directory, "recover", step, occurrence));
                assertEquals(0, child(directory, "recover", "NONE", 1));
                oldPair(directory);
                assertFalse(RestoreTransaction.hasPending(directory.resolve("newpipe.db")));
            }
        }
    }

    @Test
    public void committedOrRetiredRequestsNeverOverwriteLaterEdits() throws Exception {
        for (final var step : new RestoreTransaction.Step[]{RestoreTransaction.Step.AFTER_COMMIT,
                RestoreTransaction.Step.BEFORE_RETIRE, RestoreTransaction.Step.AFTER_RETIRE}) {
            final Path directory = fixture();
            publish(directory);
            assertEquals(91, child(directory, "recover", step, 1));
            newPair(directory);
            new RestoreProcess.DiskPreferences(directory)
                    .replace(new HashMap<>(Map.of("version", "later")));
            Files.writeString(directory.resolve("newpipe.db"), "later DB edits");
            assertEquals(0, child(directory, "recover", "NONE", 1));
            assertEquals("later DB edits", Files.readString(directory.resolve("newpipe.db")));
            assertEquals(Map.of("version", "later"),
                    new RestoreProcess.DiskPreferences(directory).read());
        }
    }

    @Test
    public void failedReplacementDoesNotRemoveWalAndRecoversTheOldPair() throws Exception {
        final Path directory = fixture();
        publish(directory);
        final var faults = new RestoreTransaction.FileSystem() {
            private boolean failed;

            @Override
            public void checkpoint(final RestoreTransaction.Step step, final Path path)
                    throws IOException {
                if (!failed && step == RestoreTransaction.Step.BEFORE_REPLACE) {
                    failed = true;
                    throw new IOException("Injected replacement failure");
                }
            }
        };
        assertNotNull(transaction(directory, faults).recover());
        oldPair(directory);
    }

    @Test
    public void sidecarAndPreferenceFailuresRollBack() throws Exception {
        for (final var step : new RestoreTransaction.Step[]{
                RestoreTransaction.Step.AFTER_REMOVE_SIDECAR,
                RestoreTransaction.Step.BEFORE_PREFERENCES,
                RestoreTransaction.Step.AFTER_PREFERENCES}) {
            final Path directory = fixture();
            publish(directory);
            final var faults = new RestoreTransaction.FileSystem() {
                private boolean fired;

                @Override
                public void checkpoint(final RestoreTransaction.Step current, final Path path)
                        throws IOException {
                    if (!fired && current == step) {
                        fired = true;
                        throw new IOException("Injected activation failure");
                    }
                }
            };
            assertNotNull(transaction(directory, faults).recover());
            oldPair(directory);
        }
    }

    @Test
    public void damagedUndoSnapshotStopsRecoveryBeforeFurtherMutation() throws Exception {
        final Path directory = fixture();
        publish(directory);
        assertEquals(91, child(directory, "recover", RestoreTransaction.Step.AFTER_REPLACE, 1));
        final Path undo = RestoreTransaction.transactionPath(directory.resolve("newpipe.db"))
                .resolve("previous-wal");
        final byte[] bytes = Files.readAllBytes(undo);
        Files.writeString(undo, "damaged");
        assertEquals(72, child(directory, "recover", "NONE", 1));
        assertEquals("new DB", Files.readString(directory.resolve("newpipe.db")));
        assertEquals("old -wal", Files.readString(directory.resolve("newpipe.db-wal")));
        Files.write(undo, bytes);
        assertEquals(0, child(directory, "recover", "NONE", 1));
        oldPair(directory);
    }

    @Test
    public void corruptedQueuedDatabaseIsRejectedBeforeLiveFilesChange() throws Exception {
        final Path directory = fixture();
        publish(directory);
        Files.writeString(RestoreTransaction.pendingDatabase(directory.resolve("newpipe.db")),
                "damaged candidate");
        assertNotNull(transaction(directory).recover());
        oldPair(directory);
        assertFalse(RestoreTransaction.hasPending(directory.resolve("newpipe.db")));
    }

    @Test
    public void databaseOnlyMergesPreferencesAtActivationTime() throws Exception {
        final Path directory = fixture();
        transaction(directory).publish(directory.resolve("candidate"),
                Map.of("location", "URI"), false);
        new RestoreProcess.DiskPreferences(directory)
                .replace(new HashMap<>(Map.of("version", "edited while restart was pending")));
        assertNull(transaction(directory).recover());
        assertEquals(Map.of("version", "edited while restart was pending", "location", "URI"),
                new RestoreProcess.DiskPreferences(directory).read());
    }

    @Test
    public void preferenceSnapshotsPreserveEverySupportedTypeAndLongStrings() throws Exception {
        final Map<String, Object> values = new HashMap<>();
        values.put("int", Integer.MIN_VALUE);
        values.put("long", Long.MAX_VALUE);
        values.put("float", -0.0f);
        values.put("boolean", true);
        values.put("nan", Float.NaN);
        values.put("string", "x".repeat(100000) + "\uD83D\uDE00");
        values.put("set", new HashSet<>(Set.of("one", "two")));
        assertEquals(values, RestorePreferences.copy(values));
        final byte[] encoded = RestorePreferences.encode(values);
        final byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);
        assertThrows(IOException.class, () -> RestorePreferences.decode(truncated));
    }

    @Test
    public void interruptedRollbackCanBeRepeatedUntilTheOldPairIsDurable() throws Exception {
        for (final var step : new RestoreTransaction.Step[]{
                RestoreTransaction.Step.BEFORE_REPLACE, RestoreTransaction.Step.AFTER_REPLACE,
                RestoreTransaction.Step.AFTER_ROLLBACK}) {
            final Path directory = fixture();
            publish(directory);
            assertEquals(91, child(directory, "recover",
                    RestoreTransaction.Step.AFTER_PREFERENCES, 1));
            assertEquals(91, child(directory, "recover", step, 1));
            assertEquals(0, child(directory, "recover", "NONE", 1));
            oldPair(directory);
        }
    }

    @Test
    public void failedPreferenceCommitAfterWritingIsRolledBack() throws Exception {
        final Path directory = fixture();
        publish(directory);
        final var disk = new RestoreProcess.DiskPreferences(directory);
        final var preferences = new RestoreTransaction.Preferences() {
            private boolean failed;

            @Override
            public Map<String, ?> read() throws IOException {
                return disk.read();
            }

            @Override
            public void replace(final Map<String, Object> values) throws IOException {
                disk.replace(values);
                if (!failed) {
                    failed = true;
                    throw new IOException("Commit reported failure after writing");
                }
            }
        };
        final var restore = new RestoreTransaction(directory.resolve("newpipe.db"), preferences,
                path -> { }, new RestoreTransaction.FileSystem());
        assertNotNull(restore.recover());
        oldPair(directory);
    }

    @Test
    public void postCommitFailureDoesNotRollBackCommittedData() throws Exception {
        final Path directory = fixture();
        publish(directory);
        final var io = new RestoreTransaction.FileSystem() {
            @Override
            public void checkpoint(final RestoreTransaction.Step step, final Path path)
                    throws IOException {
                if (step == RestoreTransaction.Step.AFTER_COMMIT) {
                    throw new IOException("Failure after durable commit");
                }
            }
        };
        assertNotNull(transaction(directory, io).recover());
        newPair(directory);
    }

    @Test
    public void damagedCompletionRecordStopsStartupWithoutGuessingAnOutcome() throws Exception {
        final Path directory = fixture();
        publish(directory);
        assertEquals(91, child(directory, "recover", RestoreTransaction.Step.AFTER_COMMIT, 1));
        final Path record = RestoreTransaction.transactionPath(directory.resolve("newpipe.db"))
                .resolve("complete");
        final byte[] original = Files.readAllBytes(record);
        Files.writeString(record, "damaged");
        assertThrows(IOException.class, () -> transaction(directory).recover());
        newPair(directory);
        assertTrue(RestoreTransaction.hasPending(directory.resolve("newpipe.db")));
        Files.write(record, original);
        assertNull(transaction(directory).recover());
        newPair(directory);
    }

    @Test
    public void rollbackRestoresAnOriginallyAbsentDatabase() throws Exception {
        final Path directory = fixture();
        Files.delete(directory.resolve("newpipe.db"));
        for (final String suffix : SIDECARS) {
            Files.delete(directory.resolve("newpipe.db" + suffix));
        }
        publish(directory);
        assertEquals(91, child(directory, "recover",
                    RestoreTransaction.Step.AFTER_PREFERENCES, 1));
        assertEquals(0, child(directory, "recover", "NONE", 1));
        assertFalse(Files.exists(directory.resolve("newpipe.db")));
        assertEquals(Map.of("version", "old"),
                new RestoreProcess.DiskPreferences(directory).read());
        for (final String suffix : SIDECARS) {
            assertFalse(Files.exists(directory.resolve("newpipe.db" + suffix)));
        }
    }

    @Test
    public void rejectedLegacyFileCannotBeReplayedOnAnotherStartup() throws Exception {
        final Path directory = fixture();
        Files.writeString(directory.resolve("newpipe.db.restore"), "invalid legacy database");
        assertNotNull(transaction(directory).recover());
        oldPair(directory);
        assertNull(transaction(directory).recover());
        oldPair(directory);
        assertFalse(Files.exists(directory.resolve("newpipe.db.restore")));
    }

    @Test
    public void failedActivationRecordSyncRecoversTheSavedPair() throws Exception {
        final Path directory = fixture();
        publish(directory);
        final Path pending = RestoreTransaction.transactionPath(directory.resolve("newpipe.db"));
        final var io = new RestoreTransaction.FileSystem() {
            private boolean failed;

            @Override
            public void syncDirectory(final Path path) throws IOException {
                if (!failed && path.equals(pending)
                        && Files.exists(pending.resolve("activation"))) {
                    failed = true;
                    throw new IOException("Activation directory sync failed");
                }
                super.syncDirectory(path);
            }
        };
        assertNotNull(transaction(directory, io).recover());
        oldPair(directory);
    }

    @Test
    public void failedCommitRecordSyncStopsStartupAndRetriesWithoutRollback() throws Exception {
        final Path directory = fixture();
        publish(directory);
        final Path pending = RestoreTransaction.transactionPath(directory.resolve("newpipe.db"));
        final var io = new RestoreTransaction.FileSystem() {
            @Override
            public void syncDirectory(final Path path) throws IOException {
                if (path.equals(pending) && Files.exists(pending.resolve("complete"))) {
                    throw new IOException("Completion directory sync failed");
                }
                super.syncDirectory(path);
            }
        };
        assertThrows(RestoreTransaction.RecoveryException.class,
                () -> transaction(directory, io).recover());
        newPair(directory);
        assertNull(transaction(directory).recover());
        newPair(directory);
    }

}
