package org.schabi.newpipe.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.SponsorBlockCategory;
import org.schabi.newpipe.util.SponsorBlockSegment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.MissionState;

/** Real Android file helpers; manager, picker and dispatch effects are isolated test adapters. */
public class DownloadPreparationIntegrationTest {
    private Context context;
    private Path directory;
    private Environment environment;
    private DownloadPreparation preparation;
    private DownloadPreparation.Selection selected;

    @Before
    public void setup() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "download-test-");
        environment = new Environment();
        selected = originalSelection();
        preparation = new DownloadPreparation(environment, () -> selected);
    }

    @After
    public void cleanup() throws IOException {
        if (preparation != null) {
            preparation.close();
        }
        if (directory != null) {
            try (var paths = Files.walk(directory)) {
                for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void unsupportedSecondaryDoesNotTruncateFileOrForgetMission() throws Exception {
        final Path existing = directory.resolve("original.mp4");
        Files.writeString(existing, "existing recording");
        environment.protectedUri = Uri.fromFile(existing.toFile());
        environment.state = MissionState.Finished;
        selected = new DownloadPreparation.Selection(DownloadPreparation.Kind.VIDEO,
                video(), audio(DeliveryMethod.HLS), 100, 47, 5,
                info("original"), segments());

        preparation.save("original", DownloadPreparation.Destination.askDocument(), environment);
        // Drive any offered decisions as well: validation must precede destructive effects,
        // regardless of whether a picker is needed by the implementation.
        if (environment.location != null) {
            environment.location.complete(environment.protectedUri);
        }
        if (environment.collision != null) {
            environment.collision.confirm();
        }

        assertEquals(1, environment.failures.size());
        assertEquals(DownloadPreparation.FailureReason.INVALID_SELECTION,
                environment.failures.get(0).reason);
        assertNotNull(environment.failures.get(0).cause);
        assertEquals("existing recording", Files.readString(existing));
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.submissions);
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    public void serializedPickerUsesOriginalSelectionAndConsumesDuplicateDecisionsOnce()
            throws Exception {
        final Path existing = directory.resolve("original.mp4");
        Files.writeString(existing, "existing recording");
        environment.protectedUri = Uri.fromFile(existing.toFile());
        preparation.save("original", DownloadPreparation.Destination.savedFolder(null),
                environment);
        assertNotNull(environment.location);
        selected.segments[0] = new SponsorBlockSegment(500, 900, SponsorBlockCategory.INTRO);
        final DownloadPreparation.Location saved = serialized(environment.location.state());
        final DownloadPreparation.Location duplicate = serialized(saved);
        preparation.close();

        selected = new DownloadPreparation.Selection(DownloadPreparation.Kind.AUDIO,
                audio(DeliveryMethod.PROGRESSIVE_HTTP), null, 900, 0, 2,
                info("different current selection"),
                new SponsorBlockSegment[]{new SponsorBlockSegment(500, 900,
                        SponsorBlockCategory.INTRO)});
        environment = new Environment();
        environment.protectedUri = Uri.fromFile(existing.toFile());
        environment.state = MissionState.Pending;
        preparation = new DownloadPreparation(environment, () -> selected);

        preparation.resume(saved, environment.folder.getUri(), environment);
        assertNotNull(environment.collision);
        assertEquals(DownloadPreparation.CollisionReason.PENDING, environment.collision.reason);
        final DownloadPreparation.CollisionRequest confirmation = environment.collision;
        confirmation.confirm();

        assertEquals(1, environment.launches.size());
        final DownloadPreparation.Launch launch = environment.launches.get(0);
        assertArrayEquals(new String[]{"https://example.invalid/original-video",
                "https://example.invalid/original-audio"}, launch.urls);
        assertEquals('v', launch.kind);
        assertEquals(5, launch.threads);
        assertEquals(147, launch.nearLength);
        assertEquals(Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER, launch.psName);
        assertEquals("original", launch.info.getName());
        assertEquals("https://example.invalid/watch/original", launch.info.getUrl());
        assertEquals(120, launch.info.getDuration());
        assertArrayEquals(segments(), launch.segments);
        assertEquals(2, launch.recoveryInfo.size());
        assertEquals('v', launch.recoveryInfo.get(0).getKind());
        assertEquals("720p", launch.recoveryInfo.get(0).getDesired());
        assertEquals('a', launch.recoveryInfo.get(1).getKind());
        assertEquals(128, launch.recoveryInfo.get(1).getDesiredBitrate());
        assertEquals(Uri.fromFile(existing.toFile()), launch.storage.getUri());
        assertEquals("original.mp4", launch.storage.getName());
        assertEquals(0, Files.size(existing));
        assertEquals(1, environment.forgotten.size());
        assertEquals(1, environment.submissions);
        assertTrue(environment.failures.isEmpty());

        // A repeated callback must not truncate bytes written by the accepted mission.
        Files.writeString(existing, "accepted mission is writing");
        confirmation.confirm();
        preparation.resume(saved, environment.folder.getUri(), environment);
        preparation.resume(duplicate, environment.folder.getUri(), environment);
        assertEquals("accepted mission is writing", Files.readString(existing));
        assertEquals(1, environment.launches.size());
        assertEquals(1, environment.forgotten.size());
        assertEquals(1, environment.submissions);
        assertEquals(1, environment.collisionCount);
    }

    @Test
    public void serializedDocumentPickerCreatesRealFileFromOriginalSelectionOnce()
            throws Exception {
        preparation.save("original", DownloadPreparation.Destination.askDocument(), environment);
        final DownloadPreparation.Location saved = serialized(environment.location.state());
        final DownloadPreparation.Location duplicate = serialized(saved);
        preparation.close();
        selected = new DownloadPreparation.Selection(DownloadPreparation.Kind.AUDIO,
                audio(DeliveryMethod.PROGRESSIVE_HTTP), null, 900, 0, 2,
                info("changed"), new SponsorBlockSegment[0]);
        environment = new Environment();
        preparation = new DownloadPreparation(environment, () -> selected);
        final Path picked = directory.resolve("picked-name.mp4");
        final Uri uri = Uri.fromFile(picked.toFile());

        preparation.resume(saved, uri, environment);

        assertTrue(Files.isRegularFile(picked));
        assertEquals(0, Files.size(picked));
        assertEquals(1, environment.launches.size());
        final DownloadPreparation.Launch launch = environment.launches.get(0);
        assertEquals(uri, launch.storage.getUri());
        assertEquals("picked-name.mp4", launch.storage.getName());
        assertEquals("original", launch.info.getName());
        assertEquals('v', launch.kind);
        assertEquals(5, launch.threads);
        assertArrayEquals(segments(), launch.segments);
        assertEquals(147, launch.nearLength);
        assertEquals(2, launch.recoveryInfo.size());
        assertTrue(environment.failures.isEmpty());

        Files.writeString(picked, "accepted mission is writing");
        preparation.resume(duplicate, uri, environment);
        assertEquals("accepted mission is writing", Files.readString(picked));
        assertEquals(1, environment.launches.size());
        assertEquals(1, environment.submissions);
        assertTrue(environment.forgotten.isEmpty());
    }

    @Test
    public void runningMissionAtConfirmationPreservesOriginalAndUsesRealUniqueFile()
            throws Exception {
        final Path existing = directory.resolve("original.mp4");
        Files.writeString(existing, "running mission bytes");
        environment.protectedUri = Uri.fromFile(existing.toFile());
        environment.state = MissionState.Pending;
        preparation.save("original", DownloadPreparation.Destination.savedFolder(null),
                environment);
        environment.location.complete(environment.folder.getUri());
        assertEquals(DownloadPreparation.CollisionReason.PENDING, environment.collision.reason);
        final DownloadPreparation.CollisionRequest pending = environment.collision;

        environment.state = MissionState.PendingRunning;
        pending.confirm();
        assertEquals("running mission bytes", Files.readString(existing));
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
        assertEquals(DownloadPreparation.CollisionReason.RUNNING, environment.collision.reason);
        assertEquals(DownloadPreparation.CollisionAction.UNIQUE_NAME,
                environment.collision.action);
        final DownloadPreparation.CollisionRequest running = environment.collision;
        running.confirm();
        running.confirm();
        pending.confirm();

        assertEquals(1, environment.launches.size());
        final DownloadPreparation.Launch launch = environment.launches.get(0);
        assertEquals("original(1).mp4", launch.storage.getName());
        assertEquals(Uri.fromFile(directory.resolve("original(1).mp4").toFile()),
                launch.storage.getUri());
        assertTrue(launch.storage.existsAsFile());
        assertTrue(launch.storage.canWrite());
        assertEquals(0, launch.storage.length());
        assertEquals("running mission bytes", Files.readString(existing));
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.failures.isEmpty());
        assertEquals(1, environment.submissions);
    }

    @Test
    public void closedModuleIgnoresLateRealDocumentResult() throws Exception {
        final Path existing = directory.resolve("original.mp4");
        Files.writeString(existing, "existing recording");
        environment.protectedUri = Uri.fromFile(existing.toFile());
        environment.state = MissionState.Finished;
        preparation.save("original", DownloadPreparation.Destination.askDocument(), environment);
        final DownloadPreparation.LocationRequest request = environment.location;
        assertNotNull(request);
        preparation.close();
        request.complete(environment.protectedUri);
        request.complete(environment.protectedUri);

        assertEquals("existing recording", Files.readString(existing));
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.submissions);
        assertEquals(0, environment.collisionCount);
    }

    private static DownloadPreparation.Location serialized(final DownloadPreparation.Location state)
            throws IOException, ClassNotFoundException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(state);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (DownloadPreparation.Location) input.readObject();
        }
    }

    private static DownloadPreparation.Selection originalSelection() {
        return new DownloadPreparation.Selection(DownloadPreparation.Kind.VIDEO, video(),
                audio(DeliveryMethod.PROGRESSIVE_HTTP), 100, 47, 5, info("original"), segments());
    }

    private static StreamInfo info(final String title) {
        final String url = "https://example.invalid/watch/" + title;
        final StreamInfo info = new StreamInfo(0, url, url, StreamType.VIDEO_STREAM,
                title, title, 0);
        info.setDuration(120);
        info.setUploaderName("fixture uploader");
        return info;
    }

    private static SponsorBlockSegment[] segments() {
        return new SponsorBlockSegment[]{new SponsorBlockSegment(1000, 2000,
                SponsorBlockCategory.SPONSOR)};
    }

    private static VideoStream video() {
        return new VideoStream.Builder().setId("fixture-video")
                .setContent("https://example.invalid/original-video", true)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(true).setResolution("720p").build();
    }

    private static AudioStream audio(final DeliveryMethod method) {
        return new AudioStream.Builder().setId("fixture-audio")
                .setContent("https://example.invalid/original-audio", true)
                .setMediaFormat(MediaFormat.M4A).setDeliveryMethod(method)
                .setAverageBitrate(128).build();
    }

    private final class Environment implements DownloadPreparation.Platform,
            DownloadPreparation.Presentation {
        final StoredDirectoryHelper folder;
        final List<DownloadPreparation.Launch> launches = new ArrayList<>();
        final List<DownloadPreparation.Failure> failures = new ArrayList<>();
        final List<StoredFileHelper> forgotten = new ArrayList<>();
        final List<DownloadPreparation.Kind> remembered = new ArrayList<>();
        DownloadPreparation.LocationRequest location;
        DownloadPreparation.CollisionRequest collision;
        Uri protectedUri;
        MissionState state = MissionState.None;
        int submissions;
        int collisionCount;

        Environment() throws IOException {
            folder = new StoredDirectoryHelper(context, Uri.fromFile(directory.toFile()), "video");
        }

        @Override
        public StoredDirectoryHelper pickedFolder(final Uri uri,
                                                  final DownloadPreparation.Kind kind)
                throws IOException {
            return new StoredDirectoryHelper(context, uri, "video");
        }

        @Override
        public DownloadPreparation.DocumentMetadata documentMetadata(final Uri uri) {
            return new DownloadPreparation.DocumentMetadata(
                    Path.of(uri.getPath()).getFileName().toString(), "video/mp4");
        }

        @Override
        public StoredFileHelper file(final StoredDirectoryHelper parent, final Uri uri)
                throws IOException {
            return new StoredFileHelper(context, parent == null ? null : parent.getUri(),
                    uri, "video");
        }

        @Override
        public MissionState missionState(final StoredFileHelper file) {
            return !file.isInvalid() && file.getUri().equals(protectedUri)
                    ? state : MissionState.None;
        }

        @Override
        public void forget(final StoredFileHelper file) {
            forgotten.add(file);
        }

        @Override
        public void dispatch(final DownloadPreparation.Launch launch) {
            launches.add(launch);
        }

        @Override
        public void rememberKind(final DownloadPreparation.Kind kind) {
            remembered.add(kind);
        }

        @Override
        public void chooseLocation(final DownloadPreparation.LocationRequest request) {
            location = request;
        }

        @Override
        public void confirmCollision(final DownloadPreparation.CollisionRequest request) {
            collision = request;
            collisionCount++;
        }

        @Override
        public void submitted() {
            submissions++;
        }

        @Override
        public void failed(final DownloadPreparation.Failure failure) {
            failures.add(failure);
        }
    }
}
