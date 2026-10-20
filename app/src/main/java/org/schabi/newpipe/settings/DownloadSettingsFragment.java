package org.schabi.newpipe.settings;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.util.FilenameUtils;
import java.io.IOException;

public class DownloadSettingsFragment extends BasePreferenceFragment {
    private String downloadPathVideoPreference;
    private String downloadPathAudioPreference;

    private Preference prefPathVideo;
    private Preference prefPathAudio;
    private Preference prefStorageAsk;

    private Context ctx;
    private final ActivityResultLauncher<Intent> requestDownloadVideoPathLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadVideoPathResult);
    private final ActivityResultLauncher<Intent> requestDownloadAudioPathLauncher =
            registerForActivityResult(
                    new StartActivityForResult(), this::requestDownloadAudioPathResult);

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        downloadPathVideoPreference = getString(R.string.download_path_video_key);
        downloadPathAudioPreference = getString(R.string.download_path_audio_key);
        final String downloadStorageAsk = getString(R.string.downloads_storage_ask);

        prefPathVideo = findPreference(downloadPathVideoPreference);
        prefPathAudio = findPreference(downloadPathAudioPreference);
        prefStorageAsk = findPreference(downloadStorageAsk);


        updatePreferencesSummary();
        updatePathPickers(!defaultPreferences.getBoolean(downloadStorageAsk, false));

        prefStorageAsk.setOnPreferenceChangeListener((preference, value) -> {
            updatePathPickers(!(boolean) value);
            return true;
        });

        final Preference replacement =
                findPreference(getString(R.string.settings_file_replacement_character_key));
        if (replacement != null) {
            replacement.setOnPreferenceChangeListener((preference, value) -> {
                // FilenameUtils also checks the stored value, which an import can write.
                final boolean valid = FilenameUtils.isValidReplacement(String.valueOf(value));
                if (!valid) {
                    Toast.makeText(requireContext(),
                            R.string.settings_file_replacement_character_invalid,
                            Toast.LENGTH_SHORT).show();
                }
                return valid;
            });
        }
    }

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        ctx = context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        ctx = null;
        prefStorageAsk.setOnPreferenceChangeListener(null);
    }

    private void updatePreferencesSummary() {
        showPathInSummary(downloadPathVideoPreference, R.string.download_path_summary,
                prefPathVideo);
        showPathInSummary(downloadPathAudioPreference, R.string.download_path_audio_summary,
                prefPathAudio);
    }

    private void showPathInSummary(final String prefKey, @StringRes final int defaultString,
                                   final Preference target) {
        final Uri uri = Uri.parse(defaultPreferences.getString(prefKey, ""));
        if (uri.equals(Uri.EMPTY)) {
            target.setSummary(getString(defaultString));
            return;
        }

        final String summary = ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                ? uri.getPath() : uri.toString();
        target.setSummary(summary);
    }


    private void updatePathPickers(final boolean enabled) {
        prefPathVideo.setEnabled(enabled);
        prefPathAudio.setEnabled(enabled);
    }


    private void showMessageDialog(@StringRes final int title, @StringRes final int message) {
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.ok), null)
                .show();
    }

    @Override
    public boolean onPreferenceTreeClick(@NonNull final Preference preference) {
        if (DEBUG) {
            Log.d(TAG, "onPreferenceTreeClick() called with: "
                    + "preference = [" + preference + "]");
        }

        final String key = preference.getKey();

        if (key.equals(downloadPathVideoPreference)) {
            launchDirectoryPicker(requestDownloadVideoPathLauncher);
        } else if (key.equals(downloadPathAudioPreference)) {
            launchDirectoryPicker(requestDownloadAudioPathLauncher);
        } else {
            return super.onPreferenceTreeClick(preference);
        }

        return true;
    }

    private void launchDirectoryPicker(final ActivityResultLauncher<Intent> launcher) {
        NoFileManagerSafeGuard.launchSafe(
                launcher,
                StoredDirectoryHelper.getPicker(ctx),
                TAG,
                ctx
        );
    }

    private void requestDownloadVideoPathResult(final ActivityResult result) {
        requestDownloadPathResult(result, downloadPathVideoPreference);
    }

    private void requestDownloadAudioPathResult(final ActivityResult result) {
        requestDownloadPathResult(result, downloadPathAudioPreference);
    }

    private void requestDownloadPathResult(final ActivityResult result, final String key) {
        if (result.getResultCode() != Activity.RESULT_OK) {
            return;
        }

        Uri uri = null;
        if (result.getData() != null) {
            uri = result.getData().getData();
        }
        if (uri == null) {
            showMessageDialog(R.string.general_error, R.string.invalid_directory);
            return;
        }

        // Keep old tree grants: existing downloads can still reference previous destinations.
        final Context context = requireContext();
        try {
            final StoredDirectoryHelper storage = new StoredDirectoryHelper(context, uri, null);
            if (!storage.canWrite()) {
                throw new IOException("No write permissions on " + uri);
            }
        } catch (final IOException | SecurityException err) {
            Log.e(TAG, "Error acquiring tree from " + uri, err);
            showMessageDialog(R.string.general_error, R.string.no_available_dir);
            return;
        }

        defaultPreferences.edit().putString(key, uri.toString()).apply();
        updatePreferencesSummary();
    }
}
