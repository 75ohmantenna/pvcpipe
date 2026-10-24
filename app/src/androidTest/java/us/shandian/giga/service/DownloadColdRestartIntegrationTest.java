/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package us.shandian.giga.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
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
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.Mission;
import us.shandian.giga.util.Utility;

/**
 * Host-driven phases of a real service download, not a stand-alone mission simulation.
 * Nonterminal phases publish coldResult and stay inside instrumentation until the host sends
 * SIGKILL. Returning from instrumentation, unbinding, or a teardown pause would invalidate the
 * crash boundary. See tools/pr151_cold_restart.py for the controlled HTTP peer and phase protocol.
 */
public class DownloadColdRestartIntegrationTest {
    private static final long GATE_TIMEOUT_MS = 45_000;

    private final Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();
    private final Bundle arguments = InstrumentationRegistry.getArguments();
    private final Context context = instrumentation.getTargetContext();
    private DownloadManagerService.DownloadManagerBinder binder;
    private File output;
    private String name;
    private long loadedDone = -1;
    private int[] loadedBlocks;
    private long loadedResumeOffset;

    @Test
    public void runPhase() throws Exception {
        assumeTrue("Requires the cold-restart host orchestrator",
                arguments.containsKey("coldPhase"));
        final String phase = required("coldPhase");
        name = required("coldName");
        assertTrue("coldName must be a plain filename", name.matches("[A-Za-z0-9_-]+"));
        final File directory = new File(context.getFilesDir(), "cold_restart");
        assertTrue("Cannot create fixture directory",
                directory.isDirectory() || directory.mkdirs());
        output = new File(directory, name + ".bin");
        // These are normal user preferences, not replacements for manager/network behavior.
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(context.getString(R.string.downloads_cross_network), false)
                .putString(context.getString(R.string.downloads_maximum_retry), "0")
                .commit());
        bindService();

        JSONObject loaded = null;
        if ("start".equals(phase)) {
            assertFalse("Refusing to overwrite an earlier fixture", output.exists());
            assertTrue(output.createNewFile());
            final StoredFileHelper storage = new StoredFileHelper(context,
                    Uri.fromFile(directory), Uri.fromFile(output), DownloadManager.TAG_VIDEO);
            final String url = required("coldUrl");
            final String second = arguments.getString("coldUrl2");
            final String[] urls = second == null ? new String[]{url} : new String[]{url, second};
            final StreamInfo info = new StreamInfo(0, url, url, StreamType.VIDEO_STREAM,
                    name, name, 0);
            onMain(() -> DownloadManagerService.startMission(context,
                    urls, storage, 'v', integer("coldThreads", 2), info, null, null,
                    fixtureSize() * urls.length, new ArrayList<>(), null));
        } else {
            assertTrue("Fixture must survive the process death", output.isFile());
            final Mission mission = awaitMission();
            loaded = snapshot(mission);
            if (mission instanceof DownloadMission pending) {
                loadedDone = pending.done;
                final int[] blocks = (int[]) field(pending, "blocks");
                loadedBlocks = blocks == null ? null : blocks.clone();
                loadedResumeOffset = (long) field(pending, "fallbackResumeOffset");
                assertFalse("Cold-loaded mission already running", pending.running);
                if ("startReplacement".equals(phase)) {
                    assertTrue("Start must follow persisted integrity rejection",
                            integrityRejected(pending) && integrityRejected(checkpoint(pending)));
                }
                if ("resume".equals(phase) || "startReplacement".equals(phase)
                        || "assertComplete".equals(phase)) {
                    onMain(() ->
                            binder.getDownloadManager().resumeMission(pending));
                } else {
                    fail("Unknown phase " + phase);
                }
            } else {
                assertTrue("Only terminal phases may find finished history",
                        "assertComplete".equals(phase));
            }
        }

        final String expectation = "assertComplete".equals(phase) ? "complete"
                : arguments.getString("coldExpectation", "loaded");
        final JSONObject result = awaitExpectation(expectation)
                .put("pid", Process.myPid()).put("phase", phase).put("name", name)
                .put("scenario", required("coldScenario"))
                .put("loaded", loaded == null ? JSONObject.NULL : loaded);
        if ("complete".equals(expectation)) {
            assertFixture();
        }
        final Bundle status = new Bundle();
        status.putString("coldResult", result.toString());
        instrumentation.sendStatus(0, status);
        if ("assertComplete".equals(phase)) {
            return;
        }
        // No cleanup: keep both the instrumentation and real service alive at the crash gate.
        final CountDownLatch killedByHost = new CountDownLatch(1);
        killedByHost.await(integer("coldHoldSeconds", 180), TimeUnit.SECONDS);
        fail("Host did not SIGKILL the gated phase");
    }

    private void bindService() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName component, final IBinder service) {
                binder = (DownloadManagerService.DownloadManagerBinder) service;
                binder.enableNotifications(false);
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(final ComponentName component) {
                // An unexpected service death is surfaced by the host PID/instrumentation checks.
            }
        };
        onMain(() -> {
            final Intent intent = new Intent(context, DownloadManagerService.class);
            context.startService(intent);
            assertTrue("Cannot bind download service",
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE));
        });
        assertTrue("Download service did not bind", connected.await(20, TimeUnit.SECONDS));
    }

    private Mission findMission() throws Exception {
        final Mission[] found = new Mission[1];
        onMain(() -> {
            if (arguments.containsKey("coldTimestamp")) {
                // getIterator() enables connectivity-driven scheduling. Locate a cold-loaded
                // pending mission without enabling that side effect before explicit Start.
                found[0] = binder.getDownloadManager().findPendingMission(
                        Long.parseLong(required("coldTimestamp")));
                if (found[0] != null) {
                    assertEquals("Cold timestamp belongs to another output",
                            Uri.fromFile(output), found[0].storage.getUri());
                    return;
                }
            }
            final DownloadManager.MissionIterator iterator =
                    binder.getDownloadManager().getIterator();
            for (int index = 0; index < iterator.getOldListSize(); index++) {
                final Mission candidate = iterator.getItem(index).mission;
                if (candidate != null && candidate.storage != null
                        && Uri.fromFile(output).equals(candidate.storage.getUri())) {
                    assertTrue("Duplicate fixture mission", found[0] == null);
                    found[0] = candidate;
                }
            }
        });
        return found[0];
    }

    private Mission awaitMission() throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + GATE_TIMEOUT_MS;
        do {
            final Mission mission = findMission();
            if (mission != null) {
                return mission;
            }
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Real manager did not load fixture " + name);
    }

    private JSONObject awaitExpectation(final String expectation) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + GATE_TIMEOUT_MS;
        JSONObject last = null;
        do {
            final Mission mission = findMission();
            if (mission != null) {
                last = snapshot(mission);
                if (mission instanceof FinishedMission) {
                    if ("complete".equals(expectation)) {
                        final File metadata = new File(context.getFilesDir(),
                                "pending_downloads/" + mission.timestamp);
                        assertFalse("Finished checkpoint remains", metadata.exists());
                        assertFalse("Finished backup remains",
                                new File(metadata + ".bak").exists());
                        assertFalse("Finished temporary checkpoint remains",
                                new File(metadata + ".new").exists());
                        return last;
                    }
                } else if (mission instanceof DownloadMission pending) {
                    final DownloadMission saved = checkpoint(pending);
                    if (saved != null && saved.current < saved.offsets.length
                            && (arguments.getString("coldCurrent") == null
                            || saved.current == integer("coldCurrent", 0))) {
                        final long base = saved.offsets[saved.current] - saved.offsets[0];
                        final boolean forceFull = (boolean) field(saved, "forceFullDownload");
                        final boolean matches;
                        switch (expectation) {
                            case "loaded":
                                matches = true;
                                break;
                            case "partial":
                                matches = pending.running && saved.done > Math.max(base, loadedDone)
                                        && field(saved, "resourceIdentity") != null
                                        && advancedResumablePosition(saved);
                                break;
                            case "rejected":
                                matches = integrityRejected(pending) && integrityRejected(saved)
                                        && rejectionMessageMatches(pending, saved);
                                break;
                            case "cleared":
                                matches = pending.running && forceFull && saved.done == base
                                        && (long) field(saved, "fallbackResumeOffset") == 0
                                        && ((int[]) field(saved, "blocks")).length == 0
                                        && field(saved, "resourceIdentity") == null;
                                break;
                            case "clearedPartial":
                                // A loaded full-mode checkpoint already has progress. The host
                                // caps this response below that progress so only a newly persisted
                                // replacement checkpoint can satisfy the cold-resume gate.
                                matches = pending.running && pending.done > base
                                        && forceFull && saved.done > base
                                        && (!Boolean.parseBoolean(arguments.getString(
                                                "coldFreshBelowLoaded", "false"))
                                        || saved.done < loadedDone);
                                break;
                            case "complete":
                                matches = false;
                                break;
                            default:
                                throw new AssertionError("Unknown expectation " + expectation);
                        }
                        if (matches) {
                            return pendingSnapshot(pending).put("checkpoint",
                                    pendingSnapshot(saved));
                        }
                    }
                    if (!pending.running && pending.errCode != DownloadMission.ERROR_NOTHING
                            && !"rejected".equals(expectation) && !"loaded".equals(expectation)) {
                        throw new AssertionError("Download failed before "
                                + expectation + ": " + last);
                    }
                }
            }
            // Bounded polling of a durable checkpoint, not a timing-based crash trigger.
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out awaiting " + expectation + ": " + last);
    }

    private boolean advancedResumablePosition(final DownloadMission mission) throws Exception {
        if ((long) field(mission, "fallbackResumeOffset") > loadedResumeOffset) {
            return true;
        }
        final int[] blocks = (int[]) field(mission, "blocks");
        if (blocks != null) {
            for (int index = 0; index < blocks.length; index++) {
                final int previous = loadedBlocks == null ? 0 : loadedBlocks[index];
                if (previous >= 0 && (blocks[index] < 0 || blocks[index] > previous)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean integrityRejected(final DownloadMission mission) throws Exception {
        // A late error from another worker may replace errObject, but not this sticky marker.
        return mission != null && !mission.running
                && (boolean) field(mission, "rangeErrorRestartPending");
    }

    private boolean rejectionMessageMatches(final DownloadMission pending,
                                            final DownloadMission saved) throws Exception {
        final String expected = arguments.getString("coldErrorContains");
        return expected == null || ((boolean) field(saved, "forceFullDownload")
                && pending.errObject != null && saved.errObject != null
                && pending.errObject.getMessage() != null
                && saved.errObject.getMessage() != null
                && pending.errObject.getMessage().contains(expected)
                && saved.errObject.getMessage().contains(expected));
    }

    private static DownloadMission checkpoint(final DownloadMission mission) throws Exception {
        // Utility's AtomicFile reader can restore a backup. Take the writer's lock so observation
        // cannot restore a backup while production is committing an in-flight checkpoint.
        synchronized (field(mission, "LOCK")) {
            return mission.metadata == null ? null : Utility.readFromFile(mission.metadata);
        }
    }

    private static JSONObject snapshot(final Mission mission) throws Exception {
        if (mission instanceof DownloadMission pending) {
            final DownloadMission saved = checkpoint(pending);
            final JSONObject result = pendingSnapshot(pending);
            return result.put("checkpoint",
                    saved == null ? JSONObject.NULL : pendingSnapshot(saved));
        }
        return new JSONObject().put("state", "finished").put("timestamp", mission.timestamp)
                .put("running", false).put("length", mission.length);
    }

    private static JSONObject pendingSnapshot(final DownloadMission mission) throws Exception {
        final Object identity = field(mission, "resourceIdentity");
        final JSONObject result = new JSONObject().put("state", "pending")
                .put("timestamp", mission.timestamp).put("running", mission.running)
                .put("done", mission.done).put("current", mission.current)
                .put("length", mission.length).put("unknownLength", mission.unknownLength)
                .put("forceFullDownload", field(mission, "forceFullDownload"))
                .put("rangeErrorRestartPending", field(mission, "rangeErrorRestartPending"))
                .put("fallbackResumeOffset", field(mission, "fallbackResumeOffset"))
                .put("offsets", new JSONArray(mission.offsets))
                .put("blocks", field(mission, "blocks") == null ? JSONObject.NULL
                        : new JSONArray(field(mission, "blocks")))
                .put("errCode", mission.errCode)
                .put("errObject", mission.errObject == null ? JSONObject.NULL
                        : mission.errObject.getClass().getSimpleName())
                .put("errMessage", mission.errObject == null ? JSONObject.NULL
                        : mission.errObject.getMessage());
        return result.put("identity", identity == null ? JSONObject.NULL : new JSONObject()
                .put("requestUrl", field(identity, "requestUrl"))
                .put("effectiveUrl", field(identity, "effectiveUrl"))
                .put("condition", field(identity, "condition")));
    }

    private void assertFixture() throws Exception {
        final long size = fixtureSize();
        final int resources = arguments.getString("coldUrl2") == null ? 1 : 2;
        assertEquals("Exact completed length", size * resources, output.length());
        final int seed = integer("coldFixtureSeed", 0);
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(output))) {
            for (int resource = 0; resource < resources; resource++) {
                final int resourceSeed = arguments.containsKey("coldReplacementSeed")
                        && resource == resources - 1
                        ? integer("coldReplacementSeed", seed) + resource : seed + resource;
                for (long index = 0; index < size; index++) {
                    final int expected = (int) ((index * 31 + (index >> 8) * 17
                            + resourceSeed) & 255);
                    final int actual = input.read();
                    if (actual != expected) {
                        fail("Fixture mismatch at resource " + resource + " byte " + index
                                + ": expected " + expected + ", actual " + actual);
                    }
                }
            }
            assertEquals("Unexpected trailing bytes", -1, input.read());
        }
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

    private long fixtureSize() {
        return Long.parseLong(required("coldFixtureSize"));
    }

    private int integer(final String key, final int fallback) {
        return Integer.parseInt(arguments.getString(key, Integer.toString(fallback)));
    }

    private String required(final String key) {
        final String value = arguments.getString(key);
        assertNotNull("Missing instrumentation argument " + key, value);
        return value;
    }
}
