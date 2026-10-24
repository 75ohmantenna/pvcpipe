package us.shandian.giga.get;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import us.shandian.giga.io.FileStream;
import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.DownloadManagerService;
import us.shandian.giga.util.Utility;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Real initializers, workers, HTTP and file writes, with only scheduling/Android adapters. */
public class DownloadMissionTransitionTest {
    private static final int TIMEOUT_SECONDS = 15;

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private final List<ControlledMission> missions = new ArrayList<>();
    private final List<Peer> peers = new ArrayList<>();
    private final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();

    @After
    public void tearDown() throws Exception {
        // Open every gate first: never join a worker while holding its mission monitor.
        for (final ControlledMission mission : missions) {
            if (mission.gate != null) {
                mission.gate.releaseWriter.countDown();
                mission.gate.releaseTransition.countDown();
            }
            if (mission.postprocessingGate != null) {
                mission.postprocessingGate.release.countDown();
            }
            mission.running = false;
        }
        for (final Peer peer : peers) {
            peer.close();
        }
        for (final ControlledMission mission : missions) {
            mission.stopAndJoin();
        }
        final Throwable failure = asynchronousFailure.get();
        if (failure != null) {
            throw new AssertionError("background fixture/worker failed", failure);
        }
    }

    @Test
    public void queuedCheckpointAfterTransitionRestartsBWithStrongIdentity() throws Exception {
        transitionCheckpoint(true, 2, false);
    }

    @Test
    public void queuedCheckpointAfterTransitionRestartsBWithoutIdentity() throws Exception {
        transitionCheckpoint(false, 2, false);
    }

    @Test
    public void queuedFallbackCheckpointDropsCompletedAResumePosition() throws Exception {
        transitionCheckpoint(true, 1, false);
    }

    @Test
    public void checkpointBeforeTransitionResumesAThenDownloadsB() throws Exception {
        transitionCheckpoint(true, 2, true);
    }

    private void transitionCheckpoint(final boolean identity, final int threadCount,
                                      final boolean saveBeforeTransition) throws Exception {
        final Peer peer = peer(identity);
        final Gate gate = new Gate(saveBeforeTransition);
        final File target = folder.newFile();
        final ControlledMission original = mission(peer, target, gate, null);
        original.threadCount = threadCount;
        start(original);
        await(gate.writerEntered, "actual progress checkpoint queued before LOCK");

        if (saveBeforeTransition) {
            // The callback is held only after the production file write and notifyProgress.
            // This makes the other side of the transition an actual, reachable checkpoint.
            gate.releaseWriter.countDown();
            await(gate.writerSaved, "A progress checkpoint serialized");
            await(gate.transitionEntered, "A completes after its earlier checkpoint");
        } else {
            await(gate.transitionEntered, "real A workers reached B initializer seam");
            assertFreshB(original, peer);
            assertArrayEquals(peer.bodies[0], Files.readAllBytes(target.toPath()));
            assertEquals(0, peer.gets[1].get());
            gate.releaseWriter.countDown();
            await(gate.writerSaved, "queued A writer serialized after transition");
        }
        assertTrue("production writeThisToFile must report a saved checkpoint", gate.saved);
        final ControlledMission restored = read(gate.snapshot);
        if (saveBeforeTransition) {
            assertEquals(0, restored.current);
            assertEquals(peer.bodies[0].length, restored.length);
            assertNotNull(restored.blocks);
            assertFalse(restored.isFinished());
        } else {
            assertFreshB(restored, peer);
        }
        restored.restoreProgress();
        if (!saveBeforeTransition) {
            assertFreshB(restored, peer);
        }
        final File replay = folder.newFile();
        Files.copy(target.toPath(), replay.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        attach(restored, replay, null);
        final int beforeB = peer.gets[1].get();
        start(restored);
        awaitFinished(restored);
        assertTrue("restored B must issue real HTTP, not finish from A's completed blocks",
                peer.gets[1].get() > beforeB);
        assertComplete(restored, peer, replay);

        // Positive control: the original initializer must still work when its gate opens.
        gate.releaseTransition.countDown();
        awaitFinished(original);
        assertComplete(original, peer, target);
    }

    @Test
    public void coldBPartialCheckpointKeepsRealWorkerPositionsAndConditionalRange()
            throws Exception {
        final Peer peer = peer(true);
        final File target = folder.newFile();
        final ControlledMission original = mission(peer, target, null, null);
        original.pauseOnBProgress = true;
        start(original);
        await(original.partialPaused, "B worker paused after a real file write");
        original.joinCreatedThreads();
        assertEquals(1, original.current);
        assertNotNull(original.blocks);
        assertEquals(1, original.blocks.length);
        final int prefix = original.blocks[0];
        assertTrue("worker must release an incomplete B block", prefix > 0
                && prefix < peer.bodies[1].length);
        assertEquals(peer.bodies[0].length + prefix, original.done);
        assertEquals(peer.bodies[1].length, original.length);
        assertEquals(peer.url(1), original.resourceIdentity.requestUrl);
        assertTrue(original.writeThisToFile());

        final ControlledMission restored = read(original.metadata);
        restored.restoreProgress();
        assertEquals(1, restored.current);
        assertArrayEquals(new long[]{0, peer.bodies[0].length}, restored.offsets);
        assertArrayEquals(new int[]{prefix}, restored.blocks);
        assertEquals(peer.bodies[1].length, restored.length);
        assertEquals(peer.bodies[0].length + prefix, restored.done);
        assertEquals(0, restored.fallbackResumeOffset);
        assertEquals(peer.url(1), restored.resourceIdentity.requestUrl);
        assertCleanRecovery(restored);
        attach(restored, target, null);
        final int requestStart = peer.requests.size();
        start(restored);
        awaitFinished(restored);
        assertTrue("cold B replay must request its retained prefix with B's validator: "
                        + peer.requests,
                peer.requests.subList(requestStart, peer.requests.size()).contains(
                        "GET /resource1 Range=bytes=" + prefix + "-"
                                + (peer.bodies[1].length - 1) + " If-Range=\"resource-1-v1\""));
        assertComplete(restored, peer, target);
    }

    @Test
    public void finalCheckpointPreservesLastResourceAndStartDoesNotRequestAgain()
            throws Exception {
        final Peer peer = peer(true);
        final File target = folder.newFile();
        final ControlledMission original = mission(peer, target, null, null);
        start(original);
        awaitFinished(original);
        final ControlledMission restored = read(original.metadata);
        assertFinalDownloadState(restored, peer);
        restored.restoreProgress();
        assertFinalDownloadState(restored, peer);
        attach(restored, target, null);
        final int requestCount = peer.requests.size();
        start(restored);
        assertTrue(restored.isFinished());
        assertEquals("finished checkpoint start must be a no-op", requestCount,
                peer.requests.size());
        assertComplete(restored, peer, target);
    }

    @Test
    public void finalDownloadBoundaryWithPendingPostprocessingIsNotFinished()
            throws Exception {
        final Peer peer = peer(true);
        final File target = folder.newFile();
        final PostprocessingGate first = new PostprocessingGate();
        final Postprocessing algorithm = Postprocessing.getAlgorithm(
                Postprocessing.ALGORITHM_TTML_CONVERTER, null, null);
        final ControlledMission original = mission(peer, target, null, algorithm);
        original.postprocessingGate = first;
        attachStorage(original, target, first);
        start(original);
        await(first.entered, "production postprocessing dispatch before algorithm starts");
        assertFinalDownloadState(original, peer);
        assertFalse(original.isFinished());
        assertEquals(0, original.psState);
        assertTrue(original.writeThisToFile());

        final ControlledMission restored = read(original.metadata);
        final long done = restored.done;
        restored.restoreProgress();
        assertEquals(done, restored.done);
        assertFinalDownloadState(restored, peer);
        assertNotNull(restored.psAlgorithm);
        assertFalse(restored.isFinished());
        final PostprocessingGate second = new PostprocessingGate();
        attach(restored, target, second);
        final int requests = peer.requests.size();
        start(restored);
        await(second.entered, "cold final checkpoint dispatches pending postprocessing");
        assertFalse("pending postprocessing must not report a completed mission",
                restored.isFinished());
        assertEquals(0, restored.events.finished.get());
        assertEquals(requests, peer.requests.size());
        assertFinalDownloadState(restored, peer);
        assertArrayEquals(peer.completeBody(), Files.readAllBytes(target.toPath()));
    }

    private static void assertFreshB(final DownloadMission mission, final Peer peer)
            throws Exception {
        assertEquals(1, mission.current);
        assertArrayEquals(new long[]{0, peer.bodies[0].length}, mission.offsets);
        assertEquals(0, mission.length);
        assertNull("B has no blocks until its initializer runs", mission.blocks);
        assertNull("A's identity must not be associated with B", mission.resourceIdentity);
        assertEquals(0, mission.fallbackResumeOffset);
        assertEquals(peer.bodies[0].length, mission.done);
        assertFalse(mission.unknownLength);
        assertFalse(mission.isFinished());
        assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
        assertCleanRecovery(mission);
    }

    private static void assertCleanRecovery(final DownloadMission mission) throws Exception {
        assertFalse(mission.forceFullDownload);
        for (final String name : new String[]{"unverifiedCurrentBytes",
                "rangeErrorRestartPending"}) {
            final Field field = DownloadMission.class.getDeclaredField(name);
            field.setAccessible(true);
            assertFalse(name, field.getBoolean(mission));
        }
    }

    private static void assertFinalDownloadState(final DownloadMission mission, final Peer peer) {
        assertEquals(2, mission.current);
        assertArrayEquals(new long[]{0, peer.bodies[0].length}, mission.offsets);
        assertEquals(peer.bodies[1].length, mission.length);
        assertArrayEquals(new int[]{-1}, mission.blocks);
        assertEquals(peer.bodies[0].length + peer.bodies[1].length, mission.done);
        assertEquals(peer.bodies[0].length + peer.bodies[1].length, mission.getLength());
    }

    private static void assertComplete(final DownloadMission mission, final Peer peer,
                                       final File target) throws IOException {
        assertTrue(mission.isFinished());
        assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
        assertEquals(peer.completeBody().length, mission.done);
        assertEquals(peer.completeBody().length, mission.getLength());
        assertArrayEquals(peer.completeBody(), Files.readAllBytes(target.toPath()));
    }

    private Peer peer(final boolean identity) throws IOException {
        final Peer peer = new Peer(identity, asynchronousFailure);
        peers.add(peer);
        return peer;
    }

    private ControlledMission mission(final Peer peer, final File target, final Gate gate,
                                      final Postprocessing postprocessing) throws Exception {
        final ControlledMission mission;
        try (var logging = mockStatic(Log.class)) {
            mission = new ControlledMission(new String[]{peer.url(0), peer.url(1)}, gate,
                    postprocessing);
        }
        attach(mission, target, null);
        mission.threadCount = 2;
        // The actual initial durable save and subsequent saves use the same JVM adapter.
        assertTrue(mission.writeThisToFile());
        mission.checkpointReady = true;
        return mission;
    }

    private void attach(final ControlledMission mission, final File target,
                        final PostprocessingGate postprocessingGate) throws Exception {
        missions.add(mission);
        mission.metadata = folder.newFile();
        mission.checkpointReady = true;
        mission.maxRetry = 0;
        mission.threads = new Thread[0]; // DownloadManager's cold-loader transient initialization.
        mission.failure = asynchronousFailure;
        mission.owner = Thread.currentThread();
        mission.created = Collections.synchronizedList(new ArrayList<>());
        mission.events = new Events();
        mission.mHandler = mission.events.handler;
        mission.postprocessingGate = postprocessingGate;
        attachStorage(mission, target, postprocessingGate);
    }

    private static void attachStorage(final ControlledMission mission, final File target,
                                      final PostprocessingGate postprocessingGate)
            throws IOException {
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.existsAsFile()).thenReturn(true);
        when(storage.canWrite()).thenReturn(true);
        when(storage.getStream()).thenAnswer(ignored -> new FileStream(target));
        when(storage.getName()).thenAnswer(ignored -> {
            if (postprocessingGate != null && Arrays.stream(
                    Thread.currentThread().getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals(DownloadMission.class.getName())
                                    && frame.getMethodName().equals("doPostprocessing"))) {
                // Name lookup precedes Android Log.d and psState=1. Hold the real dispatch
                // here, then deliberately stop it during cleanup: no media algorithm is tested.
                postprocessingGate.owner = Thread.currentThread();
                Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
                    if (!(error instanceof StopPostprocessing)) {
                        mission.failure.compareAndSet(null, error);
                    }
                });
                postprocessingGate.entered.countDown();
                await(postprocessingGate.release, "release pending postprocessing fixture");
                throw new StopPostprocessing();
            }
            return target.getName();
        });
        mission.storage = storage;
    }

    private static void start(final ControlledMission mission) {
        try (var logging = mockStatic(Log.class)) {
            mission.start();
        }
    }

    private static void awaitFinished(final ControlledMission mission) throws Exception {
        await(mission.events.terminal, "download terminal callback");
        mission.joinCreatedThreads();
        assertEquals(DownloadMission.ERROR_NOTHING, mission.errCode);
        assertEquals(1, mission.events.finished.get());
    }

    private static ControlledMission read(final File checkpoint) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(
                checkpoint.toPath()))) {
            return (ControlledMission) input.readObject();
        }
    }

    private static ControlledMission read(final byte[] checkpoint) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(checkpoint))) {
            return (ControlledMission) input.readObject();
        }
    }

    private static void await(final CountDownLatch latch, final String description) {
        try {
            assertTrue("timed out: " + description,
                    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(description, error);
        }
    }

    private static final class Events {
        private final Handler handler = mock(Handler.class);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicInteger finished = new AtomicInteger();

        Events() {
            when(handler.obtainMessage(anyInt(), any())).thenAnswer(call -> {
                final int code = call.getArgument(0);
                if (code == DownloadManagerService.MESSAGE_FINISHED) {
                    finished.incrementAndGet();
                    terminal.countDown();
                } else if (code == DownloadManagerService.MESSAGE_ERROR) {
                    terminal.countDown();
                }
                return mock(Message.class);
            });
        }
    }

    private static final class Gate {
        private final boolean holdProgress;
        private final AtomicBoolean selectedWriter = new AtomicBoolean();
        private final AtomicBoolean selectedTransition = new AtomicBoolean();
        private final AtomicBoolean heldProgress = new AtomicBoolean();
        private final CountDownLatch writerEntered = new CountDownLatch(1);
        private final CountDownLatch releaseWriter = new CountDownLatch(1);
        private final CountDownLatch writerSaved = new CountDownLatch(1);
        private final CountDownLatch transitionEntered = new CountDownLatch(1);
        private final CountDownLatch releaseTransition = new CountDownLatch(1);
        private volatile boolean saved;
        private volatile byte[] snapshot;

        Gate(final boolean holdProgress) {
            this.holdProgress = holdProgress;
        }
    }

    private static final class PostprocessingGate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread owner;
    }

    private static final class StopPostprocessing extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class ControlledMission extends DownloadMission {
        private static final long serialVersionUID = 1L;
        private transient Gate gate;
        private transient Events events;
        private transient PostprocessingGate postprocessingGate;
        private transient AtomicReference<Throwable> failure;
        private transient List<Thread> created;
        private transient Thread owner;
        private transient boolean pauseOnBProgress;
        private transient CountDownLatch partialPaused = new CountDownLatch(1);

        ControlledMission(final String[] urls, final Gate gate,
                          final Postprocessing postprocessing) {
            super(urls, null, 'v', postprocessing);
            this.gate = gate;
        }

        @Override
        boolean writeThisToFile() {
            final Gate schedule = gate;
            final Thread caller = Thread.currentThread();
            final boolean asynchronousWriter = caller != owner && !created.contains(caller);
            if (asynchronousWriter) {
                remember(caller);
            }
            final boolean selected = schedule != null && current == 0 && asynchronousWriter
                    && schedule.selectedWriter.compareAndSet(false, true);
            if (selected) {
                schedule.writerEntered.countDown();
                await(schedule.releaseWriter, "release queued progress writer before LOCK");
            }
            try (var utility = mockStatic(Utility.class)) {
                utility.when(() -> Utility.writeToFile(any(File.class), any()))
                        .thenAnswer(call -> {
                            final File file = call.getArgument(0);
                            final boolean saved = serialize(file, call.getArgument(1));
                            if (selected) {
                                // Copy while production LOCK still protects this exact save.
                                schedule.snapshot = Files.readAllBytes(file.toPath());
                            }
                            return saved;
                        });
                final boolean saved = super.writeThisToFile();
                if (selected) {
                    schedule.saved = saved;
                    schedule.writerSaved.countDown();
                }
                return saved;
            }
        }

        /*
         * JVM adapter at the production Utility boundary, inside production LOCK. Real Java
         * serialization persists every mission field; only the Android storage mock is omitted
         * and reattached like the loader's storage adapter. Android AtomicFile is NOT exercised.
         */
        private static boolean serialize(final File file, final Object mission) throws IOException {
            try (ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(file)) {
                {
                    enableReplaceObject(true);
                }

                @Override
                protected Object replaceObject(final Object object) {
                    return object instanceof StoredFileHelper ? null : object;
                }
            }) {
                output.writeObject(mission);
            }
            return true;
        }

        @Override
        synchronized void notifyProgress(final long delta) {
            super.notifyProgress(delta);
            if (gate != null && current == 0
                    && gate.heldProgress.compareAndSet(false, true)) {
                // Reserve the queued writer before A can finish, even on a busy executor.
                await(gate.writerEntered, "progress writer owns its scheduling gate");
                if (gate.holdProgress) {
                    await(gate.writerSaved, "checkpoint before allowing A worker to continue");
                }
            }
            if (pauseOnBProgress && current == 1 && delta > 0) {
                pauseOnBProgress = false;
                pause();
                partialPaused.countDown();
            }
        }

        @Override
        Thread createInitializer() {
            if (gate != null && current == 1
                    && gate.selectedTransition.compareAndSet(false, true)) {
                gate.transitionEntered.countDown();
                await(gate.releaseTransition, "release original B initializer");
            }
            return remember(new DownloadInitializer(this) {
                @Override public void run() {
                    runLogged(super::run);
                }
            });
        }

        @Override
        Thread createDownloadWorker(final int index) {
            return remember(new DownloadRunnable(this, index) {
                @Override public void run() {
                    runLogged(super::run);
                }
            });
        }

        @Override
        Thread createFallbackWorker() {
            return remember(new DownloadRunnableFallback(this) {
                @Override public void run() {
                    runLogged(super::run);
                }
            });
        }

        private void runLogged(final Runnable action) {
            try (var logging = mockStatic(Log.class)) {
                action.run();
            } catch (final Throwable error) {
                failure.compareAndSet(null, error);
                events.terminal.countDown();
            }
        }

        private Thread remember(final Thread thread) {
            if (created == null) {
                created = Collections.synchronizedList(new ArrayList<>());
            }
            created.add(thread);
            return thread;
        }

        private void joinCreatedThreads() throws InterruptedException {
            if (created != null) {
                final Thread[] snapshot;
                synchronized (created) {
                    snapshot = created.toArray(new Thread[0]);
                }
                for (final Thread thread : snapshot) {
                    thread.join(5000);
                    assertFalse("worker/initializer did not stop: " + thread, thread.isAlive());
                }
            }
        }

        private void stopAndJoin() throws InterruptedException {
            if (created != null) {
                final Thread[] snapshot;
                synchronized (created) {
                    snapshot = created.toArray(new Thread[0]);
                }
                for (final Thread thread : snapshot) {
                    thread.interrupt();
                }
                joinCreatedThreads();
            }
            if (postprocessingGate != null && postprocessingGate.owner != null) {
                postprocessingGate.owner.join(5000);
                assertFalse(postprocessingGate.owner.isAlive());
            }
        }
    }

    private static final class Peer implements AutoCloseable {
        private final byte[][] bodies = {pattern(DownloadMission.BLOCK_SIZE + 137, 31),
                pattern(200_003, 73)};
        private final AtomicInteger[] gets = {new AtomicInteger(), new AtomicInteger()};
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
        private final ServerSocket listener;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final boolean identity;
        private final AtomicReference<Throwable> failure;

        Peer(final boolean identity, final AtomicReference<Throwable> failure) throws IOException {
            this.identity = identity;
            this.failure = failure;
            listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            executor.execute(() -> {
                while (!listener.isClosed()) {
                    try {
                        final Socket socket = listener.accept();
                        sockets.add(socket);
                        executor.execute(() -> serve(socket));
                    } catch (final IOException closed) {
                        return;
                    }
                }
            });
        }

        private String url(final int resource) {
            return "http://127.0.0.1:" + listener.getLocalPort() + "/resource" + resource;
        }

        private void serve(final Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(5000);
                final BufferedReader input = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.US_ASCII));
                final String[] request = input.readLine().split(" ", 3);
                final String method = request[0];
                final int resource = request[1].equals("/resource0") ? 0 : 1;
                final Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) {
                    final int colon = line.indexOf(':');
                    headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
                final String range = headers.get("range");
                requests.add(method + " " + request[1] + " Range=" + range
                        + " If-Range=" + headers.get("if-range"));
                if (method.equals("GET")) {
                    gets[resource].incrementAndGet();
                }
                final byte[] body = bodies[resource];
                int start = 0;
                int end = body.length - 1;
                int status = 200;
                if (range != null && !(method.equals("HEAD") && range.equals("bytes=0-"))) {
                    final String[] bounds = range.substring(6).split("-", -1);
                    start = Integer.parseInt(bounds[0]);
                    if (!bounds[1].isEmpty()) {
                        end = Math.min(end, Integer.parseInt(bounds[1]));
                    }
                    status = start > end ? 416 : 206;
                }
                final int length = status == 416 ? 0 : end - start + 1;
                final String response = "HTTP/1.1 " + status + " Response\r\n"
                        + "Connection: close\r\nContent-Length: " + length + "\r\n"
                        + (identity ? "ETag: \"resource-" + resource + "-v1\"\r\n" : "")
                        + (status == 206 ? "Content-Range: bytes " + start + "-" + end + "/"
                                + body.length + "\r\n" : "") + "\r\n";
                final OutputStream output = connection.getOutputStream();
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                if (!method.equals("HEAD") && status != 416) {
                    output.write(body, start, length);
                }
                output.flush();
            } catch (final IOException disconnected) {
                // Initializer HEAD probes and intentionally paused workers disconnect early.
            } catch (final Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                sockets.remove(socket);
            }
        }

        private byte[] completeBody() {
            final byte[] complete = Arrays.copyOf(bodies[0], bodies[0].length + bodies[1].length);
            System.arraycopy(bodies[1], 0, complete, bodies[0].length, bodies[1].length);
            return complete;
        }

        private static byte[] pattern(final int length, final int multiplier) {
            final byte[] body = new byte[length];
            for (int index = 0; index < length; index++) {
                body[index] = (byte) (index * multiplier + index / 101);
            }
            return body;
        }

        @Override
        public void close() throws Exception {
            listener.close();
            synchronized (sockets) {
                for (final Socket socket : sockets) {
                    socket.close();
                }
            }
            executor.shutdownNow();
            assertTrue("HTTP fixture threads did not stop",
                    executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
