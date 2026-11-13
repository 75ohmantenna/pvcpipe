/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.R;
import org.schabi.newpipe.about.AboutActivity;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.util.Utility;

/** Real Android service lifetime; only explicitly acknowledged, separately installed test apps. */
public class DownloadTimeoutDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private DownloadManagerService.DownloadManagerBinder binder;
    private ServiceConnection connection;
    private DownloadManagerService service;

    @Test
    public void boundTimeoutResumeAndUnbindRetainsStartedLifetime() throws Exception {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue("Requires the explicit disposable verification launcher",
                arguments.containsKey("disposableVerification")
                        || arguments.containsKey("expectedPackage"));
        final String expected = arguments.getString("expectedPackage");
        assertEquals("Explicit disposable acknowledgement required", "true",
                arguments.getString("disposableVerification"));
        assertNotNull(expected);
        assertTrue("Refusing a normal/user package", expected.matches(
                "org\\.seventyfiveohmantenna\\.pvcpipe\\.verification\\."
                        + "[a-z][a-z0-9]{1,31}\\.debug"));
        assertEquals(expected, context.getPackageName());
        assertTrue("Requires timeout callback API", Build.VERSION.SDK_INT >= 35);
        final File directory = new File(context.getFilesDir(), "timeout-device");
        assertFalse("Refusing reused fixture data", directory.exists());
        assertTrue(directory.mkdir());
        final File output = new File(directory, "bytes.bin");
        assertTrue(output.createNewFile());
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(context.getString(R.string.downloads_cross_network), false)
                .putString(context.getString(R.string.downloads_maximum_retry), "0").commit());
        final byte[] data = new byte[2 * 1024 * 1024 + 137];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + i / 101);
        }
        DownloadMission mission = null;
        try (Fixture peer = new Fixture(data);
             ActivityScenario<AboutActivity> activity =
                     ActivityScenario.launch(AboutActivity.class)) {
            await("real foreground activity", () -> {
                final ActivityManager.RunningAppProcessInfo process =
                        new ActivityManager.RunningAppProcessInfo();
                ActivityManager.getMyMemoryState(process);
                return process.importance
                        == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            });
            bind();
            final DownloadManager manager = binder.getDownloadManager();
            final StoredFileHelper storage = new StoredFileHelper(context,
                    Uri.fromFile(directory), Uri.fromFile(output), DownloadManager.TAG_VIDEO);
            final String url = peer.url();
            final StreamInfo info = new StreamInfo(0, url, url, StreamType.VIDEO_STREAM,
                    "timeout fixture", "timeout fixture", 0);
            onMain(() -> DownloadManagerService.startMission(context, new String[]{url}, storage,
                    'v', 1, info, null, null, data.length, new ArrayList<>(), null));
            final DownloadMission[] selected = new DownloadMission[1];
            await("registered download", () -> {
                onMain(() -> {
                    final DownloadManager.MissionIterator iterator = manager.getIterator();
                    for (int i = 0; i < iterator.getOldListSize(); i++) {
                        if (iterator.getItem(i).mission instanceof DownloadMission pending
                                && Uri.fromFile(output).equals(pending.storage.getUri())) {
                            selected[0] = pending;
                        }
                    }
                });
                return selected[0] != null;
            });
            mission = selected[0];
            final DownloadMission active = mission;
            final File metadata = active.metadata;
            final DownloadManagerService originalService = service;
            long maximumCallbackMillis = 0;
            for (int cycle = 0; cycle < 2; cycle++) {
                final int expectedRequests = cycle + 1;
                await("held active response", () -> peer.gets.get() >= expectedRequests
                        && active.running && active.done > 0 && runningService(true, true));
                final long callbackStarted = SystemClock.elapsedRealtime();
                onMain(() -> {
                    service.onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    service.onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                });
                maximumCallbackMillis = Math.max(maximumCallbackMillis,
                        SystemClock.elapsedRealtime() - callbackStarted);
                final CompletableFuture<?> pause = (CompletableFuture<?>)
                        field(manager, "timeoutPause");
                assertNotNull(pause);
                pause.get(20, TimeUnit.SECONDS);
                assertFalse(active.running);
                assertTrue(metadata.isFile());
                final DownloadMission checkpoint = Utility.readFromFile(metadata);
                assertNotNull("Timeout checkpoint is readable", checkpoint);
                assertEquals("Paused progress is checkpointed", active.done, checkpoint.done);
                assertEquals(active.length, checkpoint.length);
                assertEquals(active.storage.getUri(),
                        StoredFileHelper.deserialize(checkpoint.storage, context).getUri());
                await("bound-only stopped foreground lifetime", () -> runningService(false, false));
                onMain(() -> manager.resumeMission(active));
                await("resumed started foreground lifetime", () -> active.running
                        && peer.gets.get() >= expectedRequests + 1 && runningService(true, true));
                assertSame(active, manager.findPendingMission(active.timestamp));
                unbind();
                SystemClock.sleep(600);
                assertTrue("Resume must survive removal of the last test binding",
                        runningService(true, true));
                assertTrue("Held download must still run after unbind", active.running);
                assertTrue("Held response must not be completed", active.done < data.length);
                if (cycle == 0) {
                    bind();
                    assertSame("No replacement service/manager during warm resume",
                            originalService, service);
                    assertSame(manager, binder.getDownloadManager());
                }
            }
            peer.release.countDown();
            await("exact finished bytes",
                    () -> active.isFinished() && output.length() == data.length);
            assertArrayEquals(data, Files.readAllBytes(output.toPath()));
            assertEquals(data.length, active.done);
            await("finished metadata retired", () -> !metadata.exists()
                    && !new File(metadata.getPath() + ".bak").exists()
                    && !new File(metadata.getPath() + ".new").exists());
            final Bundle proof = new Bundle();
            proof.putInt("timeoutCycles", 2);
            proof.putBoolean("exactBytesVerified", true);
            proof.putLong("verifiedLength", data.length);
            proof.putLong("callbackMaxMillis", maximumCallbackMillis);
            instrumentation.sendStatus(0, proof);
        } finally {
            if (mission != null && mission.running) {
                mission.pause();
            }
            unbind();
            onMain(() -> context.stopService(new Intent(context, DownloadManagerService.class)));
        }
    }

    private void bind() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName name, final IBinder value) {
                binder = (DownloadManagerService.DownloadManagerBinder) value;
                binder.enableNotifications(false);
                for (final Field candidate : value.getClass().getDeclaredFields()) {
                    if (candidate.getType() == DownloadManagerService.class) {
                        try {
                            candidate.setAccessible(true);
                            service = (DownloadManagerService) candidate.get(value);
                        } catch (final IllegalAccessException e) {
                            throw new AssertionError(e);
                        }
                    }
                }
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(final ComponentName name) {
                // Explicit assertions below inspect the real ActivityManager service lifetime.
            }
        };
        onMain(() -> assertTrue(context.bindService(
                new Intent(context, DownloadManagerService.class),
                connection, Context.BIND_AUTO_CREATE)));
        assertTrue(connected.await(20, TimeUnit.SECONDS));
        assertNotNull(service);
    }

    private void unbind() {
        if (connection != null) {
            final ServiceConnection previous = connection;
            connection = null;
            onMain(() -> context.unbindService(previous));
        }
    }

    @SuppressWarnings("deprecation")
    private boolean runningService(final boolean started, final boolean foreground) {
        for (final ActivityManager.RunningServiceInfo running : context
                .getSystemService(ActivityManager.class).getRunningServices(100)) {
            if (running.service.getPackageName().equals(context.getPackageName())
                    && running.service.getClassName().equals(
                    DownloadManagerService.class.getName())) {
                return running.started == started && running.foreground == foreground;
            }
        }
        return false;
    }

    private static Object field(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private void onMain(final Runnable action) {
        instrumentation.runOnMainSync(action);
    }

    private static void await(final String description, final BooleanSupplier condition) {
        final long deadline = SystemClock.elapsedRealtime() + 25_000;
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static final class Fixture implements AutoCloseable {
        private final byte[] bytes;
        private final ServerSocket server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger gets = new AtomicInteger();

        Fixture(final byte[] bytes) throws Exception {
            this.bytes = bytes;
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            workers.execute(() -> {
                while (!server.isClosed()) {
                    try {
                        final Socket socket = server.accept();
                        workers.execute(() -> reply(socket));
                    } catch (final Exception closed) {
                        return;
                    }
                }
            });
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/fixture";
        }

        private void reply(final Socket socket) {
            try (socket) {
                socket.setSoTimeout(10_000);
                final BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                final String request = input.readLine();
                int start = 0;
                int end = bytes.length - 1;
                boolean ranged = false;
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("range: bytes=")) {
                        final String[] limits = line.substring(line.indexOf('=') + 1)
                                .split("-", -1);
                        start = Integer.parseInt(limits[0]);
                        if (!limits[1].isEmpty()) {
                            end = Math.min(end, Integer.parseInt(limits[1]));
                        }
                        ranged = true;
                    }
                }
                final OutputStream output = socket.getOutputStream();
                final String headers = "HTTP/1.1 " + (ranged ? "206 Partial Content" : "200 OK")
                        + "\r\nContent-Length: " + (end - start + 1)
                        + "\r\nAccept-Ranges: bytes\r\nETag: \"fixture-v1\"\r\n"
                        + (ranged ? "Content-Range: bytes " + start + "-" + end + "/" + bytes.length
                        + "\r\n" : "") + "Connection: close\r\n\r\n";
                output.write(headers.getBytes(StandardCharsets.US_ASCII));
                if (request.startsWith("GET ")) {
                    gets.incrementAndGet();
                    final int prefix = Math.min(32 * 1024, end - start + 1);
                    output.write(bytes, start, prefix);
                    output.flush();
                    assertTrue("Fixture release deadline", release.await(90, TimeUnit.SECONDS));
                    output.write(bytes, start + prefix, end - start + 1 - prefix);
                }
                output.flush();
            } catch (final Exception expectedCancellation) {
                // Timeout deliberately disconnects the earlier held response.
            }
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            server.close();
            workers.shutdownNow();
            assertTrue("Fixture workers leaked", workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
