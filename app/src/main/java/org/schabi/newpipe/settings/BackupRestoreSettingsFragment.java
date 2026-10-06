package org.schabi.newpipe.settings;

import static org.schabi.newpipe.extractor.utils.Utils.isBlank;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;


import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.local.subscription.SubscriptionsImportExportHelper;
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.NavigationHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class BackupRestoreSettingsFragment extends BasePreferenceFragment {

    private static final String ZIP_MIME_TYPE = "application/zip";

    private final SimpleDateFormat exportDateFormat =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private BackupRestore backups;
    private String importExportDataPathKey;
    private final ActivityResultLauncher<Intent> requestImportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::requestImportPathResult);
    private final ActivityResultLauncher<Intent> requestExportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::requestExportPathResult);
    private SubscriptionsImportExportHelper importExportHelper;
    private final CompositeDisposable backupObservers = new CompositeDisposable();
    private androidx.appcompat.app.AlertDialog importSettingsDialog;


    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        importExportHelper = new SubscriptionsImportExportHelper(this);
    }

    @Override
    public void onCreatePreferences(@Nullable final Bundle savedInstanceState,
                                    @Nullable final String rootKey) {
        backups = new BackupRestore(requireContext().getApplicationContext());

        importExportDataPathKey = getString(R.string.import_export_data_path);

        addPreferencesFromResourceRegistry();

        final Preference importDataPreference = requirePreference(R.string.import_data);
        importDataPreference.setOnPreferenceClickListener((Preference p) -> {
            NoFileManagerSafeGuard.launchSafe(
                    requestImportPathLauncher,
                    StoredFileHelper.getPicker(requireContext(),
                            ZIP_MIME_TYPE, getImportExportDataUri()),
                    TAG,
                    getContext()
            );

            return true;
        });

        final Preference exportDataPreference = requirePreference(R.string.export_data);
        exportDataPreference.setOnPreferenceClickListener((final Preference p) -> {
            NoFileManagerSafeGuard.launchSafe(
                    requestExportPathLauncher,
                    StoredFileHelper.getNewPicker(requireContext(),
                            "PVCPipe-data-" + exportDateFormat.format(new Date()) + ".zip",
                            ZIP_MIME_TYPE, getImportExportDataUri()),
                    TAG,
                    getContext()
            );

            return true;
        });

        final Preference resetSettings = requirePreference(R.string.reset_settings);
        // Resets all settings by deleting shared preference and restarting the app
        // A dialogue will pop up to confirm if user intends to reset all settings
        resetSettings.setOnPreferenceClickListener(preference -> {
            // Show Alert Dialogue
            final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setMessage(R.string.reset_all_settings);
            builder.setCancelable(true);
            builder.setPositiveButton(R.string.ok, (dialogInterface, i) -> {
                // Deletes all shared preferences xml files.
                final SharedPreferences sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(requireContext());
                sharedPreferences.edit().clear().apply();
                // Restarts the app
                if (getActivity() == null) {
                    return;
                }
                NavigationHelper.restartApp(getActivity());
            });
            builder.setNegativeButton(R.string.cancel, (dialogInterface, i) -> {
            });
            final AlertDialog alertDialog = builder.create();
            alertDialog.show();
            return true;
        });

        final Preference exportSubsPreference =
                requirePreference(R.string.export_subscriptions_key);
        exportSubsPreference.setOnPreferenceClickListener(reference -> {
            importExportHelper.onExportSelected();
            return true;
        });

        final Preference importSubsPreference =
                requirePreference(R.string.import_subscriptions_key);
        importSubsPreference.setOnPreferenceClickListener(preference -> {
            importExportHelper.onImportPreviousSelected();
            return true;
        });

    }

    private void requestExportPathResult(final ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            // will be saved only on success
            final Uri lastExportDataUri = result.getData().getData();

            exportDatabase(lastExportDataUri);
        }
    }

    private void requestImportPathResult(final ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            // will be saved only on success
            final Uri lastImportDataUri = result.getData().getData();

            importSettingsDialog = new androidx.appcompat.app.AlertDialog.Builder(requireActivity())
                    .setMessage(R.string.override_current_data)
                    .setPositiveButton(R.string.ok, (d, id) ->
                            importDatabase(lastImportDataUri))
                    .setNegativeButton(R.string.cancel, (d, id) ->
                            d.cancel())
                    .show();
        }
    }

    @Override
    public void onDestroyView() {
        backupObservers.clear();
        if (importSettingsDialog != null) {
            importSettingsDialog.dismiss();
            importSettingsDialog = null;
        }
        super.onDestroyView();
    }

    private void exportDatabase(final Uri exportDataUri) {
        backupObservers.add(backups.exportTo(exportDataUri)
                .observeOn(AndroidSchedulers.mainThread()).subscribe(() ->
                        Toast.makeText(requireContext(), R.string.export_complete_toast,
                                Toast.LENGTH_SHORT).show(), error ->
                        showErrorSnackbar(error, "Exporting database and settings")));
    }

    private void importDatabase(final Uri importDataUri) {
        backupObservers.add(backups.inspect(importDataUri)
                .observeOn(AndroidSchedulers.mainThread()).subscribe(inspection -> {
                    if (!inspection.readable()) {
                        Toast.makeText(requireContext(), R.string.no_valid_zip_file,
                                Toast.LENGTH_SHORT).show();
                    } else if (inspection.preferenceFormat()
                            != BackupRestore.PreferenceFormat.NONE) {
                        importSettingsDialog =
                                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle(R.string.import_settings)
                                .setMessage(inspection.preferenceFormat()
                                        == BackupRestore.PreferenceFormat.JSON ? null : getString(
                                        R.string.import_settings_vulnerable_format))
                                .setNegativeButton(R.string.cancel, (dialog, which) ->
                                        performImport(inspection,
                                                BackupRestore.RestoreChoice.DATABASE_ONLY))
                                .setPositiveButton(R.string.ok, (dialog, which) ->
                                        performImport(inspection,
                                                BackupRestore.RestoreChoice.DATABASE_AND_SETTINGS))
                                .show();
                    } else {
                        performImport(inspection, BackupRestore.RestoreChoice.DATABASE_ONLY);
                    }
                }, error -> showErrorSnackbar(error, "Inspecting backup")));
    }

    private void performImport(final BackupRestore.Inspection inspection,
                               final BackupRestore.RestoreChoice choice) {
        backupObservers.add(backups.restore(inspection, choice)
                .subscribe(() -> { }, error -> { }));
    }

    private Uri getImportExportDataUri() {
        final String path = defaultPreferences.getString(importExportDataPathKey, null);
        return isBlank(path) ? null : Uri.parse(path);
    }

    private void showErrorSnackbar(final Throwable e, final String request) {
        ErrorUtil.showSnackbar(this, new ErrorInfo(e, UserAction.DATABASE_IMPORT_EXPORT, request));
    }

}
