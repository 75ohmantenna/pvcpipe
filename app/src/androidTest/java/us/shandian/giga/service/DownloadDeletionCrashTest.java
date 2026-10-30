/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.service;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.Mission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;
import us.shandian.giga.util.Utility;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Host SIGKILL phases; never installed or invoked on an existing user installation. */
public class DownloadDeletionCrashTest {
    private static final String PACKAGE = "org.seventyfiveohmantenna.pvcpipe.debug";
    private static final String TEST_PACKAGE = PACKAGE + ".test";
    private static final long TARGET = 1234;
    private static final long UNRELATED_FINISHED = 4321;
    private static final long UNRELATED_PENDING = 8765;
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private String nonce;
    private String scenario;
    private String kind;
    private String boundary;
    private File root;
    private Uri uri;

    @Test
    public void runPhase() throws Exception {
        final Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Requires disposable host driver",
                args.getString("deleteCrashDisposable") != null);
        assertEquals("true", args.getString("deleteCrashDisposable"));
        DeletionDocumentsProvider.requireEmulator();
        assertEquals(26, Build.VERSION.SDK_INT);
        assertEquals(PACKAGE, context.getPackageName());
        assertEquals(TEST_PACKAGE,
                InstrumentationRegistry.getInstrumentation().getContext().getPackageName());
        assertEquals("org.schabi.newpipe.DebugApp",
                context.getApplicationContext().getClass().getName());
        nonce = args.getString("deleteCrashNonce");
        assertNotNull(nonce);
        assertTrue(nonce.matches("[a-f0-9]{32}"));
        assertEquals(nonce, text(new File(context.getFilesDir(), "download-delete-crash-allow")));
        scenario = args.getString("deleteCrashCase");
        assertNotNull(scenario);
        assertTrue(scenario.matches("(finished|pending)-(before|reply|receipt|committed)"));
        kind = scenario.substring(0, scenario.indexOf('-'));
        boundary = scenario.substring(scenario.indexOf('-') + 1);
        root = new File(context.getFilesDir(), "download-delete-crashes/" + nonce);
        uri = DocumentsContract.buildDocumentUri(
                TEST_PACKAGE + ".download-delete", nonce + "-" + kind);
        switch (args.getString("deleteCrashPhase", "")) {
            case "prepare":
                prepare();
                break;
            case "delete":
                deleteAndHold();
                break;
            case "verify":
                verifyAfterDeath();
                break;
            default:
                throw new IllegalArgumentException("Unknown crash phase");
        }
    }

    private void prepare() throws Exception {
        assertFalse("Refusing to replace fixture", root.exists());
        assertTrue(root.mkdirs());
        grantFromOwner(false);
        final DownloadMission target = seed(TARGET,
                new StoredFileHelper(context, uri, "application/octet-stream"));
        try (FinishedMissionStore store = new FinishedMissionStore(isolated())) {
            if ("finished".equals(kind)) {
                assertTrue(store.addFinishedMission(target));
            } else {
                savePending(target);
            }
            final File unrelated = new File(root, "unrelated-finished.bin");
            Files.write(unrelated.toPath(), DeletionDocumentsProvider.BYTES);
            assertTrue(store.addFinishedMission(seed(UNRELATED_FINISHED,
                    new StoredFileHelper(context, Uri.fromFile(unrelated),
                            "application/octet-stream"))));
        }
        final File unrelated = new File(root, "unrelated-pending.bin");
        Files.write(unrelated.toPath(), DeletionDocumentsProvider.BYTES);
        savePending(seed(UNRELATED_PENDING, new StoredFileHelper(
                context, Uri.fromFile(unrelated), "application/octet-stream")));
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(
                new File(root, "sentinel.db"), null)) {
            db.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)");
        }
        final Bundle policy = new Bundle();
        policy.putString("policy", "before".equals(boundary) ? "hold-before"
                : "reply".equals(boundary) ? "hold-after" : "allow");
        context.getContentResolver().call(uri, "set-policy", nonce + "-" + kind, policy);
        signal("prepared");
    }

    private void grantFromOwner(final boolean regrant) {
        context.startActivity(new Intent().setClassName(TEST_PACKAGE,
                DeletionGrantActivity.class.getName()).putExtra("fixtureId", nonce + "-" + kind)
                .putExtra("regrant", regrant)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final long deadline = SystemClock.uptimeMillis() + 10_000;
        while (!grant() && SystemClock.uptimeMillis() < deadline) {
            try {
                context.getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (final SecurityException e) {
                SystemClock.sleep(50);
            }
        }
        assertTrue(grant());
    }

    private DownloadMission seed(final long timestamp, final StoredFileHelper storage) {
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://fixture.invalid"}, storage, 'v', null);
        mission.timestamp = timestamp;
        mission.source = "https://fixture.invalid/" + timestamp;
        mission.length = DeletionDocumentsProvider.BYTES.length;
        mission.errCode = timestamp == UNRELATED_PENDING
                ? DownloadMission.ERROR_POSTPROCESSING_HOLD : DownloadMission.ERROR_PROGRESS_LOST;
        mission.enqueued = false;
        return mission;
    }

    private File checkpoint(final long timestamp) {
        return new File(root, "pending_downloads/" + timestamp);
    }

    private void savePending(final DownloadMission mission) {
        assertTrue(checkpoint(mission.timestamp).getParentFile().isDirectory()
                || checkpoint(mission.timestamp).getParentFile().mkdirs());
        assertTrue(Utility.writeToFile(checkpoint(mission.timestamp), mission));
    }

    private void deleteAndHold() throws Exception {
        // Leave a committed WAL open; compare bytes before another process opens it.
        final SQLiteDatabase sentinel = SQLiteDatabase.openOrCreateDatabase(
                new File(root, "sentinel.db"), null);
        assertTrue(sentinel.enableWriteAheadLogging());
        sentinel.execSQL("INSERT INTO sentinel VALUES (?)", new Object[]{nonce});
        final DownloadManager manager = manager();
        if ("receipt".equals(boundary) && "finished".equals(kind)) {
            historySql("CREATE TRIGGER hold_deletion BEFORE DELETE ON finished_missions "
                    + "WHEN OLD.timestamp = 1234 BEGIN "
                    + "SELECT RAISE(ABORT, 'controlled deletion failure'); END");
        }
        if ("receipt".equals(boundary) && "pending".equals(kind)) {
            final File companion = new File(checkpoint(TARGET) + ".new");
            assertTrue(companion.mkdir());
            Files.write(new File(companion, "blocker").toPath(), DeletionDocumentsProvider.BYTES);
        }
        final Mission target = target(manager);
        assertNotNull(target);
        signal("started");
        final boolean result = manager.deleteMission(target, true);
        assertEquals("committed".equals(boundary), result);
        if ("receipt".equals(boundary)) {
            assertTrue(target.mediaDeleted);
            if ("finished".equals(kind)) {
                assertTrue(new FinishedDeletionReceipts(root).contains(target));
            } else {
                final DownloadMission saved = Utility.readFromFile(checkpoint(TARGET));
                assertNotNull(saved);
                assertTrue(saved.isDeletionPending());
                assertTrue(saved.mediaDeleted);
            }
        }
        signal("held");
        // The host checks the exact PID/cmdline and sends SIGKILL. No cleanup simulates death.
        while (sentinel.isOpen()) {
            SystemClock.sleep(100);
        }
        throw new AssertionError("Held database unexpectedly closed");
    }

    private void verifyAfterDeath() throws Exception {
        final boolean before = "before".equals(boundary);
        final long deadline = SystemClock.uptimeMillis() + 10_000;
        while (!before && grant() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50);
        }
        assertEquals(before, grant());
        final DownloadManager manager = manager();
        final Mission target = target(manager);
        if ("committed".equals(boundary)) {
            assertNull(target);
            assertFalse(checkpoint(TARGET).exists());
        } else {
            assertNotNull(target);
            assertEquals(uri, target.storage.getUri());
            assertEquals("receipt".equals(boundary), target.mediaDeleted);
            if (target instanceof DownloadMission) {
                final DownloadMission pending = (DownloadMission) target;
                assertTrue(pending.isDeletionPending());
                manager.startAllMissions();
                manager.resumeMission(pending);
                manager.tryRecover(pending);
                assertFalse(manager.setFinished(pending));
                assertFalse(manager.canRecoverMission(pending));
                assertFalse(pending.running);
                assertFalse(pending.enqueued);
            }
            if ("receipt".equals(boundary) && "finished".equals(kind)) {
                historySql("DROP TRIGGER hold_deletion");
            }
            if ("receipt".equals(boundary) && "pending".equals(kind)) {
                Files.delete(new File(checkpoint(TARGET) + ".new/blocker").toPath());
                Files.delete(new File(checkpoint(TARGET) + ".new").toPath());
            }
            if (before) {
                // The interrupted provider deliberately aborted; restored service permits a retry.
                final Bundle policy = new Bundle();
                policy.putString("policy", "allow");
                context.getContentResolver().call(uri, "set-policy", nonce + "-" + kind, policy);
            }
            final boolean retry = manager.deleteMission(target, true);
            assertEquals(!"reply".equals(boundary), retry);
            // Missing provider confirmation is never promoted into proof of media deletion.
            assertEquals("reply".equals(boundary), target(manager) != null);
            if ("reply".equals(boundary)) {
                // With no receipt, restoring access lets a real provider query prove absence.
                grantFromOwner(true);
                assertTrue(target.storage.isAbsent());
                assertTrue(manager.deleteMission(target, true));
                assertNull(target(manager));
            }
        }
        assertUnrelated(manager);
        signal("verified");
    }

    private void assertUnrelated(final DownloadManager manager) throws Exception {
        assertArrayEquals(DeletionDocumentsProvider.BYTES,
                Files.readAllBytes(new File(root, "unrelated-finished.bin").toPath()));
        assertArrayEquals(DeletionDocumentsProvider.BYTES,
                Files.readAllBytes(new File(root, "unrelated-pending.bin").toPath()));
        final DownloadMission pending = manager.findPendingMission(UNRELATED_PENDING);
        assertNotNull(pending);
        assertFalse(pending.isDeletionPending());
        assertFalse(pending.running);
        assertEquals("https://fixture.invalid/" + UNRELATED_PENDING, pending.source);
        assertEquals(Uri.fromFile(new File(root, "unrelated-pending.bin")),
                pending.storage.getUri());
        try (FinishedMissionStore store = new FinishedMissionStore(isolated())) {
            assertEquals(1, store.loadFinishedMissions().size());
            final FinishedMission finished = store.loadFinishedMissions().stream()
                    .filter(mission -> mission.timestamp == UNRELATED_FINISHED).findFirst().get();
            assertEquals("https://fixture.invalid/" + UNRELATED_FINISHED, finished.source);
            assertEquals(DeletionDocumentsProvider.BYTES.length, finished.length);
            assertEquals(Uri.fromFile(new File(root, "unrelated-finished.bin")),
                    finished.storage.getUri());
        }
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                new File(root, "sentinel.db").getPath(),
                null, SQLiteDatabase.OPEN_READONLY);
             Cursor rows = db.rawQuery("SELECT value FROM sentinel", null)) {
            assertTrue(rows.moveToFirst());
            assertEquals(nonce, rows.getString(0));
            assertFalse(rows.moveToNext());
        }
    }

    private Mission target(final DownloadManager manager) {
        if ("pending".equals(kind)) {
            return manager.findPendingMission(TARGET);
        }
        try (FinishedMissionStore store = new FinishedMissionStore(isolated())) {
            for (final FinishedMission mission : store.loadFinishedMissions()) {
                if (mission.timestamp == TARGET) {
                    // Select the manager's instance so ownership checks remain meaningful.
                    final var iterator = manager.getIterator();
                    for (int i = 0; i < iterator.getOldListSize(); i++) {
                        if (iterator.getItem(i).mission != null
                                && iterator.getItem(i).mission.timestamp == TARGET) {
                            return iterator.getItem(i).mission;
                        }
                    }
                    throw new AssertionError("Stored target missing from manager");
                }
            }
            return null;
        }
    }

    private void historySql(final String sql) {
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(
                new File(root, "downloads.db"), null)) {
            db.execSQL(sql);
        }
    }

    private DownloadManager manager() {
        return new DownloadManager(isolated(),
                new Handler(Looper.getMainLooper()), null, null);
    }

    private Context isolated() {
        return new ContextWrapper(context) {
            @Override public File getFilesDir() {
                return root;
            }
            @Override public File getExternalFilesDir(final String type) {
                return root;
            }
            @Override public File getDatabasePath(final String name) {
                return new File(root, name);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(final String name, final int mode,
                    final SQLiteDatabase.CursorFactory factory) {
                return openOrCreateDatabase(name, mode, factory, null);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(final String name, final int mode,
                    final SQLiteDatabase.CursorFactory factory,
                    final DatabaseErrorHandler handler) {
                return SQLiteDatabase.openOrCreateDatabase(
                        getDatabasePath(name).getPath(), factory, handler);
            }
        };
    }

    private boolean grant() {
        return context.getContentResolver().getPersistedUriPermissions().stream()
                .anyMatch(permission -> uri.equals(permission.getUri())
                        && permission.isReadPermission() && permission.isWritePermission());
    }

    private static String text(final File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void signal(final String phase) throws Exception {
        final JSONObject value = new JSONObject().put("nonce", nonce).put("case", scenario)
                .put("phase", phase).put("pid", Process.myPid());
        // Rename publication prevents the host from accepting a partially written marker.
        final File temporary = new File(root, phase + ".tmp");
        Files.write(temporary.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), new File(root, phase + ".json").toPath());
    }
}
