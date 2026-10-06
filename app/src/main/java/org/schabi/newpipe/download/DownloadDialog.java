package org.schabi.newpipe.download;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;
import static org.schabi.newpipe.util.ListHelper.getStreamsOfSpecifiedDelivery;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.collection.SparseArrayCompat;
import androidx.preference.PreferenceManager;

import com.evernote.android.state.State;
import com.livefront.bridge.Bridge;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DownloadDialogBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.AudioTrackAdapter;
import org.schabi.newpipe.util.AudioTrackAdapter.AudioTracksWrapper;
import org.schabi.newpipe.util.FilenameUtils;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.SecondaryStreamHelper;
import org.schabi.newpipe.util.ServiceBinding;
import org.schabi.newpipe.util.SimpleOnSeekBarChangeListener;
import org.schabi.newpipe.util.StreamItemAdapter;
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import us.shandian.giga.service.DownloadManagerService;
import us.shandian.giga.service.DownloadManagerService.DownloadManagerBinder;

public class DownloadDialog extends PvcDownloadDialog
        implements RadioGroup.OnCheckedChangeListener, AdapterView.OnItemSelectedListener {
    private static final String TAG = "DialogFragment";
    private static final boolean DEBUG = MainActivity.DEBUG;

    @State
    StreamInfo currentInfo;
    @State
    StreamInfoWrapper<VideoStream> wrappedVideoStreams;
    @State
    StreamInfoWrapper<SubtitlesStream> wrappedSubtitleStreams;
    @State
    AudioTracksWrapper wrappedAudioTracks;
    @State
    int selectedAudioTrackIndex;
    @State
    int selectedVideoIndex; // set in the constructor
    @State
    int selectedAudioIndex = 0; // default to the first item
    @State
    int selectedSubtitleIndex = 0; // default to the first item

    private StoredDirectoryHelper mainStorageAudio = null;
    private StoredDirectoryHelper mainStorageVideo = null;
    private DownloadPreparation downloadPreparation;
    private MenuItem okButton = null;
    private Context context = null;
    private boolean askForSavePath;

    private AudioTrackAdapter audioTrackAdapter;
    private StreamItemAdapter<AudioStream, Stream> audioStreamsAdapter;
    private StreamItemAdapter<VideoStream, AudioStream> videoStreamsAdapter;
    private StreamItemAdapter<SubtitlesStream, Stream> subtitleStreamsAdapter;

    private final CompositeDisposable disposables = new CompositeDisposable();

    private DownloadDialogBinding dialogBinding;

    private SharedPreferences prefs;
    private ServiceBinding downloadServiceBinding;

    @State
    DownloadPreparation.Location pendingLocationState;
    @State
    Uri pendingLocationResult;

    private final ActivityResultLauncher<Intent> requestDownloadSaveAsLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadSaveAsResult);
    private final ActivityResultLauncher<Intent> requestDownloadPickAudioFolderLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadPickAudioFolderResult);
    private final ActivityResultLauncher<Intent> requestDownloadPickVideoFolderLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadPickVideoFolderResult);

    /*//////////////////////////////////////////////////////////////////////////
    // Instance creation
    //////////////////////////////////////////////////////////////////////////*/

    public DownloadDialog() {
        // Just an empty default no-arg ctor to keep Fragment.instantiate() happy
        // otherwise InstantiationException will be thrown when fragment is recreated
        // TODO: Maybe use a custom FragmentFactory instead?
    }

    /**
     * Create a new download dialog with the video, audio and subtitle streams from the provided
     * stream info. Video streams and video-only streams will be put into a single list menu,
     * sorted according to their resolution and the default video resolution will be selected.
     *
     * @param context the context to use just to obtain preferences and strings (will not be stored)
     * @param info    the info from which to obtain downloadable streams and other info (e.g. title)
     */
    public DownloadDialog(@NonNull final Context context, @NonNull final StreamInfo info) {
        this.currentInfo = info;

        final List<AudioStream> audioStreams =
                getStreamsOfSpecifiedDelivery(info.getAudioStreams(), PROGRESSIVE_HTTP);
        final List<List<AudioStream>> groupedAudioStreams =
                ListHelper.getGroupedAudioStreams(context, audioStreams);
        this.wrappedAudioTracks = new AudioTracksWrapper(groupedAudioStreams, context);
        this.selectedAudioTrackIndex =
                ListHelper.getDefaultAudioTrackGroup(context, groupedAudioStreams);

        // TODO: Adapt this code when the downloader support other types of stream deliveries
        final List<VideoStream> videoStreams = ListHelper.getSortedStreamVideosList(
                context,
                pvcAddHlsStreams(info,
                        getStreamsOfSpecifiedDelivery(info.getVideoStreams(), PROGRESSIVE_HTTP)),
                getStreamsOfSpecifiedDelivery(info.getVideoOnlyStreams(), PROGRESSIVE_HTTP),
                false,
                // If there are multiple languages available, prefer streams without audio
                // to allow language selection
                wrappedAudioTracks.size() > 1
        );

        this.wrappedVideoStreams = new StreamInfoWrapper<>(videoStreams, context);
        this.wrappedSubtitleStreams = new StreamInfoWrapper<>(
                getStreamsOfSpecifiedDelivery(info.getSubtitles(), PROGRESSIVE_HTTP), context);

        this.selectedVideoIndex = ListHelper.getDefaultResolutionIndex(context, videoStreams);
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Android lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) {
            Log.d(TAG, "onCreate() called with: "
                    + "savedInstanceState = [" + savedInstanceState + "]");
        }

        context = getContext();

        setStyle(STYLE_NO_TITLE, ThemeHelper.getDialogTheme(context));
        Bridge.restoreInstanceState(this, savedInstanceState);

        this.audioTrackAdapter = new AudioTrackAdapter(wrappedAudioTracks);
        this.subtitleStreamsAdapter = new StreamItemAdapter<>(wrappedSubtitleStreams);
        updateSecondaryStreams();

        final Intent intent = new Intent(context, DownloadManagerService.class);
        context.startService(intent);
        downloadServiceBinding = new ServiceBinding(context, intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName cname, final IBinder service) {
                if (dialogBinding == null) {
                    downloadServiceBinding.unbind();
                    return;
                }
                final DownloadManagerBinder mgr = (DownloadManagerBinder) service;

                mainStorageAudio = mgr.getMainStorageAudio();
                mainStorageVideo = mgr.getMainStorageVideo();
                downloadPreparation = new DownloadPreparation(context, mgr.getDownloadManager(),
                        DownloadDialog.this::selectedDownload);
                askForSavePath = mgr.askForSavePath();

                okButton.setEnabled(true);
                downloadServiceBinding.unbind();
                resumePendingLocation();
            }

            @Override
            public void onServiceDisconnected(final ComponentName name) {
                // nothing to do
            }
        });
    }

    /**
     * Update the displayed video streams based on the selected audio track.
     */
    private void updateSecondaryStreams() {
        final StreamInfoWrapper<AudioStream> audioStreams = getWrappedAudioStreams();
        final var secondaryStreams = new SparseArrayCompat<SecondaryStreamHelper<AudioStream>>(4);
        final List<VideoStream> videoStreams = wrappedVideoStreams.getStreamsList();
        wrappedVideoStreams.resetInfo();

        for (int i = 0; i < videoStreams.size(); i++) {
            if (!videoStreams.get(i).isVideoOnly()) {
                continue;
            }
            final AudioStream audioStream = SecondaryStreamHelper.getAudioStreamFor(
                    context, audioStreams.getStreamsList(), videoStreams.get(i));

            if (audioStream != null) {
                secondaryStreams.append(i, new SecondaryStreamHelper<>(audioStreams, audioStream));
            } else if (DEBUG) {
                final MediaFormat mediaFormat = videoStreams.get(i).getFormat();
                if (mediaFormat != null) {
                    Log.w(TAG, "No audio stream candidates for video format "
                            + mediaFormat.name());
                } else {
                    Log.w(TAG, "No audio stream candidates for unknown video format");
                }
            }
        }

        this.videoStreamsAdapter = new StreamItemAdapter<>(wrappedVideoStreams, secondaryStreams);
        this.audioStreamsAdapter = new StreamItemAdapter<>(audioStreams);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             final ViewGroup container,
                             final Bundle savedInstanceState) {
        if (DEBUG) {
            Log.d(TAG, "onCreateView() called with: "
                    + "inflater = [" + inflater + "], container = [" + container + "], "
                    + "savedInstanceState = [" + savedInstanceState + "]");
        }
        return inflater.inflate(R.layout.download_dialog, container);
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        dialogBinding = DownloadDialogBinding.bind(view);

        dialogBinding.fileName.setText(FilenameUtils.createFilename(getContext(),
                currentInfo.getName()));
        selectedAudioIndex = ListHelper.getDefaultAudioFormat(getContext(),
                getWrappedAudioStreams().getStreamsList());

        selectedSubtitleIndex = getSubtitleIndexBy(subtitleStreamsAdapter.getAll());

        dialogBinding.qualitySpinner.setOnItemSelectedListener(this);
        dialogBinding.audioStreamSpinner.setOnItemSelectedListener(this);
        dialogBinding.audioTrackSpinner.setOnItemSelectedListener(this);
        dialogBinding.videoAudioGroup.setOnCheckedChangeListener(this);

        initToolbar(dialogBinding.toolbarLayout.toolbar);
        downloadServiceBinding.bind(Context.BIND_AUTO_CREATE);

        loadSponsorBlockSegments(currentInfo, okButton, dialogBinding);

        setupDownloadOptions();

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        final int threads = prefs.getInt(getString(R.string.default_download_threads), 3);
        dialogBinding.threadsCount.setText(String.valueOf(threads));
        dialogBinding.threads.setProgress(threads - 1);
        dialogBinding.threads.setOnSeekBarChangeListener(new SimpleOnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(@NonNull final SeekBar seekbar,
                                          final int progress,
                                          final boolean fromUser) {
                final int newProgress = progress + 1;
                prefs.edit().putInt(getString(R.string.default_download_threads), newProgress)
                        .apply();
                dialogBinding.threadsCount.setText(String.valueOf(newProgress));
            }
        });

        fetchStreamsSize();
    }

    private void initToolbar(final Toolbar toolbar) {
        if (DEBUG) {
            Log.d(TAG, "initToolbar() called with: toolbar = [" + toolbar + "]");
        }

        toolbar.setTitle(R.string.download_dialog_title);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.inflateMenu(R.menu.dialog_url);
        toolbar.setNavigationOnClickListener(v -> dismiss());
        toolbar.setNavigationContentDescription(R.string.cancel);

        okButton = toolbar.getMenu().findItem(R.id.okay);
        okButton.setEnabled(false); // disable until the download service connection is done

        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.okay) {
                prepareSelectedDownload();
                return true;
            }
            return false;
        });
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        downloadPreparation = null;
        downloadServiceBinding.unbind();
        okButton = null;
        dialogBinding = null;
        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        Bridge.saveInstanceState(this, outState);
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Video, audio and subtitle spinners
    //////////////////////////////////////////////////////////////////////////*/

    private void fetchStreamsSize() {
        disposables.clear();
        disposables.add(StreamInfoWrapper.fetchMoreInfoForWrapper(currentInfo,
                wrappedVideoStreams)
                .subscribe(result -> {
                    if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId()
                            == R.id.video_button) {
                        setupVideoSpinner();
                    }
                }, throwable -> ErrorUtil.showSnackbar(context,
                        new ErrorInfo(throwable, UserAction.DOWNLOAD_OPEN_DIALOG,
                                "Downloading video stream size", currentInfo))));
        disposables.add(StreamInfoWrapper.fetchMoreInfoForWrapper(currentInfo,
                getWrappedAudioStreams())
                .subscribe(result -> {
                    if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId()
                            == R.id.audio_button) {
                        setupAudioSpinner();
                    }
                }, throwable -> ErrorUtil.showSnackbar(context,
                        new ErrorInfo(throwable, UserAction.DOWNLOAD_OPEN_DIALOG,
                                "Downloading audio stream size", currentInfo))));
        disposables.add(StreamInfoWrapper.fetchMoreInfoForWrapper(currentInfo,
                        wrappedSubtitleStreams)
                .subscribe(result -> {
                    if (dialogBinding.videoAudioGroup.getCheckedRadioButtonId()
                            == R.id.subtitle_button) {
                        setupSubtitleSpinner();
                    }
                }, throwable -> ErrorUtil.showSnackbar(context,
                        new ErrorInfo(throwable, UserAction.DOWNLOAD_OPEN_DIALOG,
                                "Downloading subtitle stream size", currentInfo))));
    }

    private void setupAudioTrackSpinner() {
        if (getContext() == null) {
            return;
        }

        dialogBinding.audioTrackSpinner.setAdapter(audioTrackAdapter);
        dialogBinding.audioTrackSpinner.setSelection(selectedAudioTrackIndex);
    }

    private void setupAudioSpinner() {
        if (getContext() == null) {
            return;
        }

        dialogBinding.qualitySpinner.setVisibility(View.GONE);
        setRadioButtonsState(true);
        dialogBinding.audioStreamSpinner.setAdapter(audioStreamsAdapter);
        dialogBinding.audioStreamSpinner.setSelection(selectedAudioIndex);
        dialogBinding.audioStreamSpinner.setVisibility(View.VISIBLE);
        dialogBinding.audioTrackSpinner.setVisibility(
                wrappedAudioTracks.size() > 1 ? View.VISIBLE : View.GONE);
        dialogBinding.audioTrackPresentInVideoText.setVisibility(View.GONE);
    }

    private void setupVideoSpinner() {
        if (getContext() == null) {
            return;
        }

        dialogBinding.qualitySpinner.setAdapter(videoStreamsAdapter);
        dialogBinding.qualitySpinner.setSelection(selectedVideoIndex);
        dialogBinding.qualitySpinner.setVisibility(View.VISIBLE);
        setRadioButtonsState(true);
        dialogBinding.audioStreamSpinner.setVisibility(View.GONE);
        onVideoStreamSelected();
    }

    private void onVideoStreamSelected() {
        final boolean isVideoOnly = videoStreamsAdapter.getItem(selectedVideoIndex).isVideoOnly();

        dialogBinding.audioTrackSpinner.setVisibility(
                isVideoOnly && wrappedAudioTracks.size() > 1 ? View.VISIBLE : View.GONE);
        dialogBinding.audioTrackPresentInVideoText.setVisibility(
                !isVideoOnly && wrappedAudioTracks.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private void setupSubtitleSpinner() {
        if (getContext() == null) {
            return;
        }

        dialogBinding.qualitySpinner.setAdapter(subtitleStreamsAdapter);
        dialogBinding.qualitySpinner.setSelection(selectedSubtitleIndex);
        dialogBinding.qualitySpinner.setVisibility(View.VISIBLE);
        setRadioButtonsState(true);
        dialogBinding.audioStreamSpinner.setVisibility(View.GONE);
        dialogBinding.audioTrackSpinner.setVisibility(View.GONE);
        dialogBinding.audioTrackPresentInVideoText.setVisibility(View.GONE);
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Activity results
    //////////////////////////////////////////////////////////////////////////*/

    private void requestDownloadPickAudioFolderResult(final ActivityResult result) {
        completeLocation(result);
    }

    private void requestDownloadPickVideoFolderResult(final ActivityResult result) {
        completeLocation(result);
    }

    private void requestDownloadSaveAsResult(@NonNull final ActivityResult result) {
        completeLocation(result);
    }

    private void completeLocation(final ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK) {
            return;
        }
        if (result.getData() == null || result.getData().getData() == null) {
            showFailedDialog(R.string.general_error);
            return;
        }
        pendingLocationResult = result.getData().getData();
        resumePendingLocation();
    }

    private void resumePendingLocation() {
        if (downloadPreparation == null || dialogBinding == null || pendingLocationState == null
                || pendingLocationResult == null) {
            return;
        }
        final DownloadPreparation.Location location = pendingLocationState;
        final Uri result = pendingLocationResult;
        pendingLocationState = null;
        pendingLocationResult = null;
        downloadPreparation.resume(location, result, new DownloadPresentation());
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Listeners
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCheckedChanged(final RadioGroup group, @IdRes final int checkedId) {
        if (DEBUG) {
            Log.d(TAG, "onCheckedChanged() called with: "
                    + "group = [" + group + "], checkedId = [" + checkedId + "]");
        }
        boolean flag = true;

        if (checkedId == R.id.audio_button) {
            setupAudioSpinner();
        } else if (checkedId == R.id.video_button) {
            setupVideoSpinner();
        } else if (checkedId == R.id.subtitle_button) {
            setupSubtitleSpinner();
            flag = false;
        }

        dialogBinding.threads.setEnabled(flag);
    }

    @Override
    public void onItemSelected(final AdapterView<?> parent,
                               final View view,
                               final int position,
                               final long id) {
        if (DEBUG) {
            Log.d(TAG, "onItemSelected() called with: "
                    + "parent = [" + parent + "], view = [" + view + "], "
                    + "position = [" + position + "], id = [" + id + "]");
        }

        final int parentId = parent.getId();
        if (parentId == R.id.quality_spinner) {
            final int checkedRadioButtonId = dialogBinding.videoAudioGroup
                    .getCheckedRadioButtonId();
            if (checkedRadioButtonId == R.id.video_button) {
                selectedVideoIndex = position;
                onVideoStreamSelected();
            } else if (checkedRadioButtonId == R.id.subtitle_button) {
                selectedSubtitleIndex = position;
            }
            onItemSelectedSetFileName();
        } else if (parentId == R.id.audio_track_spinner) {
            final boolean trackChanged = selectedAudioTrackIndex != position;
            selectedAudioTrackIndex = position;
            if (trackChanged) {
                updateSecondaryStreams();
                fetchStreamsSize();
            }
        } else if (parentId == R.id.audio_stream_spinner) {
            selectedAudioIndex = position;
        }
    }

    private void onItemSelectedSetFileName() {
        final String fileName = FilenameUtils.createFilename(getContext(), currentInfo.getName());
        final String prevFileName = Optional.ofNullable(dialogBinding.fileName.getText())
                .map(Object::toString)
                .orElse("");

        if (prevFileName.isEmpty()
                || prevFileName.equals(fileName)
                || prevFileName.startsWith(getString(R.string.caption_file_name, fileName, ""))) {
            // only update the file name field if it was not edited by the user

            final int radioButtonId = dialogBinding.videoAudioGroup
                    .getCheckedRadioButtonId();
            if (radioButtonId == R.id.audio_button || radioButtonId == R.id.video_button) {
                if (!prevFileName.equals(fileName)) {
                    // since the user might have switched between audio and video, the correct
                    // text might already be in place, so avoid resetting the cursor position
                    dialogBinding.fileName.setText(fileName);
                }
            } else if (radioButtonId == R.id.subtitle_button) {
                final String setSubtitleLanguageCode = subtitleStreamsAdapter
                        .getItem(selectedSubtitleIndex).getLanguageTag();
                // this will reset the cursor position, which is bad UX, but it can't be avoided
                dialogBinding.fileName.setText(getString(
                        R.string.caption_file_name, fileName, setSubtitleLanguageCode));
            }
        }
    }

    @Override
    public void onNothingSelected(final AdapterView<?> parent) {
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Download
    //////////////////////////////////////////////////////////////////////////*/

    protected void setupDownloadOptions() {
        setRadioButtonsState(false);
        setupAudioTrackSpinner();

        final boolean isVideoStreamsAvailable = videoStreamsAdapter.getCount() > 0;
        final boolean isAudioStreamsAvailable = audioStreamsAdapter.getCount() > 0;
        final boolean isSubtitleStreamsAvailable = subtitleStreamsAdapter.getCount() > 0;

        dialogBinding.audioButton.setVisibility(isAudioStreamsAvailable ? View.VISIBLE
                : View.GONE);
        dialogBinding.videoButton.setVisibility(isVideoStreamsAvailable ? View.VISIBLE
                : View.GONE);
        dialogBinding.subtitleButton.setVisibility(isSubtitleStreamsAvailable
                ? View.VISIBLE : View.GONE);

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        final String defaultMedia = prefs.getString(getString(R.string.last_used_download_type),
                getString(R.string.last_download_type_video_key));

        if (isVideoStreamsAvailable
                && (defaultMedia.equals(getString(R.string.last_download_type_video_key)))) {
            dialogBinding.videoButton.setChecked(true);
            setupVideoSpinner();
        } else if (isAudioStreamsAvailable
                && (defaultMedia.equals(getString(R.string.last_download_type_audio_key)))) {
            dialogBinding.audioButton.setChecked(true);
            setupAudioSpinner();
        } else if (isSubtitleStreamsAvailable
                && (defaultMedia.equals(getString(R.string.last_download_type_subtitle_key)))) {
            dialogBinding.subtitleButton.setChecked(true);
            setupSubtitleSpinner();
        } else if (isVideoStreamsAvailable) {
            dialogBinding.videoButton.setChecked(true);
            setupVideoSpinner();
        } else if (isAudioStreamsAvailable) {
            dialogBinding.audioButton.setChecked(true);
            setupAudioSpinner();
        } else if (isSubtitleStreamsAvailable) {
            dialogBinding.subtitleButton.setChecked(true);
            setupSubtitleSpinner();
        } else {
            Toast.makeText(getContext(), R.string.no_streams_available_download,
                    Toast.LENGTH_SHORT).show();
            dismiss();
        }
    }

    private void setRadioButtonsState(final boolean enabled) {
        dialogBinding.audioButton.setEnabled(enabled);
        dialogBinding.videoButton.setEnabled(enabled);
        dialogBinding.subtitleButton.setEnabled(enabled);
    }

    private StreamInfoWrapper<AudioStream> getWrappedAudioStreams() {
        if (selectedAudioTrackIndex < 0 || selectedAudioTrackIndex > wrappedAudioTracks.size()) {
            return StreamInfoWrapper.empty();
        }
        return wrappedAudioTracks.getTracksList().get(selectedAudioTrackIndex);
    }

    private int getSubtitleIndexBy(@NonNull final List<SubtitlesStream> streams) {
        final Localization preferredLocalization = NewPipe.getPreferredLocalization();

        int candidate = 0;
        for (int i = 0; i < streams.size(); i++) {
            final Locale streamLocale = streams.get(i).getLocale();

            final boolean languageEquals = streamLocale.getLanguage() != null
                    && preferredLocalization.getLanguageCode() != null
                    && streamLocale.getLanguage()
                    .equals(new Locale(preferredLocalization.getLanguageCode()).getLanguage());
            final boolean countryEquals = streamLocale.getCountry() != null
                    && streamLocale.getCountry().equals(preferredLocalization.getCountryCode());

            if (languageEquals) {
                if (countryEquals) {
                    return i;
                }

                candidate = i;
            }
        }

        return candidate;
    }

    @NonNull
    private String getNameEditText() {
        final String str = Objects.requireNonNull(dialogBinding.fileName.getText()).toString()
                .trim();

        return FilenameUtils.createFilename(context, str.isEmpty() ? currentInfo.getName() : str);
    }

    private void showFailedDialog(@StringRes final int msg) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.general_error)
                .setMessage(msg)
                .setNegativeButton(getString(R.string.ok), null)
                .show();
    }

    private void launchDirectoryPicker(final ActivityResultLauncher<Intent> launcher) {
        NoFileManagerSafeGuard.launchSafe(launcher, StoredDirectoryHelper.getPicker(context), TAG,
                context);
    }

    private void prepareSelectedDownload() {
        final DownloadPreparation.Destination destination;
        if (askForSavePath) {
            destination = DownloadPreparation.Destination.askDocument();
        } else {
            final StoredDirectoryHelper folder = dialogBinding.videoAudioGroup
                    .getCheckedRadioButtonId() == R.id.audio_button
                    ? mainStorageAudio : mainStorageVideo;
            destination = DownloadPreparation.Destination.savedFolder(folder);
        }
        downloadPreparation.save(getNameEditText(), destination, new DownloadPresentation());
    }

    private DownloadPreparation.Selection selectedDownload() {
        final int selected = dialogBinding.videoAudioGroup.getCheckedRadioButtonId();
        final DownloadPreparation.Kind kind;
        final Stream stream;
        AudioStream secondaryStream = null;
        final long size;
        long secondarySize = 0;
        if (selected == R.id.audio_button) {
            kind = DownloadPreparation.Kind.AUDIO;
            stream = audioStreamsAdapter.getItem(selectedAudioIndex);
            size = getWrappedAudioStreams().getSizeInBytes(selectedAudioIndex);
        } else if (selected == R.id.video_button) {
            kind = DownloadPreparation.Kind.VIDEO;
            stream = videoStreamsAdapter.getItem(selectedVideoIndex);
            size = wrappedVideoStreams.getSizeInBytes(selectedVideoIndex);
            final SecondaryStreamHelper<AudioStream> secondary = videoStreamsAdapter
                    .getAllSecondary().get(wrappedVideoStreams.getStreamsList().indexOf(stream));
            if (secondary != null) {
                secondaryStream = secondary.getStream();
                secondarySize = secondary.getSizeInBytes();
            }
        } else if (selected == R.id.subtitle_button) {
            kind = DownloadPreparation.Kind.SUBTITLE;
            stream = subtitleStreamsAdapter.getItem(selectedSubtitleIndex);
            size = wrappedSubtitleStreams.getSizeInBytes(selectedSubtitleIndex);
        } else {
            return null;
        }
        return new DownloadPreparation.Selection(kind, stream, secondaryStream, size, secondarySize,
                dialogBinding.threads.getProgress() + 1, currentInfo, pvcSponsorBlockSegments());
    }

    private final class DownloadPresentation implements DownloadPreparation.Presentation {
        @Override
        public void chooseLocation(final DownloadPreparation.LocationRequest request) {
            pendingLocationState = request.state();
            pendingLocationResult = null;
            if (request.type == DownloadPreparation.LocationType.DOCUMENT) {
                NoFileManagerSafeGuard.launchSafe(requestDownloadSaveAsLauncher,
                        StoredFileHelper.getNewPicker(context, request.filename, request.mime,
                                null),
                        TAG, context);
            } else {
                Toast.makeText(context, getString(R.string.no_dir_yet), Toast.LENGTH_LONG).show();
                launchDirectoryPicker(request.kind == DownloadPreparation.Kind.AUDIO
                        ? requestDownloadPickAudioFolderLauncher
                        : requestDownloadPickVideoFolderLauncher);
            }
        }

        @Override
        public void confirmCollision(final DownloadPreparation.CollisionRequest request) {
            final int message;
            switch (request.reason) {
                case FINISHED:
                    message = R.string.overwrite_finished_warning;
                    break;
                case PENDING:
                    message = R.string.download_already_pending;
                    break;
                case RUNNING:
                    message = R.string.download_already_running;
                    break;
                case UNRELATED:
                    message = R.string.overwrite_unrelated_warning;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown collision reason");
            }
            final AlertDialog.Builder askDialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.download_dialog_title)
                    .setMessage(message)
                    .setNegativeButton(R.string.cancel, null);
            if (request.action != DownloadPreparation.CollisionAction.NONE) {
                final int button = request.action == DownloadPreparation.CollisionAction.UNIQUE_NAME
                        ? R.string.generate_unique_name : R.string.overwrite;
                askDialog.setPositiveButton(button, (dialog, which) -> {
                    dialog.dismiss();
                    request.confirm();
                });
            }
            askDialog.show();
        }

        @Override
        public void submitted() {
            Toast.makeText(context, getString(R.string.download_has_started),
                    Toast.LENGTH_SHORT).show();
            dismiss();
        }

        @Override
        public void failed(final DownloadPreparation.Failure failure) {
            final int message;
            switch (failure.reason) {
                case STORAGE:
                    ErrorUtil.createNotification(requireContext(), new ErrorInfo(failure.cause,
                            UserAction.DOWNLOAD_FAILED, "Getting storage"));
                    return;
                case PATH_CREATION:
                    message = R.string.error_path_creation;
                    break;
                case FILE_CREATION:
                    message = R.string.error_file_creation;
                    break;
                case PERMISSION_DENIED:
                    message = R.string.permission_denied;
                    break;
                case OVERWRITE:
                    Log.e(TAG, "Failed to truncate the download file", failure.cause);
                    message = R.string.overwrite_failed;
                    break;
                case INSUFFICIENT_STORAGE:
                    Toast.makeText(context, getString(R.string.error_insufficient_storage),
                            Toast.LENGTH_LONG).show();
                    final Intent storageSettings =
                            new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                    if (storageSettings.resolveActivity(context.getPackageManager()) != null) {
                        startActivity(storageSettings);
                    }
                    return;
                default:
                    message = R.string.general_error;
                    break;
            }
            showFailedDialog(message);
        }
    }
}
