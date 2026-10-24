/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package us.shandian.giga.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.Mission;
import us.shandian.giga.util.Utility;

/**
 * One warm Start through the real service binder and manager; no process death or fake manager.
 * Run only with tools/download_warm_retry.py on its explicitly acknowledged disposable API26 AVD.
 * The host withholds the reconnect's 503 until the other original worker has actually exited.
 * The normal queue toggle disables automatic service retry so only the explicit warm Start runs.
 */
public class DownloadWarmRetryIntegrationTest {
    private static final long TIMEOUT_MS = 45_000;
    private static final int BLOCK_SIZE = 512 * 1024;
    private static final int PREFIX = 187;
    private static final int SIZE = 2 * BLOCK_SIZE + 137;
    private static final int SEED = 73;

    private final Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();
    private final Bundle arguments = InstrumentationRegistry.getArguments();
    private final Context context = instrumentation.getTargetContext();
    private DownloadManagerService.DownloadManagerBinder binder;
    private ServiceConnection connection;
    private File output;

    @Test
    public void retryThenWarmStart() throws Exception {
        assumeTrue("Requires the disposable warm-retry host launcher",
                arguments.containsKey("warmUrl"));
        assertEquals("Disposable acknowledgement is required", "true", required("warmDisposable"));
        assertEquals("Only API26 is authorized", 26, Build.VERSION.SDK_INT);
        assertEquals("Only the debug app is authorized",
                "org.seventyfiveohmantenna.pvcpipe.debug", context.getPackageName());
        final String name = required("warmName");
        assertTrue("Fixture name must be plain", name.matches("[A-Za-z0-9_-]+"));
        final File directory = new File(context.getFilesDir(), "warm_retry");
        assertTrue("Cannot create isolated fixture directory",
                directory.isDirectory() || directory.mkdirs());
        output = new File(directory, name + ".bin");
        assertFalse("Refusing to overwrite an earlier fixture", output.exists());
        assertTrue(output.createNewFile());
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(context.getString(R.string.downloads_cross_network), false)
                .putString(context.getString(R.string.downloads_maximum_retry), "1")
                .commit());
        final int pid = Process.myPid();
        bindService();
        try {
            final StoredFileHelper storage = new StoredFileHelper(context,
                    Uri.fromFile(directory), Uri.fromFile(output), DownloadManager.TAG_VIDEO);
            final String url = required("warmUrl");
            final StreamInfo info = new StreamInfo(0, url, url, StreamType.VIDEO_STREAM,
                    name, name, 0);
            onMain(() -> DownloadManagerService.startMission(context,
                    new String[]{url}, storage, 'v', 2, info, null, null,
                    SIZE, new ArrayList<>(), null));
            final DownloadMission mission = awaitPending();
            final File metadata = mission.metadata;
            assertEquals("Real manager retry preference", 1, mission.maxRetry);
            assertEquals("Two requested workers", 2, mission.threadCount);
            final Thread[] original = awaitOtherWorkerExit(mission);
            // HTTP5xx remains queued and MESSAGE_ERROR otherwise auto-resumes it. Use the same
            // supported queue toggle as the UI, without pausing or changing progress/ownership.
            // setEnqueued also schedules a normal checkpoint, recorded separately below.
            onMain(() -> mission.setEnqueued(false));
            assertFalse("Automatic queue retry must be disabled at the failure gate",
                    mission.enqueued);
            final JSONObject before503 = snapshot(mission);
            // The peer sent exactly 187 bytes; any reconnect response waits for failure control.
            // Inspect the checkpoint without writing, pausing, or replacing the live mission.
            publish("warmBefore503", new JSONObject().put("pid", pid)
                    .put("mission", before503).put("originalWorkers", workers(original)));
            control("fail-retry");
            awaitStopped(mission, original);
            final JSONObject stopped = snapshot(mission);
            assertFalse("Failed mission must be stopped", mission.running);
            assertEquals("Reconnect terminates with HTTP503", 503, mission.errCode);
            assertEquals("No replay has occurred yet", SIZE - BLOCK_SIZE + PREFIX, mission.done);
            final int[] retained = (int[]) field(mission, "blocks");
            assertEquals("Three original blocks", 3, retained.length);
            assertEquals("Terminal worker publishes its retained prefix", PREFIX, retained[0]);
            assertEquals("Other worker completed block 1", -1, retained[1]);
            assertEquals("Other worker completed the final short block", -1, retained[2]);
            for (final boolean acquired : (boolean[]) field(mission, "blockAcquired")) {
                assertFalse("No acquisition may outlive its worker", acquired);
            }
            assertSame("Warm path retains the original manager mission", mission,
                    pending(mission.timestamp));
            publish("warmStopped", new JSONObject().put("pid", pid).put("mission", stopped)
                    .put("originalWorkers", workers(original))
                    .put("checkpointObservation", "Read after workers exited, before Start; "
                            + "not a claim that terminal block publication was persisted"));
            awaitControl("resume");
            assertEquals("PID before actual Start", pid, Process.myPid());
            onMain(() -> binder.getDownloadManager().resumeMission(mission));
            assertEquals("PID after actual Start", pid, Process.myPid());
            assertTrue("Actual manager Start runs the same mission", mission.running);
            assertSame("Warm Start did not reload the mission",
                    mission, pending(mission.timestamp));
            for (final Thread old : original) {
                assertFalse("Original worker restarted", old.isAlive());
                for (final Thread current : mission.threads) {
                    assertFalse("Start must use new worker instances", old == current);
                }
            }
            publish("warmStarted", new JSONObject().put("pid", pid)
                    .put("mission", snapshot(mission)));
            final Mission finished = awaitFinished(mission);
            assertEquals("Exact final logical progress, not replay-inflated", SIZE, mission.done);
            assertEquals("Exact finished length", SIZE, finished.length);
            assertEquals("PID at final byte verification", pid, Process.myPid());
            assertFixture();
            for (final String suffix : new String[]{"", ".bak", ".new"}) {
                assertFalse("Finished metadata remains: " + suffix,
                        new File(metadata.getPath() + suffix).exists());
            }
            publish("warmResult", new JSONObject().put("pid", pid).put("name", name)
                    .put("timestamp", mission.timestamp).put("done", mission.done)
                    .put("length", finished.length).put("outputLength", output.length())
                    .put("before503", before503).put("stopped", stopped)
                    .put("originalWorkers", workers(original)));
            // Keep the process alive until the host independently checks its PID and output hash.
            awaitControl("finish");
        } finally {
            // This does not pause a mission, rewrite a checkpoint, or stop/kill the process.
            onMain(() -> context.unbindService(connection));
        }
    }

    private void bindService() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName component, final IBinder service) {
                binder = (DownloadManagerService.DownloadManagerBinder) service;
                binder.enableNotifications(false);
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(final ComponentName component) {
                // A service death fails the host's independent PID checks and this test's gates.
            }
        };
        onMain(() -> {
            context.startService(new Intent(context, DownloadManagerService.class));
            assertTrue("Cannot bind real service", context.bindService(
                    new Intent(context, DownloadManagerService.class), connection,
                    Context.BIND_AUTO_CREATE));
        });
        assertTrue("Real download service did not bind", connected.await(20, TimeUnit.SECONDS));
    }

    private DownloadMission awaitPending() throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        do {
            final DownloadMission[] found = new DownloadMission[1];
            onMain(() -> {
                final DownloadManager.MissionIterator iterator =
                        binder.getDownloadManager().getIterator();
                for (int index = 0; index < iterator.getOldListSize(); index++) {
                    final Mission candidate = iterator.getItem(index).mission;
                    if (candidate instanceof DownloadMission pending && candidate.storage != null
                            && Uri.fromFile(output).equals(candidate.storage.getUri())) {
                        assertTrue("Duplicate fixture mission", found[0] == null);
                        found[0] = pending;
                    }
                }
            });
            if (found[0] != null) {
                return found[0];
            }
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Real manager did not create the fixture mission");
    }

    private DownloadMission pending(final long timestamp) throws Exception {
        final DownloadMission[] found = new DownloadMission[1];
        onMain(() -> found[0] = binder.getDownloadManager().findPendingMission(timestamp));
        return found[0];
    }

    private Thread[] awaitOtherWorkerExit(final DownloadMission mission) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        do {
            final Thread[] workers = mission.threads;
            // Production publishes the array before filling its worker entries.
            if (workers.length == 2 && workers[0] != null && workers[1] != null) {
                int alive = 0;
                for (final Thread worker : workers) {
                    alive += worker.isAlive() ? 1 : 0;
                }
                final int[] blocks = (int[]) field(mission, "blocks");
                if (alive == 1 && blocks != null && blocks.length == 3
                        && blocks[1] == -1 && blocks[2] == -1
                        && mission.done == SIZE - BLOCK_SIZE + PREFIX) {
                    assertTrue("Failure must not precede the other worker's exit", mission.running);
                    return workers.clone();
                }
            }
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Other original worker did not exit before reconnect503: "
                + snapshot(mission));
    }

    private void awaitStopped(final DownloadMission mission, final Thread[] workers) {
        final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        do {
            boolean alive = false;
            for (final Thread worker : workers) {
                alive |= worker.isAlive();
            }
            if (!mission.running && !alive) {
                return;
            }
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Original workers did not exit after reconnect503");
    }

    private Mission awaitFinished(final DownloadMission original) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        do {
            final Mission[] finished = new Mission[1];
            onMain(() -> {
                final DownloadManager.MissionIterator iterator =
                        binder.getDownloadManager().getIterator();
                for (int index = 0; index < iterator.getOldListSize(); index++) {
                    final Mission candidate = iterator.getItem(index).mission;
                    if (candidate instanceof FinishedMission
                            && candidate.timestamp == original.timestamp) {
                        finished[0] = candidate;
                    }
                }
            });
            if (finished[0] != null) {
                return finished[0];
            }
            if (!original.running && original.errCode != DownloadMission.ERROR_NOTHING) {
                throw new AssertionError("Warm Start failed: " + snapshot(original));
            }
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Real manager did not finish warm fixture: " + snapshot(original));
    }

    private JSONObject snapshot(final DownloadMission mission) throws Exception {
        synchronized (mission) {
            synchronized (field(mission, "LOCK")) {
                final DownloadMission saved = mission.metadata == null ? null
                        : Utility.readFromFile(mission.metadata);
                return state(mission).put("checkpointAtObservation",
                        saved == null ? JSONObject.NULL : state(saved));
            }
        }
    }

    private static JSONObject state(final DownloadMission mission) throws Exception {
        final Object blocks = field(mission, "blocks");
        final Object acquired = field(mission, "blockAcquired");
        return new JSONObject().put("running", mission.running).put("done", mission.done)
                .put("length", mission.length).put("errCode", mission.errCode)
                .put("enqueued", mission.enqueued)
                .put("blocks", blocks == null ? JSONObject.NULL : new JSONArray(blocks))
                .put("acquired", acquired == null ? JSONObject.NULL : new JSONArray(acquired))
                .put("writingToFile", field(mission, "writingToFile"));
    }

    private static JSONArray workers(final Thread[] workers) throws Exception {
        final JSONArray result = new JSONArray();
        for (final Thread worker : workers) {
            result.put(new JSONObject().put("id", worker.getId()).put("name", worker.getName())
                    .put("alive", worker.isAlive()).put("state", worker.getState().name()));
        }
        return result;
    }

    private void assertFixture() throws Exception {
        assertEquals("Exact output length", SIZE, output.length());
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(output))) {
            for (int index = 0; index < SIZE; index++) {
                final int expected = (index * 31 + (index >> 8) * 17 + SEED) & 255;
                final int actual = input.read();
                if (actual != expected) {
                    fail("Byte mismatch at " + index + ": expected " + expected
                            + ", actual " + actual);
                }
            }
            assertEquals("Unexpected trailing bytes", -1, input.read());
        }
    }

    private void awaitControl(final String gate) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        do {
            if (control(gate) == HttpURLConnection.HTTP_OK) {
                return;
            }
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Host did not release warm gate " + gate);
    }

    private int control(final String gate) throws Exception {
        final HttpURLConnection request = (HttpURLConnection)
                new URL(required("warmControl") + "/" + gate).openConnection();
        request.setConnectTimeout(5_000);
        request.setReadTimeout(5_000);
        try {
            return request.getResponseCode();
        } finally {
            request.disconnect();
        }
    }

    private void publish(final String key, final JSONObject value) {
        final Bundle status = new Bundle();
        status.putString(key, value.toString());
        instrumentation.sendStatus(0, status);
    }

    private void onMain(final Runnable action) throws Exception {
        final FutureTask<Void> result = new FutureTask<>(action, null);
        instrumentation.runOnMainSync(result);
        result.get(20, TimeUnit.SECONDS);
    }

    private static Object field(final Object object, final String name) throws Exception {
        final Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private String required(final String key) {
        final String value = arguments.getString(key);
        assertNotNull("Missing instrumentation argument " + key, value);
        return value;
    }
}
