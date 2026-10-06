package org.schabi.newpipe.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import com.jakewharton.processphoenix.ProcessPhoenix;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.settings.export.BackupFileLocator;
import org.schabi.newpipe.settings.export.ImportExportManager;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.ZipHelper;
import org.schabi.newpipe.util.DeviceUtils;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;

/**
 * Application-owned backup workflow. First subscription attempts admission; accepted work survives
 * observer disposal. Reobserving the same request replays its outcome, while a new request retries.
 */
final class BackupRestore {
    enum PreferenceFormat { NONE, JSON, LEGACY_SERIALIZED }
    enum RestoreChoice { DATABASE_ONLY, DATABASE_AND_SETTINGS }

    /** Advisory information about a document; neither a snapshot nor database validation. */
    static final class Inspection {
        private final StoredFileHelper source;
        private final Uri uri;
        private final boolean readable;
        private final PreferenceFormat preferenceFormat;

        private Inspection(final StoredFileHelper source, final Uri uri, final boolean readable,
                           final PreferenceFormat preferenceFormat) {
            this.source = source;
            this.uri = uri;
            this.readable = readable;
            this.preferenceFormat = preferenceFormat;
        }

        boolean readable() {
            return readable;
        }

        PreferenceFormat preferenceFormat() {
            return preferenceFormat;
        }
    }

    /** Platform effects vary between Android and controlled workflow tests. */
    interface Platform {
        StoredFileHelper document(Uri uri);
        void checkpoint();
        void normalizeImportedPreferences(Map<String, Object> values);
        void restart();
        void reportRestoreFailure(Throwable error);
    }

    private static BackupRestore applicationInstance;

    static synchronized BackupRestore forApplication(final Context context) {
        if (applicationInstance == null) {
            applicationInstance = new BackupRestore(context.getApplicationContext());
        }
        return applicationInstance;
    }

    // Retained by the application instance if a preference rollback itself fails.
    private Map<String, Object> recoveryPreferences;
    private final BackupOperations operations;
    private final ImportExportManager archives;
    private final SharedPreferences preferences;
    private final String pathKey;
    private final Platform platform;
    private final Scheduler main;

    private BackupRestore(final Context context) {
        this(BackupOperations.INSTANCE, new ImportExportManager(new BackupFileLocator(context)),
                PreferenceManager.getDefaultSharedPreferences(context),
                context.getString(R.string.import_export_data_path),
                new AndroidPlatform(context.getApplicationContext()),
                AndroidSchedulers.mainThread());
    }

    BackupRestore(final BackupOperations operations, final ImportExportManager archives,
                  final SharedPreferences preferences, final String pathKey,
                  final Platform platform, final Scheduler main) {
        this.operations = operations;
        this.archives = archives;
        this.preferences = preferences;
        this.pathKey = pathKey;
        this.platform = platform;
        this.main = main;
    }

    Completable exportTo(final Uri destination) {
        return operations.submit(() -> {
            recoverPreferencesIfNeeded();
            if (archives.hasPendingRestore()) {
                throw new IOException("A restored database is awaiting restart");
            }
            platform.checkpoint();
            archives.exportDatabase(preferences, platform.document(destination));
            preferences.edit().putString(pathKey, destination.toString()).apply();
            return true;
        }).ignoreElement();
    }

    Single<Inspection> inspect(final Uri source) {
        return operations.submit(() -> {
            recoverPreferencesIfNeeded();
            final StoredFileHelper file = platform.document(source);
            if (!ZipHelper.isValidZipFile(file)) {
                return new Inspection(file, source, false, PreferenceFormat.NONE);
            }
            final PreferenceFormat format = archives.exportHasJsonPrefs(file)
                    ? PreferenceFormat.JSON : archives.exportHasSerializedPrefs(file)
                    ? PreferenceFormat.LEGACY_SERIALIZED : PreferenceFormat.NONE;
            return new Inspection(file, source, true, format);
        });
    }

    /**
     * Completion means staged and restart requested, not installed in the current process.
     * @param inspection advisory information returned by this module
     * @param choice whether to restore the offered preferences
     * @return cached completion of the accepted restore and its terminal effects
     */
    Completable restore(final Inspection inspection, final RestoreChoice choice) {
        return operations.submit(() -> {
            recoverPreferencesIfNeeded();
            final String settingsEntry = choice == RestoreChoice.DATABASE_ONLY ? null
                    : inspection.preferenceFormat == PreferenceFormat.JSON
                    ? BackupFileLocator.FILE_NAME_JSON_PREFS
                    : BackupFileLocator.FILE_NAME_SERIALIZED_PREFS;
            try (var prepared = archives.prepareRestore(inspection.source, settingsEntry)) {
                final Map<String, Object> previous = copyPreferences(preferences.getAll());
                final Map<String, Object> replacement = prepared.getPreferences() == null
                        ? copyPreferences(previous) : prepared.getPreferences();
                if (prepared.getPreferences() != null) {
                    platform.normalizeImportedPreferences(replacement);
                }
                replacement.put(pathKey, inspection.uri.toString());
                try {
                    ImportExportManager.replacePreferences(preferences, replacement);
                    prepared.publish();
                } catch (final Exception failure) {
                    recoveryPreferences = previous;
                    try {
                        recoverPreferencesIfNeeded();
                    } catch (final RestoreRecoveryRequiredException recovery) {
                        recovery.addSuppressed(failure);
                        throw recovery;
                    }
                    throw failure;
                }
            }
            return true;
        }).observeOn(main)
                .doOnSuccess(ignored -> {
                    try {
                        platform.restart();
                    } catch (final RuntimeException failure) {
                        throw new RestoreCommittedException(failure);
                    }
                })
                .doOnError(platform::reportRestoreFailure)
                .cache().ignoreElement();
    }

    private void recoverPreferencesIfNeeded() throws RestoreRecoveryRequiredException {
        if (recoveryPreferences != null) {
            try {
                ImportExportManager.replacePreferences(preferences, recoveryPreferences);
                recoveryPreferences = null;
            } catch (final Exception failure) {
                throw new RestoreRecoveryRequiredException(failure);
            }
        }
    }

    private static Map<String, Object> copyPreferences(final Map<String, ?> values) {
        final Map<String, Object> copy = new HashMap<>();
        values.forEach((key, value) -> copy.put(key,
                value instanceof Set<?> ? new HashSet<>((Set<?>) value) : value));
        return copy;
    }

    static final class RestoreRecoveryRequiredException extends IOException {
        RestoreRecoveryRequiredException(final Throwable cause) {
            super("Restore failed and settings recovery is incomplete; retry recovery", cause);
        }
    }

    static final class RestoreCommittedException extends IOException {
        RestoreCommittedException(final Throwable cause) {
            super("Backup is prepared; restart the application to finish restoring", cause);
        }
    }

    private static final class AndroidPlatform implements Platform {
        private final Context context;

        AndroidPlatform(final Context context) {
            this.context = context;
        }

        @Override
        public StoredFileHelper document(final Uri uri) {
            return new StoredFileHelper(context, uri, "application/zip");
        }

        @Override
        public void checkpoint() {
            NewPipeDatabase.checkpoint();
        }

        @Override
        public void normalizeImportedPreferences(final Map<String, Object> values) {
            final String tunnelingKey = context.getString(R.string.disable_media_tunneling_key);
            final String automaticKey =
                    context.getString(R.string.disabled_media_tunneling_automatically_key);
            if (Integer.valueOf(1).equals(values.get(automaticKey))
                    && Boolean.TRUE.equals(values.get(tunnelingKey))) {
                values.put(automaticKey, -1);
                values.put(tunnelingKey, false);
                if (!DeviceUtils.shouldSupportMediaTunneling()) {
                    values.put(tunnelingKey, true);
                    values.put(automaticKey, 1);
                }
                values.put(context.getString(R.string.media_tunneling_device_blacklist_version),
                        DeviceUtils.MEDIA_TUNNELING_DEVICE_BLACKLIST_VERSION);
            }
        }

        @Override
        public void restart() {
            ProcessPhoenix.triggerRebirth(context);
        }

        @Override
        public void reportRestoreFailure(final Throwable error) {
            ErrorUtil.createNotification(context, new ErrorInfo(error,
                    UserAction.DATABASE_IMPORT_EXPORT, "Importing backup"));
        }
    }
}
