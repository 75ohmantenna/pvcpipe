package org.schabi.newpipe.streams.io;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.R;

/**
 * Helper for when no file-manager/activity was found.
 */
public final class NoFileManagerSafeGuard {
    private NoFileManagerSafeGuard() {
        // No impl
    }

    /**
     * Shows an alert dialog when no file-manager is found.
     * @param context Context
     */
    private static void showActivityNotFoundAlert(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "Unable to open no file manager alert dialog: Context is null");
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.no_app_to_open_intent)
                .setMessage(R.string.no_appropriate_file_manager_message_android_10)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /**
     * Attempts to launch a file or directory picker. If no activity handles the launch,
     * shows an alert dialog.
     *
     * @param activityResultLauncher see {@link ActivityResultLauncher#launch(Object)}
     * @param input see {@link ActivityResultLauncher#launch(Object)}
     * @param tag Tag used for logging
     * @param context Context
     * @param <I> see {@link ActivityResultLauncher#launch(Object)}
     * @return whether the launch request completed without {@link ActivityNotFoundException}
     */
    public static <I> boolean launchSafe(
            final ActivityResultLauncher<I> activityResultLauncher,
            final I input,
            final String tag,
            final Context context
    ) {
        try {
            activityResultLauncher.launch(input);
            return true;
        } catch (final ActivityNotFoundException aex) {
            Log.w(tag, "Unable to launch file/directory picker", aex);
            NoFileManagerSafeGuard.showActivityNotFoundAlert(context);
            return false;
        }
    }
}
