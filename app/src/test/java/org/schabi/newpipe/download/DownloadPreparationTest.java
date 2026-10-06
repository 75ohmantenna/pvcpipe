package org.schabi.newpipe.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.SponsorBlockCategory;
import org.schabi.newpipe.util.SponsorBlockSegment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.List;

import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.MissionState;

public class DownloadPreparationTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private DownloadPreparationEnvironment environment;
    private DownloadPreparation preparation;
    private DownloadPreparation.Selection selected;
    private final StreamInfo info = new StreamInfo(0, "https://example.com/watch",
            "https://example.com/watch", StreamType.VIDEO_STREAM, "id", "recording", 0);
    private final SponsorBlockSegment[] segments = {
            new SponsorBlockSegment(1000, 2000, SponsorBlockCategory.SPONSOR)};

    @Before
    public void setUp() throws Exception {
        environment = new DownloadPreparationEnvironment(temporary.newFolder().toPath());
        select(DownloadPreparation.Kind.AUDIO, audio(MediaFormat.M4A), null, 100, 0);
        preparation = new DownloadPreparation(environment, () -> selected);
    }

    @Test
    public void audioNamesAndExtractionAlgorithmsMatchOutputFormats() throws Exception {
        final MediaFormat[] formats = {MediaFormat.WEBMA_OPUS, MediaFormat.M4A};
        final String[] names = {"recording.opus", "recording.m4a"};
        final String[] algorithms = {Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER,
                Postprocessing.ALGORITHM_M4A_NO_DASH};
        for (int index = 0; index < formats.length; index++) {
            select(DownloadPreparation.Kind.AUDIO, audio(formats[index]), null, 100, 0);
            preparation.save("recording", DownloadPreparation.Destination.askDocument(),
                    environment);
            assertEquals(names[index], environment.location.filename);
            assertEquals(index == 0 ? "audio/ogg" : "audio/mp4", environment.location.mime);
            environment.location.complete(environment.fileUri);
            final DownloadPreparation.Launch launch = environment.launches.get(index);
            assertEquals(algorithms[index], launch.psName);
            assertEquals('a', launch.kind);
            assertEquals(5, launch.threads);
            assertEquals(1, launch.recoveryInfo.size());
            assertEquals(128, launch.recoveryInfo.get(0).getDesiredBitrate());
            assertSame(info, launch.info);
            assertArrayEquals(segments, launch.segments);
        }
        assertEquals(2, environment.submissions);
    }

    @Test
    public void pairedMp4CarriesOrderedUrlsSizesAndRecovery() throws Exception {
        final VideoStream video = video(MediaFormat.MPEG_4, DeliveryMethod.PROGRESSIVE_HTTP);
        final AudioStream audio = audio(MediaFormat.M4A);
        select(DownloadPreparation.Kind.VIDEO, video, audio, 100, 47);
        final DownloadPreparation.Launch launch = submitDocument();
        assertArrayEquals(new String[]{video.getContent(), audio.getContent()}, launch.urls);
        assertEquals(Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER, launch.psName);
        assertEquals(147, launch.nearLength);
        assertEquals('v', launch.recoveryInfo.get(0).getKind());
        assertEquals("720p", launch.recoveryInfo.get(0).getDesired());
        assertEquals('a', launch.recoveryInfo.get(1).getKind());
    }

    @Test
    public void pairedWebmNeedsBothKnownSizesForEstimate() throws Exception {
        select(DownloadPreparation.Kind.VIDEO,
                video(MediaFormat.WEBM, DeliveryMethod.PROGRESSIVE_HTTP),
                audio(MediaFormat.WEBMA), -1, 47);
        final DownloadPreparation.Launch launch = submitDocument();
        assertEquals(Postprocessing.ALGORITHM_WEBM_MUXER, launch.psName);
        assertEquals(0, launch.nearLength);
        assertEquals(2, launch.urls.length);
    }

    @Test
    public void hlsVideoSelectsRemuxer() throws Exception {
        select(DownloadPreparation.Kind.VIDEO, video(MediaFormat.MPEG_4, DeliveryMethod.HLS),
                null, 100, 0);
        final DownloadPreparation.Launch launch = submitDocument();
        assertEquals(Postprocessing.ALGORITHM_PVC_HLS_REMUXER, launch.psName);
        assertEquals('v', launch.kind);
        assertEquals(1, launch.urls.length);
    }

    @Test
    public void ttmlUsesSrtNameConverterAndOneThread() throws Exception {
        final SubtitlesStream subtitles = new SubtitlesStream.Builder()
                .setContent("https://example.com/subtitles", true)
                .setMediaFormat(MediaFormat.TTML)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setLanguageCode("en").setAutoGenerated(false).build();
        select(DownloadPreparation.Kind.SUBTITLE, subtitles, null, 100, 0);
        preparation.save("captions", DownloadPreparation.Destination.askDocument(), environment);
        assertEquals("captions.srt", environment.location.filename);
        assertEquals(MediaFormat.TTML.mimeType, environment.location.mime);
        environment.location.complete(environment.fileUri);
        final DownloadPreparation.Launch launch = environment.launches.get(0);
        assertEquals(Postprocessing.ALGORITHM_TTML_CONVERTER, launch.psName);
        assertArrayEquals(new String[]{"ttml", "false"}, launch.psArgs);
        assertEquals(1, launch.threads);
        assertEquals('s', launch.kind);
        assertEquals("en", launch.recoveryInfo.get(0).getDesired());
    }

    @Test
    public void unusedFolderDestinationCreatesFileAndRemembersKind() throws Exception {
        saveToFolder();
        assertEquals("recording.m4a", environment.createdName);
        assertEquals("audio/mp4", environment.createdMime);
        assertTrue(Files.isRegularFile(environment.output));
        assertEquals(0, Files.size(environment.output));
        assertEquals(List.of(DownloadPreparation.Kind.AUDIO), environment.remembered);
        assertEquals(1, environment.launches.size());
    }

    @Test
    public void missingFolderRequestsPickerThenContinuesWithPickedFolder() throws Exception {
        preparation.save("recording", DownloadPreparation.Destination.savedFolder(null),
                environment);
        assertEquals(DownloadPreparation.LocationType.FOLDER, environment.location.type);
        assertEquals(DownloadPreparation.Kind.AUDIO, environment.location.kind);
        assertFalse(Files.exists(environment.output));
        environment.location.complete(environment.folderUri);
        assertTrue(Files.exists(environment.output));
        assertEquals(1, environment.launches.size());
        assertTrue(environment.remembered.isEmpty());
    }

    @Test
    public void saveAsCreatesPickedDocumentWithoutAnExtraOverwritePrompt() throws Exception {
        final DownloadPreparation.Launch launch = submitDocument();
        assertEquals(DownloadPreparation.LocationType.DOCUMENT, environment.location.type);
        assertSame(environment.storage, launch.storage);
        assertTrue(Files.exists(environment.output));
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.remembered.isEmpty());
    }

    @Test
    public void insufficientSpaceDoesNotCreateOrLaunch() throws Exception {
        when(environment.folder.getFreeStorageSpace()).thenReturn(100L);
        saveToFolder();
        assertEquals(DownloadPreparation.FailureReason.INSUFFICIENT_STORAGE,
                environment.failures.get(0).reason);
        assertFalse(Files.exists(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertTrue(environment.remembered.isEmpty());
    }

    @Test
    public void leavingCollisionUnconfirmedPreservesFileAndMission() throws Exception {
        Files.writeString(environment.output, "existing recording");
        environment.state = MissionState.Finished;
        saveToFolder();
        assertEquals(DownloadPreparation.CollisionReason.FINISHED, environment.collision.reason);
        assertEquals(DownloadPreparation.CollisionAction.OVERWRITE, environment.collision.action);
        assertEquals("existing recording", Files.readString(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertTrue(environment.forgotten.isEmpty());
    }

    @Test
    public void confirmedPendingReplacementForgetsMissionAndTruncatesOutput() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        assertEquals(DownloadPreparation.CollisionReason.PENDING, environment.collision.reason);
        environment.collision.confirm();
        assertEquals(List.of(environment.storage), environment.forgotten);
        assertEquals(0, Files.size(environment.output));
        assertEquals(1, environment.launches.size());
    }

    @Test
    public void runningMissionUsesSeparateUniqueFile() throws Exception {
        Files.writeString(environment.output, "running recording");
        environment.state = MissionState.PendingRunning;
        saveToFolder();
        assertEquals(DownloadPreparation.CollisionAction.UNIQUE_NAME, environment.collision.action);
        environment.collision.confirm();
        assertEquals("running recording", Files.readString(environment.output));
        assertSame(environment.uniqueStorage, environment.launches.get(0).storage);
        assertTrue(Files.exists(environment.uniqueOutput));
        assertTrue(environment.forgotten.isEmpty());
    }

    @Test
    public void saveAsCannotReplaceRunningMissionOrGenerateSiblingName() throws Exception {
        Files.writeString(environment.output, "running recording");
        environment.state = MissionState.PendingRunning;
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        environment.location.complete(environment.fileUri);
        assertEquals(DownloadPreparation.CollisionReason.RUNNING, environment.collision.reason);
        assertEquals(DownloadPreparation.CollisionAction.NONE, environment.collision.action);
        environment.collision.confirm();
        assertEquals("running recording", Files.readString(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertFalse(Files.exists(environment.uniqueOutput));
    }

    @Test
    public void pickerContinuationUsesCapturedSelectionDespiteLaterUiChanges() throws Exception {
        final AudioStream original = (AudioStream) selected.primary;
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        select(DownloadPreparation.Kind.AUDIO, audio(MediaFormat.WEBMA_OPUS), null, 100, 0);
        environment.location.complete(environment.fileUri);
        assertArrayEquals(new String[]{original.getContent()}, environment.launches.get(0).urls);
        assertEquals(Postprocessing.ALGORITHM_M4A_NO_DASH, environment.launches.get(0).psName);
    }

    @Test
    public void unsupportedSecondaryIsRejectedBeforePickerOrTruncation() throws Exception {
        Files.writeString(environment.output, "existing recording");
        selectUnsupportedSecondary();
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        assertEquals(DownloadPreparation.FailureReason.INVALID_SELECTION,
                environment.failures.get(0).reason);
        assertNotNull(environment.failures.get(0).cause);
        assertNull(environment.location);
        assertEquals("existing recording", Files.readString(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertTrue(environment.forgotten.isEmpty());
        assertEquals(0, environment.resolutions);
        assertEquals(0, environment.submissions);
    }

    @Test
    public void failedReacquisitionRetainsPendingMissionAndOutput() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        environment.failResolve = true;
        environment.collision.confirm();
        assertTrue(environment.forgotten.isEmpty());
        assertEquals(MissionState.Pending, environment.state);
        assertEquals("pending recording", Files.readString(environment.output));
        assertEquals(DownloadPreparation.FailureReason.FILE_CREATION,
                environment.failures.get(0).reason);
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.submissions);
    }

    @Test
    public void unrelatedCollisionRequiresConsentWithoutForgettingMission() throws Exception {
        Files.writeString(environment.output, "unrelated recording");
        saveToFolder();
        assertEquals(DownloadPreparation.CollisionReason.UNRELATED, environment.collision.reason);
        assertEquals("unrelated recording", Files.readString(environment.output));
        environment.collision.confirm();
        assertEquals(0, Files.size(environment.output));
        assertTrue(environment.forgotten.isEmpty());
        assertEquals(1, environment.launches.size());
    }

    @Test
    public void documentWritePermissionFailurePreservesExistingOutput() throws Exception {
        Files.writeString(environment.output, "existing recording");
        environment.writable = false;
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        environment.location.complete(environment.fileUri);
        assertEquals(DownloadPreparation.FailureReason.PERMISSION_DENIED,
                environment.failures.get(0).reason);
        assertEquals("existing recording", Files.readString(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.submissions);
    }

    @Test
    public void truncateFailureReportsOverwriteAndDoesNotSubmit() throws Exception {
        Files.writeString(environment.output, "existing recording");
        environment.failTruncate = true;
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        environment.location.complete(environment.fileUri);
        assertEquals(DownloadPreparation.FailureReason.OVERWRITE,
                environment.failures.get(0).reason);
        assertNotNull(environment.failures.get(0).cause);
        assertEquals("existing recording", Files.readString(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.submissions);
    }

    @Test
    public void fileCreationFailureReportsErrorWithoutLaunching() throws Exception {
        environment.failCreate = true;
        saveToFolder();
        assertEquals(DownloadPreparation.FailureReason.FILE_CREATION,
                environment.failures.get(0).reason);
        assertFalse(Files.exists(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.submissions);
    }

    @Test
    public void documentPickerStateCanResumeAfterModuleRecreation() throws Exception {
        preparation.save("original", DownloadPreparation.Destination.askDocument(), environment);
        final DownloadPreparation.Location state = serialized(environment.location.state());
        final DownloadPreparationEnvironment restored =
                new DownloadPreparationEnvironment(environment.directory);
        final DownloadPreparation recreated = new DownloadPreparation(restored, () -> selected);
        assertEquals(DownloadPreparation.LocationType.DOCUMENT, state.type);
        assertEquals("original.m4a", state.filename);
        select(DownloadPreparation.Kind.AUDIO, audio(MediaFormat.WEBMA_OPUS), null, 100, 0);
        recreated.resume(state, restored.fileUri, restored);
        assertEquals(1, restored.launches.size());
        assertEquals(1, restored.submissions);
        assertTrue(environment.launches.isEmpty());
        assertSame(restored.storage, restored.launches.get(0).storage);
        assertArrayEquals(new String[]{audio(MediaFormat.M4A).getContent()},
                restored.launches.get(0).urls);
        assertEquals(info.getUrl(), restored.launches.get(0).info.getUrl());
        assertArrayEquals(segments, restored.launches.get(0).segments);
    }

    @Test
    public void folderPickerStateCanResumeAfterModuleRecreation() throws Exception {
        preparation.save("original", DownloadPreparation.Destination.savedFolder(null),
                environment);
        final DownloadPreparation.Location state = serialized(environment.location.state());
        final DownloadPreparationEnvironment restored =
                new DownloadPreparationEnvironment(environment.directory);
        final DownloadPreparation recreated = new DownloadPreparation(restored, () -> selected);
        assertEquals(DownloadPreparation.LocationType.FOLDER, state.type);
        select(DownloadPreparation.Kind.AUDIO, audio(MediaFormat.WEBMA_OPUS), null, 100, 0);
        recreated.resume(state, restored.folderUri, restored);
        assertEquals("original.m4a", restored.createdName);
        assertEquals("audio/mp4", restored.createdMime);
        assertArrayEquals(new String[]{audio(MediaFormat.M4A).getContent()},
                restored.launches.get(0).urls);
        assertEquals(1, restored.launches.size());
        assertEquals(1, restored.submissions);
        assertTrue(environment.launches.isEmpty());
    }

    @Test
    public void unsupportedSelectionCannotRecreateLookupMissedExistingOutput() throws Exception {
        Files.writeString(environment.output, "existing recording");
        environment.missingLookup = true;
        selectUnsupportedSecondary();
        saveToFolder();
        assertEquals(DownloadPreparation.FailureReason.INVALID_SELECTION,
                environment.failures.get(0).reason);
        assertEquals("existing recording", Files.readString(environment.output));
        assertNull(environment.createdName);
        assertTrue(environment.launches.isEmpty());
    }

    @Test
    public void unsupportedSelectionCannotForgetPendingMission() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        selectUnsupportedSecondary();
        saveToFolder();
        assertEquals(DownloadPreparation.FailureReason.INVALID_SELECTION,
                environment.failures.get(0).reason);
        assertNull(environment.collision);
        assertTrue(environment.forgotten.isEmpty());
        assertEquals(MissionState.Pending, environment.state);
        assertEquals("pending recording", Files.readString(environment.output));
    }

    @Test
    public void collisionConfirmationUsesCapturedSelection() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        final AudioStream original = (AudioStream) selected.primary;
        saveToFolder();
        select(DownloadPreparation.Kind.AUDIO, audio(MediaFormat.WEBMA_OPUS), null, 100, 0);
        environment.collision.confirm();
        assertArrayEquals(new String[]{original.getContent()}, environment.launches.get(0).urls);
        assertEquals(Postprocessing.ALGORITHM_M4A_NO_DASH, environment.launches.get(0).psName);
    }

    @Test
    public void capturedSegmentsSurviveArrayMutationWhilePickerIsOpen() throws Exception {
        final SponsorBlockSegment original = segments[0];
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        segments[0] = new SponsorBlockSegment(3000, 4000, SponsorBlockCategory.SPONSOR);
        environment.location.complete(environment.fileUri);
        assertArrayEquals(new SponsorBlockSegment[]{original},
                environment.launches.get(0).segments);
    }

    @Test
    public void completingLocationTwiceDoesNotTruncateOrLaunchAgain() throws Exception {
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        final DownloadPreparation.LocationRequest request = environment.location;
        request.complete(environment.fileUri);
        Files.writeString(environment.output, "admitted recording");
        request.complete(environment.fileUri);
        assertEquals(1, environment.launches.size());
        assertEquals(1, environment.submissions);
        assertEquals("admitted recording", Files.readString(environment.output));
    }

    @Test
    public void serializedLocationReplayDoesNotTruncateOrLaunchAgain() throws Exception {
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        final DownloadPreparation.Location original = environment.location.state();
        final DownloadPreparation.Location replay = serialized(original);
        preparation.resume(original, environment.fileUri, environment);
        Files.writeString(environment.output, "admitted recording");
        preparation.resume(replay, environment.fileUri, environment);
        assertEquals(1, environment.launches.size());
        assertEquals(1, environment.submissions);
        assertEquals("admitted recording", Files.readString(environment.output));
    }

    @Test
    public void confirmingCollisionTwiceDoesNotForgetOrLaunchAgain() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        final DownloadPreparation.CollisionRequest request = environment.collision;
        request.confirm();
        Files.writeString(environment.output, "admitted recording");
        request.confirm();
        assertEquals(1, environment.launches.size());
        assertEquals(1, environment.submissions);
        assertEquals(1, environment.forgotten.size());
        assertEquals("admitted recording", Files.readString(environment.output));
    }

    @Test
    public void pendingMissionThatStartsBeforeConsentRequiresUniqueNamePrompt() throws Exception {
        Files.writeString(environment.output, "running recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        final DownloadPreparation.CollisionRequest previous = environment.collision;
        environment.state = MissionState.PendingRunning;
        previous.confirm();
        assertNotSame(previous, environment.collision);
        assertEquals(DownloadPreparation.CollisionReason.RUNNING, environment.collision.reason);
        assertEquals(DownloadPreparation.CollisionAction.UNIQUE_NAME, environment.collision.action);
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
        assertEquals("running recording", Files.readString(environment.output));
        environment.collision.confirm();
        assertSame(environment.uniqueStorage, environment.launches.get(0).storage);
        assertEquals("running recording", Files.readString(environment.output));
        assertTrue(environment.forgotten.isEmpty());
    }

    @Test
    public void pickedDocumentThatStartsRunningCannotUseEarlierOverwriteConsent() throws Exception {
        Files.writeString(environment.output, "running recording");
        environment.state = MissionState.Pending;
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        environment.location.complete(environment.fileUri);
        final DownloadPreparation.CollisionRequest previous = environment.collision;
        environment.state = MissionState.PendingRunning;
        previous.confirm();
        assertNotSame(previous, environment.collision);
        assertEquals(DownloadPreparation.CollisionAction.NONE, environment.collision.action);
        environment.collision.confirm();
        assertEquals("running recording", Files.readString(environment.output));
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
    }

    @Test
    public void overwritePermissionFailureRetainsPendingMissionAndOutput() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        environment.writable = false;
        environment.collision.confirm();
        assertFalse(environment.failures.isEmpty());
        assertEquals(MissionState.Pending, environment.state);
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
        assertEquals("pending recording", Files.readString(environment.output));
        assertEquals(0, environment.submissions);
    }

    @Test
    public void overwriteTruncateFailureRetainsPendingMissionAndOutput() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        environment.failTruncate = true;
        environment.collision.confirm();
        assertEquals(DownloadPreparation.FailureReason.OVERWRITE,
                environment.failures.get(0).reason);
        assertEquals(MissionState.Pending, environment.state);
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
        assertEquals("pending recording", Files.readString(environment.output));
        assertEquals(0, environment.submissions);
    }

    @Test
    public void dispatchFailureRetainsPendingMissionAndDoesNotReportSuccess() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        environment.failDispatch = true;
        environment.collision.confirm();
        assertEquals(DownloadPreparation.FailureReason.DISPATCH,
                environment.failures.get(0).reason);
        assertNotNull(environment.failures.get(0).cause);
        assertEquals(MissionState.Pending, environment.state);
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.submissions);
    }

    @Test
    public void spaceCheckIncludesBothKnownStreamsBeforeCreatingOutput() throws Exception {
        select(DownloadPreparation.Kind.VIDEO,
                video(MediaFormat.MPEG_4, DeliveryMethod.PROGRESSIVE_HTTP),
                audio(MediaFormat.M4A), 100, 47);
        when(environment.folder.getFreeStorageSpace()).thenReturn(120L);
        saveToFolder();
        assertEquals(DownloadPreparation.FailureReason.INSUFFICIENT_STORAGE,
                environment.failures.get(0).reason);
        assertFalse(Files.exists(environment.output));
        assertTrue(environment.launches.isEmpty());
    }

    @Test
    public void pickedFolderGetsSameSpaceCheckBeforeCreatingOutput() throws Exception {
        preparation.save("recording", DownloadPreparation.Destination.savedFolder(null),
                environment);
        when(environment.folder.getFreeStorageSpace()).thenReturn(100L);
        environment.location.complete(environment.folderUri);
        assertEquals(DownloadPreparation.FailureReason.INSUFFICIENT_STORAGE,
                environment.failures.get(0).reason);
        assertFalse(Files.exists(environment.output));
        assertTrue(environment.launches.isEmpty());
    }

    @Test
    public void closedModuleIgnoresOldPickerCallback() throws Exception {
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        final DownloadPreparation.LocationRequest request = environment.location;
        preparation.close();
        request.complete(environment.fileUri);
        assertFalse(Files.exists(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertTrue(environment.failures.isEmpty());
        assertEquals(0, environment.resolutions);
    }

    @Test
    public void closedModuleIgnoresOldCollisionCallback() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        final DownloadPreparation.CollisionRequest request = environment.collision;
        preparation.close();
        request.confirm();
        assertEquals("pending recording", Files.readString(environment.output));
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
        assertTrue(environment.failures.isEmpty());
    }

    @Test
    public void newerSaveInvalidatesOldPickerCallbackAndSavedDescriptor() throws Exception {
        preparation.save("first", DownloadPreparation.Destination.askDocument(), environment);
        final DownloadPreparation.LocationRequest first = environment.location;
        final DownloadPreparation.Location oldState = serialized(first.state());
        final AudioStream replacement = audio(MediaFormat.WEBMA_OPUS);
        select(DownloadPreparation.Kind.AUDIO, replacement, null, 100, 0);
        preparation.save("second", DownloadPreparation.Destination.askDocument(), environment);
        final DownloadPreparation.LocationRequest second = environment.location;
        first.complete(environment.fileUri);
        preparation.resume(oldState, environment.fileUri, environment);
        assertFalse(Files.exists(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.resolutions);
        second.complete(environment.fileUri);
        assertEquals("second.opus", second.filename);
        assertEquals(1, environment.launches.size());
        assertEquals(1, environment.submissions);
        assertArrayEquals(new String[]{replacement.getContent()}, environment.launches.get(0).urls);
    }

    @Test
    public void newerSaveInvalidatesOldCollisionConfirmation() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        final DownloadPreparation.CollisionRequest first = environment.collision;
        select(DownloadPreparation.Kind.AUDIO, audio(MediaFormat.WEBMA_OPUS), null, 100, 0);
        preparation.save("second", DownloadPreparation.Destination.askDocument(), environment);
        first.confirm();
        assertEquals("pending recording", Files.readString(environment.output));
        assertEquals(MissionState.Pending, environment.state);
        assertTrue(environment.forgotten.isEmpty());
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.submissions);
    }

    @Test
    public void invalidNewSaveStillInvalidatesEarlierPickerAttempt() throws Exception {
        preparation.save("first", DownloadPreparation.Destination.askDocument(), environment);
        final DownloadPreparation.LocationRequest first = environment.location;
        selectUnsupportedSecondary();
        preparation.save("invalid", DownloadPreparation.Destination.askDocument(), environment);
        assertEquals(DownloadPreparation.FailureReason.INVALID_SELECTION,
                environment.failures.get(0).reason);
        first.complete(environment.fileUri);
        assertFalse(Files.exists(environment.output));
        assertTrue(environment.launches.isEmpty());
        assertEquals(0, environment.resolutions);
        assertEquals(0, environment.submissions);
    }

    @Test
    public void acceptedDispatchReportsSuccessDespiteMetadataCleanupFailure() throws Exception {
        Files.writeString(environment.output, "pending recording");
        environment.state = MissionState.Pending;
        saveToFolder();
        final DownloadPreparation.CollisionRequest request = environment.collision;
        environment.failForget = true;
        request.confirm();
        request.confirm();
        assertEquals(1, environment.launches.size());
        assertEquals(1, environment.submissions);
        assertTrue(environment.failures.isEmpty());
        assertEquals(1, environment.cleanupWarnings.size());
        assertEquals("metadata cleanup failed", environment.cleanupWarnings.get(0).getMessage());
    }

    private void selectUnsupportedSecondary() {
        final AudioStream unsupported = new AudioStream.Builder().setId("unsupported")
                .setContent("https://example.com/manifest", true).setMediaFormat(MediaFormat.M4A)
                .setDeliveryMethod(DeliveryMethod.HLS).setAverageBitrate(128).build();
        select(DownloadPreparation.Kind.VIDEO,
                video(MediaFormat.MPEG_4, DeliveryMethod.PROGRESSIVE_HTTP), unsupported, 100, 47);
    }

    private static DownloadPreparation.Location serialized(final DownloadPreparation.Location state)
            throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(state);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (DownloadPreparation.Location) input.readObject();
        }
    }

    private void saveToFolder() {
        preparation.save("recording",
                DownloadPreparation.Destination.savedFolder(environment.folder), environment);
    }

    private DownloadPreparation.Launch submitDocument() {
        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);
        assertNotNull(environment.location);
        environment.location.complete(environment.fileUri);
        return environment.launches.get(environment.launches.size() - 1);
    }

    private void select(final DownloadPreparation.Kind kind, final Stream primary,
                        final AudioStream secondary, final long primarySize,
                        final long secondarySize) {
        selected = new DownloadPreparation.Selection(kind, primary, secondary, primarySize,
                secondarySize, 5, info, segments);
    }

    private static AudioStream audio(final MediaFormat format) {
        return new AudioStream.Builder().setId(format.name())
                .setContent("https://example.com/audio/" + format.name(), true)
                .setMediaFormat(format).setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setAverageBitrate(128).build();
    }

    private static VideoStream video(final MediaFormat format, final DeliveryMethod delivery) {
        return new VideoStream.Builder().setId(format.name())
                .setContent("https://example.com/video/" + format.name(), true)
                .setMediaFormat(format).setDeliveryMethod(delivery)
                .setIsVideoOnly(true).setResolution("720p").build();
    }
}
