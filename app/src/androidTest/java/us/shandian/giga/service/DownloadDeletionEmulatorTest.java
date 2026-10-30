/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.service;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Intent;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.R;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.FutureTask;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;
import us.shandian.giga.ui.adapter.MissionAdapter;
import us.shandian.giga.ui.common.Deleter;
import us.shandian.giga.util.Utility;
import java.lang.reflect.Method;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Native provider, persisted URI grants and SQLite; only unique, guarded fixture directories. */
public class DownloadDeletionEmulatorTest {
    private static final String PACKAGE = "org.seventyfiveohmantenna.pvcpipe.debug";
    private static final String TEST_PACKAGE = PACKAGE + ".test";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String authority = TEST_PACKAGE + ".download-delete";

    @Test
    public void refusedDeletionAndPendingReloadRemainRetryable() throws Exception {
        final Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Explicit disposable invocation required", args.containsKey("deleteDisposable"));
        assertEquals("true", args.getString("deleteDisposable"));
        assertEquals(26, Build.VERSION.SDK_INT);
        DeletionDocumentsProvider.requireEmulator();
        assertEquals(PACKAGE, context.getPackageName());
        assertEquals(TEST_PACKAGE,
                InstrumentationRegistry.getInstrumentation().getContext().getPackageName());
        final String nonce = args.getString("deleteNonce");
        assertNotNull(nonce);
        assertTrue(nonce.matches("[a-f0-9]{32}"));
        assertEquals(nonce, new String(Files.readAllBytes(new File(context.getFilesDir(),
                "download-delete-allow").toPath()), StandardCharsets.UTF_8).trim());
        final File root = new File(context.getFilesDir(), "download-delete-tests/" + nonce);
        assertFalse("Refusing to reuse fixture", root.exists());
        assertTrue(root.mkdirs());

        final String finishedId = nonce + "-finished";
        final Uri uri = createAndGrant(finishedId);
        final AtomicBoolean readOnly = new AtomicBoolean();
        final Context finishedContext = isolated(new File(root, "finished"), readOnly);
        final DownloadMission seed = new DownloadMission(new String[]{"https://fixture.invalid"},
                new StoredFileHelper(context, uri, "application/octet-stream"), 'v', null);
        seed.source = "https://fixture.invalid";
        seed.timestamp = 1234;
        seed.length = DeletionDocumentsProvider.BYTES.length;
        try (FinishedMissionStore store = new FinishedMissionStore(finishedContext)) {
            assertTrue(store.addFinishedMission(seed));
        }
        final DownloadManager first = manager(finishedContext);
        final FinishedMission finished = (FinishedMission) first.getIterator().getItem(1).mission;
        assertFalse(first.deleteMission(finished, true));
        assertEquals(2, first.getIterator().getOldListSize());
        assertTrue(hasPersistedGrant(uri));
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(input);
            final byte[] bytes = new byte[DeletionDocumentsProvider.BYTES.length];
            new DataInputStream(input).readFully(bytes);
            assertArrayEquals(DeletionDocumentsProvider.BYTES, bytes);
            assertEquals(-1, input.read());
        }
        try (FinishedMissionStore store = new FinishedMissionStore(finishedContext)) {
            assertEquals(1, store.loadFinishedMissions().size());
        }

        policy(finishedId, "unavailable");
        final DownloadManager unavailable = manager(finishedContext);
        assertEquals(2, unavailable.getIterator().getOldListSize());
        final FinishedMission retained =
                (FinishedMission) unavailable.getIterator().getItem(1).mission;
        assertEquals(uri, retained.storage.getUri());
        assertFalse(unavailable.deleteMission(retained, true));
        assertTrue(hasPersistedGrant(uri));
        policy(finishedId, "allow");
        final Field storeField = DownloadManager.class.getDeclaredField("mFinishedMissionStore");
        storeField.setAccessible(true);
        ((FinishedMissionStore) storeField.get(unavailable)).close();
        readOnly.set(true);
        assertFalse(unavailable.deleteMission(retained, true));
        assertTrue(retained.mediaDeleted);
        assertFalse(hasPersistedGrant(uri));
        final DownloadManager cleanup = manager(finishedContext);
        final FinishedMission receipt = (FinishedMission) cleanup.getIterator().getItem(1).mission;
        assertTrue(receipt.mediaDeleted);
        assertDeletionRow(cleanup, finishedContext);
        readOnly.set(false);
        assertTrue(cleanup.deleteMission(receipt, true));
        assertEquals(0, manager(finishedContext).getIterator().getOldListSize());

        final String pendingId = nonce + "-pending";
        final Uri pendingUri = createAndGrant(pendingId);
        policy(pendingId, "allow");
        final Context pendingContext = isolated(new File(root, "pending"));
        final File checkpoints = new File(pendingContext.getFilesDir(), "pending_downloads");
        assertTrue(checkpoints.mkdirs());
        final File checkpoint = new File(checkpoints, "5678");
        final DownloadMission pending = new DownloadMission(
                new String[]{"https://fixture.invalid"},
                new StoredFileHelper(context, pendingUri, "application/octet-stream"), 'v', null);
        pending.timestamp = 5678;
        pending.metadata = checkpoint;
        pending.mHandler = new Handler(Looper.getMainLooper());
        pending.errCode = DownloadMission.ERROR_PROGRESS_LOST;
        pending.enqueued = false;
        assertTrue(Utility.writeToFile(checkpoint, pending));
        final DownloadManager deleting = manager(pendingContext);
        final File companion = new File(checkpoint.getPath() + ".new");
        assertTrue(companion.mkdir());
        final File blocker = new File(companion, "blocked");
        Files.write(blocker.toPath(), DeletionDocumentsProvider.BYTES);
        assertDeletionRow(deleting, pendingContext, true);
        assertTrue(checkpoint.exists());
        assertTrue(deleting.findPendingMission(5678).mediaDeleted);
        assertFalse(hasPersistedGrant(pendingUri));
        final DownloadManager reloaded = manager(pendingContext);
        final DownloadMission retry = reloaded.findPendingMission(5678);
        assertNotNull(retry);
        assertTrue(retry.isDeletionPending());
        reloaded.startAllMissions();
        reloaded.resumeMission(retry);
        reloaded.tryRecover(retry);
        assertFalse(reloaded.setFinished(retry));
        assertFalse(retry.running);
        assertTrue(retry.mediaDeleted);
        assertArrayEquals(new boolean[]{false, false},
                reloaded.getIterator().hasValidPendingMissions());
        assertDeletionRow(reloaded, pendingContext);
        Files.delete(blocker.toPath());
        Files.delete(companion.toPath());
        assertTrue(reloaded.deleteMission(retry, true));
        assertFalse(hasPersistedGrant(pendingUri));
        assertFalse(checkpoint.exists());
        assertNull(manager(pendingContext).findPendingMission(5678));
        assertEquals("Deletion incomplete",
                context.getString(R.string.download_deletion_incomplete));
        assertTrue(context.getResources().getQuantityString(
                R.plurals.download_deletion_failed, 1, 1)
                .contains("Its entry was kept"));
        assertTrue(context.getResources().getQuantityString(
                R.plurals.download_deletion_failed, 2, 2)
                .contains("Their entries were kept"));
        System.out.println("DOWNLOAD_DELETE_NATIVE_OK nonce=" + nonce
                + " providerRefusal=true refusalGrantRetained=true sqliteRetained=true retry=true"
                + " databaseRetryWithoutGrant=true pendingRetryWithoutGrant=true"
                + " pendingReload=true pendingResumeBlocked=true checkpointRetry=true");
    }

    private DownloadManager manager(final Context isolated) {
        return new DownloadManager(isolated, new Handler(Looper.getMainLooper()), null, null);
    }

    private void assertDeletionRow(final DownloadManager manager, final Context isolated)
            throws Exception {
        assertDeletionRow(manager, isolated, false);
    }

    private void assertDeletionRow(final DownloadManager manager, final Context isolated,
                                  final boolean commitVisiblePending) throws Exception {
        final FutureTask<Void> check = new FutureTask<>(() -> {
            final Context themed = new ContextThemeWrapper(isolated, R.style.LightTheme);
            final FrameLayout root = new FrameLayout(themed);
            final MissionAdapter adapter = new MissionAdapter(themed, manager,
                    new FrameLayout(themed), root);
            final RecyclerView.ViewHolder row = adapter.onCreateViewHolder(root,
                    DownloadManager.SPECIAL_NOTHING);
            adapter.onBindViewHolder(row, 1);
            final TextView status = row.itemView.findViewById(R.id.item_status);
            if (commitVisiblePending) {
                assertEquals(themed.getString(R.string.msg_error), status.getText().toString());
                final Field field = MissionAdapter.class.getDeclaredField("mDeleter");
                field.setAccessible(true);
                final Deleter deleter = (Deleter) field.get(adapter);
                deleter.append(manager.findPendingMission(5678), true);
                adapter.applyChanges();
                final Method commit = Deleter.class.getDeclaredMethod("commit");
                commit.setAccessible(true);
                commit.invoke(deleter);
            }
            assertEquals("Deletion incomplete", status.getText().toString());
            adapter.onDestroy();
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(check);
        check.get();
    }

    private boolean hasPersistedGrant(final Uri uri) {
        return context.getContentResolver().getPersistedUriPermissions().stream()
                .anyMatch(permission -> uri.equals(permission.getUri())
                        && permission.isReadPermission() && permission.isWritePermission());
    }

    private Uri createAndGrant(final String id) {
        context.startActivity(new Intent().setClassName(TEST_PACKAGE,
                DeletionGrantActivity.class.getName()).putExtra("fixtureId", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final Uri uri = DocumentsContract.buildDocumentUri(authority, id);
        final long deadline = SystemClock.uptimeMillis() + 10_000;
        while (!hasPersistedGrant(uri) && SystemClock.uptimeMillis() < deadline) {
            try {
                context.getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (final SecurityException e) {
                SystemClock.sleep(100);
            }
        }
        assertTrue("Grant activity did not provide persistable access", hasPersistedGrant(uri));
        return uri;
    }

    private void policy(final String id, final String policy) {
        final Bundle args = new Bundle();
        args.putString("policy", policy);
        context.getContentResolver().call(DocumentsContract.buildDocumentUri(authority, id),
                "set-policy", id, args);
    }

    private Context isolated(final File root) {
        return isolated(root, new AtomicBoolean());
    }

    private Context isolated(final File root, final AtomicBoolean readOnly) {
        assertTrue(root.mkdirs());
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
                    final DatabaseErrorHandler errorHandler) {
                if (readOnly.get()) {
                    return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(), factory,
                            SQLiteDatabase.OPEN_READONLY);
                }
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),
                        factory, errorHandler);
            }
        };
    }

}
