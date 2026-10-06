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

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
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
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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
                stream, null, 100, 0, 3, info, new SponsorBlockSegment[0]);
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
