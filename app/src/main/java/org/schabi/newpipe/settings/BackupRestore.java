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

import java.io.IOException;

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
        void cleanImportedPreferences();
        void restart();
        void reportRestoreFailure(Throwable error);
    }

    private final BackupOperations operations;
    private final ImportExportManager archives;
    private final SharedPreferences preferences;
    private final String pathKey;
    private final Platform platform;
    private final Scheduler main;

    BackupRestore(final Context context) {
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
            platform.checkpoint();
            archives.exportDatabase(preferences, platform.document(destination));
            preferences.edit().putString(pathKey, destination.toString()).apply();
            return true;
        }).ignoreElement();
    }

    Single<Inspection> inspect(final Uri source) {
        return operations.submit(() -> {
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
            if (choice == RestoreChoice.DATABASE_AND_SETTINGS) {
                if (inspection.preferenceFormat == PreferenceFormat.JSON) {
                    archives.loadJsonPrefs(inspection.source, preferences);
                } else {
                    archives.loadSerializedPrefs(inspection.source, preferences);
                }
                platform.cleanImportedPreferences();
            }
            archives.ensureDbDirectoryExists();
            if (!archives.stageDb(inspection.source)) {
                throw new IOException("Backup does not contain a database");
            }
            preferences.edit().putString(pathKey, inspection.uri.toString()).apply();
            return true;
        }).observeOn(main)
                .doOnSuccess(ignored -> platform.restart())
                .doOnError(platform::reportRestoreFailure)
                .cache().ignoreElement();
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
        public void cleanImportedPreferences() {
            final SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(context);
            final String tunnelingKey = context.getString(R.string.disable_media_tunneling_key);
            final String automaticKey =
                    context.getString(R.string.disabled_media_tunneling_automatically_key);
            if (prefs.getInt(automaticKey, -1) == 1 && prefs.getBoolean(tunnelingKey, false)) {
                prefs.edit().putInt(automaticKey, -1).putBoolean(tunnelingKey, false).apply();
                NewPipeSettings.setMediaTunneling(context);
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
