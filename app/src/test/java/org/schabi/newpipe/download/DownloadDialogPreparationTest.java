package org.schabi.newpipe.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.MenuItem;
import android.widget.RadioGroup;
import android.widget.SeekBar;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.collection.SparseArrayCompat;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DownloadDialogBinding;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.SponsorBlockSegment;
import org.schabi.newpipe.util.StreamItemAdapter;
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Exercises the real dialog adapter without constructing an Android Fragment or launching UI. */
public class DownloadDialogPreparationTest {
    private static final String AUDIO_RESULT = "requestDownloadPickAudioFolderResult";
    private static final String VIDEO_RESULT = "requestDownloadPickVideoFolderResult";
    private static final String DOCUMENT_RESULT = "requestDownloadSaveAsResult";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private DownloadDialog dialog;
    private DownloadPreparation module;
    private MenuItem button;
    private Uri resultUri;

    @Before
    public void setUp() throws Exception {
        dialog = mock(DownloadDialog.class, CALLS_REAL_METHODS);
        module = mock(DownloadPreparation.class);
        button = mock(MenuItem.class);
        resultUri = mock(Uri.class);
        inject("downloadPreparation", module);
        inject("okButton", button);
        inject("dialogBinding", mock(DownloadDialogBinding.class));
    }

    @Test
    public void audioFolderRequestIgnoresVideoAndDocumentResults() throws Exception {
        dialog.pendingLocationState = location(DownloadPreparation.Kind.AUDIO, false);
        assertWrongSourcesPreserveRequest(VIDEO_RESULT, DOCUMENT_RESULT);
    }

    @Test
    public void videoFolderRequestIgnoresAudioAndDocumentResults() throws Exception {
        dialog.pendingLocationState = location(DownloadPreparation.Kind.VIDEO, false);
        assertWrongSourcesPreserveRequest(AUDIO_RESULT, DOCUMENT_RESULT);
    }

    @Test
    public void documentRequestIgnoresBothFolderResults() throws Exception {
        dialog.pendingLocationState = location(DownloadPreparation.Kind.AUDIO, true);
        assertWrongSourcesPreserveRequest(AUDIO_RESULT, VIDEO_RESULT);
    }

    @Test
    public void matchingResultWaitsForServiceAndConsumesPendingStateOnce() throws Exception {
        final DownloadPreparation.Location state = location(DownloadPreparation.Kind.AUDIO, true);
        dialog.pendingLocationState = state;
        inject("downloadPreparation", null);
        final ActivityResult result = successResult();
        invokeResult(DOCUMENT_RESULT, result);
        assertSame(state, dialog.pendingLocationState);
        assertSame(resultUri, dialog.pendingLocationResult);
        verifyNoInteractions(module, button);

        doAnswer(call -> {
            assertNull(dialog.pendingLocationState);
            assertNull(dialog.pendingLocationResult);
            return null;
        }).when(module).resume(any(), any(), any());
        inject("downloadPreparation", module);
        invoke("resumePendingLocation");
        invoke("resumePendingLocation");
        invokeResult(DOCUMENT_RESULT, result);
        verify(module, times(1)).resume(eq(state), eq(resultUri), any());
        verify(button, times(1)).setEnabled(true);
        assertNull(dialog.pendingLocationState);
        assertNull(dialog.pendingLocationResult);
    }

    @Test
    public void matchingCancellationClearsRequestAndEnablesBoundDialog() throws Exception {
        dialog.pendingLocationState = location(DownloadPreparation.Kind.AUDIO, false);
        dialog.pendingLocationResult = resultUri;
        invokeResult(AUDIO_RESULT, new ActivityResult(Activity.RESULT_CANCELED, null));
        assertNull(dialog.pendingLocationState);
        assertNull(dialog.pendingLocationResult);
        verify(button).setEnabled(true);
        verifyNoInteractions(module);
    }

    @Test
    public void matchingCancellationKeepsOfflineDialogDisabled() throws Exception {
        dialog.pendingLocationState = location(DownloadPreparation.Kind.AUDIO, true);
        inject("downloadPreparation", null);
        invokeResult(DOCUMENT_RESULT, new ActivityResult(Activity.RESULT_CANCELED, null));
        assertNull(dialog.pendingLocationState);
        assertNull(dialog.pendingLocationResult);
        verify(button).setEnabled(false);
        verifyNoInteractions(module);
    }

    @Test
    public void outstandingPickerBlocksStartingAnotherDownload() throws Exception {
        final DownloadPreparation.Location state = location(DownloadPreparation.Kind.AUDIO, true);
        dialog.pendingLocationState = state;
        invoke("prepareSelectedDownload");
        assertSame(state, dialog.pendingLocationState);
        verifyNoInteractions(module, button);
    }

    @Test
    public void subtitleFolderSelectionAcceptsVideoFolderLauncherResult() throws Exception {
        final DownloadPreparation.Location state = location(DownloadPreparation.Kind.SUBTITLE,
                false);
        dialog.pendingLocationState = state;
        invokeResult(VIDEO_RESULT, successResult());
        verify(module).resume(eq(state), eq(resultUri), any());
        verify(button).setEnabled(true);
        assertNull(dialog.pendingLocationState);
        assertNull(dialog.pendingLocationResult);
    }

    @Test
    public void unavailableFileManagerClearsPickerAndAllowsAnotherAttempt() throws Exception {
        final Context context = mock(Context.class);
        final Intent picker = mock(Intent.class);
        @SuppressWarnings("unchecked")
        final ActivityResultLauncher<Intent> launcher = mock(ActivityResultLauncher.class);
        inject("context", context);
        inject("requestDownloadSaveAsLauncher", launcher);
        final DownloadPreparation.LocationRequest first =
                locationRequest(DownloadPreparation.Kind.AUDIO, true);
        final DownloadPreparation.LocationRequest second =
                locationRequest(DownloadPreparation.Kind.AUDIO, true);
        doThrow(mock(ActivityNotFoundException.class)).doNothing().when(launcher).launch(picker);
        final DownloadPreparation.Presentation presentation = presentation();
        try (var storage = mockStatic(StoredFileHelper.class);
             var logging = mockStatic(Log.class);
             var alerts = mockConstruction(AlertDialog.Builder.class,
                     withSettings().defaultAnswer(RETURNS_SELF))) {
            storage.when(() -> StoredFileHelper.getNewPicker(context, first.filename, first.mime,
                    null)).thenReturn(picker);
            presentation.chooseLocation(first);
            assertNull(dialog.pendingLocationState);
            assertNull(dialog.pendingLocationResult);
            assertEquals(1, alerts.constructed().size());
            verify(button).setEnabled(true);
            verifyNoInteractions(module);
            presentation.chooseLocation(second);
            assertSame(second.state(), dialog.pendingLocationState);
            invokeResult(DOCUMENT_RESULT, successResult());
            verify(module).resume(eq(second.state()), eq(resultUri), any());
            verify(launcher, times(2)).launch(picker);
            assertNull(dialog.pendingLocationState);
            assertNull(dialog.pendingLocationResult);
        }
    }

    @Test
    public void selectedVideoCarriesTheInferredWrapperFormatIntoPreparation() throws Exception {
        final VideoStream stream = new VideoStream.Builder().setId("unknown")
                .setContent("https://example.com/video", true)
                .setResolution("720p").setIsVideoOnly(false).build();
        final StreamInfoWrapper<VideoStream> wrapper = new StreamInfoWrapper<>(
                List.of(stream), null);
        wrapper.setFormat(stream, MediaFormat.WEBM);
        wrapper.setSize(stream, 1234);
        @SuppressWarnings("unchecked")
        final StreamItemAdapter<VideoStream, AudioStream> adapter = mock(StreamItemAdapter.class);
        when(adapter.getItem(0)).thenReturn(stream);
        when(adapter.getAllSecondary()).thenReturn(new SparseArrayCompat<>(0));
        inject("videoStreamsAdapter", adapter);
        inject("sponsorBlockSegments", new SponsorBlockSegment[0]);
        dialog.wrappedVideoStreams = wrapper;
        dialog.selectedVideoIndex = 0;
        dialog.currentInfo = new StreamInfo(0, "https://example.com/watch",
                "https://example.com/watch", StreamType.VIDEO_STREAM, "id", "recording", 0);
        final DownloadDialogBinding binding = mock(DownloadDialogBinding.class);
        final RadioGroup group = mock(RadioGroup.class);
        when(group.getCheckedRadioButtonId()).thenReturn(R.id.video_button);
        final SeekBar threads = mock(SeekBar.class);
        when(threads.getProgress()).thenReturn(2);
        final Field groupField = DownloadDialogBinding.class.getField("videoAudioGroup");
        groupField.setAccessible(true);
        groupField.set(binding, group);
        final Field threadsField = DownloadDialogBinding.class.getField("threads");
        threadsField.setAccessible(true);
        threadsField.set(binding, threads);
        inject("dialogBinding", binding);
        final Method method = DownloadDialog.class.getDeclaredMethod("selectedDownload");
        method.setAccessible(true);
        final DownloadPreparation.Selection selection =
                (DownloadPreparation.Selection) method.invoke(dialog);
        final DownloadPreparationEnvironment environment = new DownloadPreparationEnvironment(
                temporary.newFolder().toPath());
        final DownloadPreparation preparation = new DownloadPreparation(environment,
                () -> selection);

        preparation.save("recording", DownloadPreparation.Destination.askDocument(), environment);

        assertEquals(MediaFormat.WEBM, selection.primaryFormat);
        assertEquals(1234, selection.primarySize);
        assertEquals("recording.webm", environment.location.filename);
        environment.location.complete(environment.fileUri);
        assertEquals(MediaFormat.WEBM, environment.launches.get(0).recoveryInfo.get(0).getFormat());
        assertNull(stream.getFormat());
    }

    @Test
    public void hlsSidecarNeedsSingleUseExplicitSilentVideoConfirmation() throws Exception {
        configureHlsSidecar();
        final AlertDialog window = mock(AlertDialog.class);
        try (var alerts = mockConstruction(AlertDialog.Builder.class,
                withSettings().defaultAnswer(RETURNS_SELF),
                (builder, context) -> when(builder.create()).thenReturn(window));
             var names = mockStatic(org.schabi.newpipe.util.FilenameUtils.class)) {
            names.when(() -> org.schabi.newpipe.util.FilenameUtils.createFilename(any(), any()))
                    .thenReturn("recording");
            invoke("prepareSelectedDownload");
            verifyNoInteractions(module);
            final var click = org.mockito.ArgumentCaptor.forClass(
                    android.content.DialogInterface.OnClickListener.class);
            verify(alerts.constructed().get(0)).setPositiveButton(
                    eq(R.string.download_video_without_audio), click.capture());
            click.getValue().onClick(window, android.content.DialogInterface.BUTTON_POSITIVE);
            click.getValue().onClick(window, android.content.DialogInterface.BUTTON_POSITIVE);
            verify(module, times(1)).save(eq("recording"), any(), any());
        }
    }

    @Test
    public void canceledOrChangedHlsSidecarConfirmationHasNoDestinationEffects() throws Exception {
        for (final boolean dismissed : new boolean[]{true, false}) {
            configureHlsSidecar();
            final AlertDialog window = mock(AlertDialog.class);
            try (var alerts = mockConstruction(AlertDialog.Builder.class,
                    withSettings().defaultAnswer(RETURNS_SELF),
                    (builder, context) -> when(builder.create()).thenReturn(window))) {
                invoke("prepareSelectedDownload");
                final var click = org.mockito.ArgumentCaptor.forClass(
                        android.content.DialogInterface.OnClickListener.class);
                verify(alerts.constructed().get(0)).setPositiveButton(
                        eq(R.string.download_video_without_audio), click.capture());
                if (dismissed) {
                    final var dismiss = org.mockito.ArgumentCaptor.forClass(
                            android.content.DialogInterface.OnDismissListener.class);
                    verify(window).setOnDismissListener(dismiss.capture());
                    dismiss.getValue().onDismiss(window);
                } else {
                    dialog.selectedAudioTrackIndex = 1;
                }
                click.getValue().onClick(window, android.content.DialogInterface.BUTTON_POSITIVE);
                verifyNoInteractions(module);
                inject("silentVideoDialog", null);
            }
        }
    }

    @Test
    public void previousIntentionalSilentVideoNeedsNoNewConfirmation() throws Exception {
        configureHlsSidecar();
        inject("wrappedAudioTracks", new org.schabi.newpipe.util.AudioTrackAdapter
                .AudioTracksWrapper(List.of(), null));
        try (var alerts = mockConstruction(AlertDialog.Builder.class,
                withSettings().defaultAnswer(RETURNS_SELF));
             var names = mockStatic(org.schabi.newpipe.util.FilenameUtils.class)) {
            names.when(() -> org.schabi.newpipe.util.FilenameUtils.createFilename(any(), any()))
                    .thenReturn("recording");
            invoke("prepareSelectedDownload");
            assertEquals(0, alerts.constructed().size());
            verify(module).save(eq("recording"), any(), any());
        }
    }

    @Test
    public void trackChangeRebindsVisibleSpinnerBeforePendingHeaderLookup() throws Exception {
        final DownloadDialogBinding binding = configureHlsSidecar();
        final AudioStream progressive = new AudioStream.Builder().setId("progressive")
                .setContent("https://media.test/audio.m4a", true).setMediaFormat(MediaFormat.M4A)
                .setAverageBitrate(128).build();
        final AudioStream hls = hlsAudio();
        inject("wrappedAudioTracks", new org.schabi.newpipe.util.AudioTrackAdapter
                .AudioTracksWrapper(
                        List.of(List.of(progressive, progressive), List.of(hls)), null));
        inject("disposables", new io.reactivex.rxjava3.disposables.CompositeDisposable());
        dialog.wrappedSubtitleStreams = new StreamInfoWrapper<>(List.of(), null);
        dialog.selectedAudioIndex = 1;
        final android.widget.Spinner parent = mock(android.widget.Spinner.class);
        when(parent.getId()).thenReturn(R.id.audio_track_spinner);
        when(binding.videoAudioGroup.getCheckedRadioButtonId()).thenReturn(R.id.audio_button);
        when(dialog.getContext()).thenReturn(mock(Context.class));
        // A real fallback invokes AndroidSchedulers while stubbing, poisoning its class in
        // this plain JVM. Keep every size request on the controlled, pending Single.
        try (var lookups = mockStatic(StreamInfoWrapper.class);
             var defaults = mockStatic(org.schabi.newpipe.util.ListHelper.class);
             var pairing = mockStatic(org.schabi.newpipe.util.SecondaryStreamHelper.class);
             var logging = mockStatic(Log.class)) {
            lookups.when(() -> StreamInfoWrapper.fetchMoreInfoForWrapper(any(), any()))
                    .thenReturn(io.reactivex.rxjava3.core.Single.never());
            dialog.onItemSelected(parent, null, 1, 1);
            lookups.verify(() -> StreamInfoWrapper.fetchMoreInfoForWrapper(any(), any()), times(3));
            final var adapter = org.mockito.ArgumentCaptor.forClass(
                    android.widget.SpinnerAdapter.class);
            verify(binding.audioStreamSpinner).setAdapter(adapter.capture());
            assertEquals(1, adapter.getValue().getCount());
            assertSame(hls, adapter.getValue().getItem(0));
            assertEquals(0, dialog.selectedAudioIndex);
            verify(binding.audioStreamSpinner).setSelection(0);
        }
        dialog.selectedAudioTrackIndex = 2;
        final Method method = DownloadDialog.class.getDeclaredMethod("getWrappedAudioStreams");
        method.setAccessible(true);
        assertEquals(0, ((StreamInfoWrapper<?>) method.invoke(dialog)).getStreamsList().size());
    }

    private DownloadDialogBinding configureHlsSidecar() throws Exception {
        final VideoStream video = new VideoStream.Builder().setId("video-only")
                .setContent("https://media.test/video.mp4", true).setMediaFormat(MediaFormat.MPEG_4)
                .setResolution("720p").setIsVideoOnly(true).build();
        final StreamInfoWrapper<VideoStream> wrapper =
                new StreamInfoWrapper<>(List.of(video), null);
        @SuppressWarnings("unchecked")
        final StreamItemAdapter<VideoStream, AudioStream> adapter = mock(StreamItemAdapter.class);
        when(adapter.getItem(0)).thenReturn(video);
        when(adapter.getAllSecondary()).thenReturn(new SparseArrayCompat<>(0));
        inject("videoStreamsAdapter", adapter);
        inject("sponsorBlockSegments", new SponsorBlockSegment[0]);
        inject("wrappedAudioTracks", new org.schabi.newpipe.util.AudioTrackAdapter
                .AudioTracksWrapper(List.of(List.of(hlsAudio())), null));
        inject("context", mock(Context.class));
        inject("askForSavePath", true);
        dialog.wrappedVideoStreams = wrapper;
        dialog.selectedVideoIndex = 0;
        dialog.selectedAudioTrackIndex = 0;
        dialog.currentInfo = new StreamInfo(0, "https://media.test/watch",
                "https://media.test/watch",
                StreamType.VIDEO_STREAM, "id", "recording", 0);
        final DownloadDialogBinding binding = mock(DownloadDialogBinding.class);
        for (final String name : List.of(
                "audioStreamSpinner", "audioTrackSpinner", "qualitySpinner")) {
            setBinding(binding, name, mock(android.widget.Spinner.class));
        }
        for (final String name : List.of("audioButton", "videoButton", "subtitleButton")) {
            setBinding(binding, name, mock(android.widget.RadioButton.class));
        }
        setBinding(binding, "audioTrackPresentInVideoText",
                mock(org.schabi.newpipe.views.NewPipeTextView.class));
        final RadioGroup group = mock(RadioGroup.class);
        when(group.getCheckedRadioButtonId()).thenReturn(R.id.video_button);
        setBinding(binding, "videoAudioGroup", group);
        final SeekBar threads = mock(SeekBar.class);
        when(threads.getProgress()).thenReturn(2);
        setBinding(binding, "threads", threads);
        final org.schabi.newpipe.views.NewPipeEditText name =
                mock(org.schabi.newpipe.views.NewPipeEditText.class);
        final android.text.Editable text = mock(android.text.Editable.class);
        when(text.toString()).thenReturn("recording");
        when(name.getText()).thenReturn(text);
        setBinding(binding, "fileName", name);
        inject("dialogBinding", binding);
        return binding;
    }

    private static AudioStream hlsAudio() {
        return new AudioStream.Builder().setId("aac-hls")
                .setContent("https://media.test/audio.m3u8", true).setMediaFormat(MediaFormat.M4A)
                .setDeliveryMethod(DeliveryMethod.HLS).setAverageBitrate(160).build();
    }

    private static void setBinding(final DownloadDialogBinding binding, final String name,
                                   final Object value) throws Exception {
        final Field field = DownloadDialogBinding.class.getField(name);
        field.setAccessible(true);
        field.set(binding, value);
    }

    private DownloadPreparation.Presentation presentation() throws Exception {
        final Class<?> type = Class.forName(
                DownloadDialog.class.getName() + "$DownloadPresentation");
        final Constructor<?> constructor = type.getDeclaredConstructor(DownloadDialog.class);
        constructor.setAccessible(true);
        return (DownloadPreparation.Presentation) constructor.newInstance(dialog);
    }

    private void assertWrongSourcesPreserveRequest(final String first, final String second)
            throws Exception {
        final DownloadPreparation.Location state = dialog.pendingLocationState;
        dialog.pendingLocationResult = resultUri;
        for (final String source : new String[]{first, second}) {
            invokeResult(source, successResult());
            assertSame(state, dialog.pendingLocationState);
            assertSame(resultUri, dialog.pendingLocationResult);
            invokeResult(source, new ActivityResult(Activity.RESULT_CANCELED, null));
            assertSame(state, dialog.pendingLocationState);
            assertSame(resultUri, dialog.pendingLocationResult);
        }
        verifyNoInteractions(module, button);
    }

    private ActivityResult successResult() {
        final Intent intent = mock(Intent.class);
        when(intent.getData()).thenReturn(resultUri);
        return new ActivityResult(Activity.RESULT_OK, intent);
    }

    private DownloadPreparation.Location location(final DownloadPreparation.Kind kind,
                                                  final boolean document) throws Exception {
        return locationRequest(kind, document).state();
    }

    private DownloadPreparation.LocationRequest locationRequest(final DownloadPreparation.Kind kind,
                                                                final boolean document)
            throws Exception {
        final Stream stream;
        switch (kind) {
            case AUDIO:
                stream = new AudioStream.Builder().setId("audio")
                        .setContent("https://example.com/audio", true)
                        .setMediaFormat(MediaFormat.M4A)
                        .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                        .setAverageBitrate(128).build();
                break;
            case VIDEO:
                stream = new VideoStream.Builder().setId("video")
                        .setContent("https://example.com/video", true)
                        .setMediaFormat(MediaFormat.MPEG_4)
                        .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                        .setResolution("720p").setIsVideoOnly(false).build();
                break;
            case SUBTITLE:
                stream = new SubtitlesStream.Builder()
                        .setContent("https://example.com/subtitles", true)
                        .setMediaFormat(MediaFormat.SRT)
                        .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                        .setLanguageCode("en").setAutoGenerated(false).build();
                break;
            default:
                throw new AssertionError("Unexpected selection kind");
        }
        final StreamInfo info = new StreamInfo(0, "https://example.com/watch",
                "https://example.com/watch", StreamType.VIDEO_STREAM, "id", "recording", 0);
        final DownloadPreparation.Selection selection = new DownloadPreparation.Selection(kind,
                stream, stream.getFormat(), null, 100, 0, 3, info, new SponsorBlockSegment[0]);
        final DownloadPreparationEnvironment environment = new DownloadPreparationEnvironment(
                temporary.newFolder().toPath());
        final DownloadPreparation preparation = new DownloadPreparation(environment,
                () -> selection);
        preparation.save("recording", document ? DownloadPreparation.Destination.askDocument()
                : DownloadPreparation.Destination.savedFolder(null), environment);
        return environment.location;
    }

    private void inject(final String name, final Object value) throws Exception {
        final Field field = DownloadDialog.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(dialog, value);
    }

    private void invokeResult(final String name, final ActivityResult result) throws Exception {
        final Method method = DownloadDialog.class.getDeclaredMethod(name, ActivityResult.class);
        invoke(method, result);
    }

    private void invoke(final String name) throws Exception {
        invoke(DownloadDialog.class.getDeclaredMethod(name));
    }

    private void invoke(final Method method, final Object... arguments) throws Exception {
        method.setAccessible(true);
        try {
            method.invoke(dialog, arguments);
        } catch (final InvocationTargetException error) {
            throw new AssertionError("Dialog handler failed", error.getCause());
        }
    }
}
